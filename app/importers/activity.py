"""Analyse des autres contenus de l'export : profil, médias, activite.

Les exports Meta reposent presque tous sur trois formes generiques :
  string_map_data : {"Champ": {"value": ..., "timestamp": ...}}
  string_list_data: [{"href": ..., "value": ..., "timestamp": ...}]
  media_map_data  : {"Photo": {"uri": ..., "creation_timestamp": ...}}
On analyse ces formes plutot que des noms de fichiers precis, ce qui rend le
parseur resistant aux renommages de Meta.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from .. import db as dbmod
from ..cases import Case
from ..config import CONFIDENCE_CONFIRMED
from ..utils import (
    deep_fix_mojibake,
    media_kind,
    ms_to_iso,
    normalize_epoch_ms,
    read_text_file,
    safe_relpath,
    truncate,
)
from .detector import (
    CAT_ACCOUNT_HISTORY,
    CAT_ARCHIVED,
    CAT_COMMENTS,
    CAT_DEVICES,
    CAT_LIKES,
    CAT_LOGIN_ACTIVITY,
    CAT_POSTS,
    CAT_PROFILE,
    CAT_SEARCHES,
    CAT_STORIES,
)


@dataclass
class MetaEntry:
    title: str | None
    fields: dict[str, Any]
    timestamp_ms: int | None
    media: list[dict] = field(default_factory=list)
    links: list[str] = field(default_factory=list)
    raw: Any = None


def _iter_container(data: Any) -> Iterable[Any]:
    if isinstance(data, list):
        yield from data
    elif isinstance(data, dict):
        for value in data.values():
            if isinstance(value, list):
                yield from value
            elif isinstance(value, dict):
                yield from _iter_container(value)


def parse_meta_entries(data: Any) -> list[MetaEntry]:
    """Convertit n'importe quel bloc d'export Meta en entrées normalisées."""
    data = deep_fix_mojibake(data)
    out: list[MetaEntry] = []
    for entry in _iter_container(data):
        if not isinstance(entry, dict):
            continue
        title = entry.get("title") if isinstance(entry.get("title"), str) else None
        fields: dict[str, Any] = {}
        timestamp = normalize_epoch_ms(
            entry.get("creation_timestamp")
            if entry.get("creation_timestamp") is not None
            else entry.get("timestamp")
        )
        media: list[dict] = []
        links: list[str] = []

        smap = entry.get("string_map_data")
        if isinstance(smap, dict):
            for key, value in smap.items():
                if not isinstance(value, dict):
                    continue
                if value.get("value") is not None:
                    fields[key] = value.get("value")
                if value.get("href"):
                    links.append(str(value["href"]))
                ts = normalize_epoch_ms(value.get("timestamp"))
                if ts and timestamp is None:
                    timestamp = ts

        slist = entry.get("string_list_data")
        if isinstance(slist, list):
            for item in slist:
                if not isinstance(item, dict):
                    continue
                if item.get("href"):
                    links.append(str(item["href"]))
                if item.get("value") is not None:
                    values = fields.get("valeurs")
                    if not isinstance(values, list):
                        values = []
                        fields["valeurs"] = values
                    values.append(item["value"])
                ts = normalize_epoch_ms(item.get("timestamp"))
                if ts and timestamp is None:
                    timestamp = ts

        mmap = entry.get("media_map_data")
        if isinstance(mmap, dict):
            for key, value in mmap.items():
                if isinstance(value, dict) and value.get("uri"):
                    media.append(
                        {
                            "label": key,
                            "uri": value["uri"],
                            "timestamp_ms": normalize_epoch_ms(value.get("creation_timestamp")),
                        }
                    )

        media_list = entry.get("media")
        if isinstance(media_list, list):
            for item in media_list:
                if isinstance(item, dict) and item.get("uri"):
                    media.append(
                        {
                            "label": item.get("title") or None,
                            "uri": item["uri"],
                            "timestamp_ms": normalize_epoch_ms(item.get("creation_timestamp")),
                        }
                    )
        if entry.get("uri"):
            media.append(
                {
                    "label": title,
                    "uri": entry["uri"],
                    "timestamp_ms": normalize_epoch_ms(entry.get("creation_timestamp")),
                }
            )

        if not (title or fields or media or links):
            continue
        out.append(
            MetaEntry(
                title=title,
                fields=fields,
                timestamp_ms=timestamp,
                media=media,
                links=links,
                raw=entry,
            )
        )
    return out


PROFILE_FIELD_ALIASES = {
    "username": ("username", "nom d'utilisateur", "user name"),
    "full_name": ("name", "nom", "full name"),
    "biography": ("bio", "biography", "biographie"),
    "external_url": ("website", "site web", "url"),
    "email": ("email", "e-mail", "adresse e-mail"),
    "phone": ("phone", "telephone", "téléphone", "phone number"),
    "private": ("private account", "compte prive", "compte privé"),
    "birthday": ("date of birth", "date de naissance"),
}


def extract_profile(entries: list[MetaEntry]) -> dict[str, Any]:
    """Extrait les champs de profil d'un personal_information.json."""
    profile: dict[str, Any] = {"raw_fields": {}, "profile_photos": []}
    for entry in entries:
        for key, value in entry.fields.items():
            profile["raw_fields"][key] = value
            key_l = key.strip().lower()
            for target, aliases in PROFILE_FIELD_ALIASES.items():
                if key_l in aliases and value:
                    profile.setdefault(target, value)
        for item in entry.media:
            profile["profile_photos"].append(item)
        if entry.title and "username" not in profile:
            profile.setdefault("title", entry.title)
    return profile


def store_media(
    case: Case,
    source_id: int,
    entries: list[MetaEntry],
    context: str,
    file_id: int | None = None,
) -> int:
    count = 0
    case_id = case.case_id
    for entry in entries:
        for item in entry.media:
            uri = str(item.get("uri"))
            dbmod.insert(
                case.conn,
                "media",
                {
                    "case_id": case_id,
                    "source_id": source_id,
                    "file_id": file_id,
                    "rel_path": uri,
                    "kind": media_kind(uri) or "unknown",
                    "taken_at": ms_to_iso(item.get("timestamp_ms") or entry.timestamp_ms),
                    "context": context,
                    "caption": item.get("label") or entry.title,
                    "original_raw": dbmod.json_dumps(entry.raw),
                },
            )
            count += 1
    return count


EVENT_TEMPLATES = {
    CAT_LIKES: ("like", "J'aime"),
    CAT_COMMENTS: ("comment", "Commentaire"),
    CAT_SEARCHES: ("search", "Recherche"),
    CAT_LOGIN_ACTIVITY: ("login", "Connexion"),
    CAT_DEVICES: ("device", "Appareil"),
    CAT_POSTS: ("post", "Publication"),
    CAT_STORIES: ("story", "Story"),
    CAT_ARCHIVED: ("archived", "Contenu archive"),
    CAT_PROFILE: ("profile", "Profil"),
    CAT_ACCOUNT_HISTORY: ("account_history", "Historique du compte"),
}


def store_events(
    case: Case,
    category: str,
    entries: list[MetaEntry],
    source_ref: str,
    file_id: int | None = None,
) -> int:
    """Créé des événements de chronologie a partir d'entrées generiques.

    Le niveau de confiance est CONFIRME : ces événements proviennent
    directement de l'export officiel Meta, sans déduction.
    """
    kind, label = EVENT_TEMPLATES.get(category, ("activite", "Activite"))
    case_id = case.case_id
    count = 0
    for entry in entries:
        detail_bits = []
        for key, value in list(entry.fields.items())[:6]:
            if isinstance(value, list):
                value = ", ".join(str(v) for v in value[:5])
            detail_bits.append(f"{key} : {value}")
        for link in entry.links[:3]:
            detail_bits.append(link)
        title = entry.title or label
        dbmod.insert(
            case.conn,
            "events",
            {
                "case_id": case_id,
                "occurred_at": ms_to_iso(entry.timestamp_ms),
                "occurred_ms": entry.timestamp_ms,
                "kind": kind,
                "title": truncate(f"{label} — {title}", 200),
                "detail": truncate(" | ".join(detail_bits), 600) or None,
                "confidence": CONFIDENCE_CONFIRMED,
                "source_ref": source_ref,
                "file_id": file_id,
                "ref_table": None,
                "ref_id": None,
                "original_raw": dbmod.json_dumps(entry.raw),
            },
        )
        count += 1
    return count


def load_json(path: Path) -> Any:
    try:
        return json.loads(read_text_file(path))
    except (ValueError, OSError):
        return None


def html_entries(path: Path, root: Path | None = None) -> list[MetaEntry]:
    """Extraction generique d'entrées depuis une page HTML d'export."""
    from . import htmlkit

    try:
        html = read_text_file(path)
    except OSError:
        return []
    tree = htmlkit.parse_html(html)
    out: list[MetaEntry] = []
    for node in tree.iter_all():
        if node.tag not in {"div", "tr", "li"}:
            continue
        lines = node.text_lines()
        if not lines or len(lines) > 12:
            continue
        dated = htmlkit.find_date_in_text(lines[-1])
        if not dated:
            continue
        inner = [n for n in node.children if n.tag in {"div", "tr", "li"} and n.text_lines()]
        if any(htmlkit.find_date_in_text(c.text_lines()[-1]) for c in inner if c.text_lines()):
            continue
        from ..utils import iso_to_ms

        out.append(
            MetaEntry(
                title=lines[0],
                fields={"contenu": " / ".join(lines[1:-1])} if len(lines) > 2 else {},
                timestamp_ms=iso_to_ms(dated[1]),
                media=[{"label": None, "uri": u, "timestamp_ms": None} for u in htmlkit.collect_media(node)],
                links=htmlkit.collect_links(node),
                raw={"lines": lines, "source": "html", "file": safe_relpath(path, root) if root else path.name},
            )
        )
    return out
