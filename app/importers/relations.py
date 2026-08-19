"""Analyse des relations : abonnés, abonnements, demandes, blocages (section 7).

Règle stricte : une date n'est retenue que si elle existe REELLEMENT dans le
fichier source. L'ordre des entrées dans un JSON n'est jamais interprété comme
une chronologie ; en l'absence de champ timestamp, la date reste « Date
inconnue ».
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from .. import db as dbmod
from ..cases import Case
from ..config import UNKNOWN_DATE_LABEL
from ..utils import (
    deep_fix_mojibake,
    ms_to_iso,
    normalize_epoch_ms,
    read_text_file,
    safe_relpath,
    utcnow_iso,
)
from ..people import upsert_person
from . import htmlkit
from .detector import (
    CAT_BLOCKED,
    CAT_CLOSE_FRIENDS,
    CAT_FOLLOWERS,
    CAT_FOLLOWING,
    CAT_PENDING_RECEIVED,
    CAT_PENDING_SENT,
    CAT_RECENTLY_FOLLOWED,
    CAT_RECENTLY_UNFOLLOWED,
    CAT_REMOVED_SUGGESTIONS,
    RELATION_LABELS,
)

# Categories stockees dans la table `followers` (relations entrantes) ou
# `following` (relations sortantes, decidees par le compte).
INBOUND = {CAT_FOLLOWERS, CAT_PENDING_RECEIVED}
OUTBOUND = {
    CAT_FOLLOWING,
    CAT_PENDING_SENT,
    CAT_BLOCKED,
    CAT_RECENTLY_UNFOLLOWED,
    CAT_RECENTLY_FOLLOWED,
    CAT_CLOSE_FRIENDS,
    CAT_REMOVED_SUGGESTIONS,
}


@dataclass
class RelationEntry:
    username: str | None
    display_name: str | None
    profile_url: str | None
    timestamp_ms: int | None
    raw: Any
    source_file: str

    @property
    def date_label(self) -> str:
        iso = ms_to_iso(self.timestamp_ms)
        return iso or UNKNOWN_DATE_LABEL


def _username_from(href: str | None, value: str | None, title: str | None) -> str | None:
    if href:
        extracted = htmlkit.extract_instagram_username(href)
        if extracted:
            return extracted
    for candidate in (value, title):
        if candidate and isinstance(candidate, str):
            text = candidate.strip().lstrip("@")
            if text and " " not in text and len(text) <= 30:
                return text.lower()
    return None


def _entries_from_container(data: Any) -> list[dict]:
    """Recupere la liste d'entrées quelle que soit la clé racine utilisée."""
    if isinstance(data, list):
        return [e for e in data if isinstance(e, dict)]
    if isinstance(data, dict):
        for value in data.values():
            if isinstance(value, list) and value and isinstance(value[0], dict):
                return [e for e in value if isinstance(e, dict)]
        # certains exports imbriquent d'un niveau supplementaire
        for value in data.values():
            if isinstance(value, dict):
                nested = _entries_from_container(value)
                if nested:
                    return nested
    return []


def parse_relation_json(
    path: Path, data: Any = None, root: Path | None = None
) -> list[RelationEntry]:
    if data is None:
        try:
            data = json.loads(read_text_file(path))
        except (ValueError, OSError):
            return []
    data = deep_fix_mojibake(data)
    source_file = safe_relpath(path, root) if root else path.name

    out: list[RelationEntry] = []
    for entry in _entries_from_container(data):
        title = entry.get("title") if isinstance(entry.get("title"), str) else None
        items = entry.get("string_list_data")
        if isinstance(items, list) and items:
            for item in items:
                if not isinstance(item, dict):
                    continue
                href = item.get("href")
                value = item.get("value")
                username = _username_from(href, value, title)
                # timestamp = 0 signifie « absent » dans les exports Meta
                ts = normalize_epoch_ms(item.get("timestamp"))
                out.append(
                    RelationEntry(
                        username=username,
                        display_name=title or (value if value != username else None),
                        profile_url=href or (
                            f"https://www.instagram.com/{username}/" if username else None
                        ),
                        timestamp_ms=ts,
                        raw=entry,
                        source_file=source_file,
                    )
                )
            continue

        # Forme string_map_data (contacts, listes recentes)
        smap = entry.get("string_map_data")
        if isinstance(smap, dict):
            username = None
            display = None
            ts = None
            for key, val in smap.items():
                if not isinstance(val, dict):
                    continue
                key_l = key.lower()
                if "username" in key_l or "user name" in key_l:
                    username = _username_from(None, val.get("value"), None)
                elif "name" in key_l and display is None:
                    display = val.get("value")
                if val.get("timestamp") is not None and ts is None:
                    ts = normalize_epoch_ms(val.get("timestamp"))
            username = username or _username_from(None, title, None)
            out.append(
                RelationEntry(
                    username=username,
                    display_name=display or title,
                    profile_url=f"https://www.instagram.com/{username}/" if username else None,
                    timestamp_ms=ts,
                    raw=entry,
                    source_file=source_file,
                )
            )
            continue

        # Derniere chance : une entree ne contenant qu'un titre
        if title:
            username = _username_from(None, title, None)
            out.append(
                RelationEntry(
                    username=username,
                    display_name=title,
                    profile_url=f"https://www.instagram.com/{username}/" if username else None,
                    timestamp_ms=normalize_epoch_ms(entry.get("timestamp")),
                    raw=entry,
                    source_file=source_file,
                )
            )
    return out


def parse_relation_html(path: Path, root: Path | None = None) -> list[RelationEntry]:
    """Analyse une page HTML d'abonnés/abonnements (liens profil + date voisine)."""
    try:
        html = read_text_file(path)
    except OSError:
        return []
    tree = htmlkit.parse_html(html)
    source_file = safe_relpath(path, root) if root else path.name

    out: list[RelationEntry] = []
    seen: set[tuple[str | None, int | None]] = set()
    for anchor in tree.find_all("a"):
        href = anchor.attrs.get("href", "")
        username = htmlkit.extract_instagram_username(href)
        if not username:
            continue
        # On remonte au bloc parent pour chercher une date associee
        block = anchor.parent
        hops = 0
        timestamp_ms = None
        while block is not None and hops < 4:
            lines = block.text_lines()
            for line in reversed(lines):
                found = htmlkit.find_date_in_text(line)
                if found:
                    from ..utils import iso_to_ms

                    timestamp_ms = iso_to_ms(found[1])
                    break
            if timestamp_ms is not None:
                break
            block = block.parent
            hops += 1
        key = (username, timestamp_ms)
        if key in seen:
            continue
        seen.add(key)
        label = anchor.all_text().strip() or username
        out.append(
            RelationEntry(
                username=username,
                display_name=label if label != username else None,
                profile_url=href,
                timestamp_ms=timestamp_ms,
                raw={"href": href, "text": label, "source": "html"},
                source_file=source_file,
            )
        )
    return out


def create_snapshot(
    case: Case,
    source_id: int,
    kind: str,
    label: str | None = None,
    captured_at: str | None = None,
) -> int:
    return dbmod.insert(
        case.conn,
        "relationship_snapshots",
        {
            "case_id": case.case_id,
            "source_id": source_id,
            "kind": kind,
            "label": label or RELATION_LABELS.get(kind, kind),
            "captured_at": captured_at,
            "imported_at": utcnow_iso(),
            "entry_count": 0,
        },
    )


def store_entries(
    case: Case,
    snapshot_id: int,
    kind: str,
    entries: Iterable[RelationEntry],
) -> int:
    table = "followers" if kind in INBOUND else "following"
    case_id = case.case_id
    count = 0
    for entry in entries:
        dbmod.insert(
            case.conn,
            table,
            {
                "case_id": case_id,
                "snapshot_id": snapshot_id,
                "username": entry.username,
                "display_name": entry.display_name,
                "profile_url": entry.profile_url,
                "timestamp_utc": ms_to_iso(entry.timestamp_ms),
                "timestamp_ms": entry.timestamp_ms,
                "status": kind,
                "source_file": entry.source_file,
                "original_raw": dbmod.json_dumps(entry.raw),
            },
        )
        if entry.username or entry.display_name:
            upsert_person(
                case,
                username=entry.username,
                display_name=entry.display_name,
                profile_url=entry.profile_url,
                first_seen_at=ms_to_iso(entry.timestamp_ms),
            )
        count += 1
    case.conn.execute(
        "UPDATE relationship_snapshots SET entry_count = ? WHERE id = ?", (count, snapshot_id)
    )
    return count
