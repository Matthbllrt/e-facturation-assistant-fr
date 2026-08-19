"""Reconstruction des conversations (section 4).

Contraintes absolues :
  * la donnée brute d'origine est conservée intégralement (colonne original_raw) ;
  * aucun message n'est inventé : ce qui est absent reste absent, et les
    indices d'absence sont écrits dans la table `gaps`, jamais dans `messages`.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..utils import (
    deep_fix_mojibake,
    iso_to_ms,
    ms_to_iso,
    normalize_epoch_ms,
    read_text_file,
    safe_relpath,
    sha256_text,
    truncate,
)
from . import htmlkit

MEDIA_KEYS = ("photos", "videos", "audio_files", "gifs", "files", "stickers", "clips")


@dataclass
class ParsedMessage:
    sender: str | None
    timestamp_ms: int | None
    content: str | None
    msg_type: str
    media_paths: list[str] = field(default_factory=list)
    reactions: list[dict] = field(default_factory=list)
    share: dict | None = None
    raw: Any = None
    is_unsent: bool = False


@dataclass
class ParsedThread:
    external_id: str | None
    title: str | None
    participants: list[str]
    messages: list[ParsedMessage]
    raw_meta: dict
    source_file: str
    is_group: bool = False


def _as_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def _extract_media(message: dict) -> list[str]:
    paths: list[str] = []
    for key in MEDIA_KEYS:
        items = message.get(key)
        if not isinstance(items, list):
            continue
        for item in items:
            if isinstance(item, dict):
                uri = item.get("uri") or item.get("path")
                if uri:
                    paths.append(str(uri))
            elif isinstance(item, str):
                paths.append(item)
    return paths


def _message_type(message: dict, media: list[str], content: str | None) -> str:
    if message.get("is_unsent"):
        return "unsent"
    if message.get("call_duration") is not None or "call" in str(message.get("type", "")).lower():
        return "call"
    if message.get("share"):
        return "share"
    if media:
        return "media"
    if content:
        return "text"
    if message.get("reactions"):
        return "reaction_only"
    return "system"


def parse_message_json(path: Path, data: Any = None, root: Path | None = None) -> ParsedThread | None:
    """Analyse un message_N.json d'export Instagram."""
    if data is None:
        try:
            data = json.loads(read_text_file(path))
        except (ValueError, OSError):
            return None
    if not isinstance(data, dict) or not isinstance(data.get("messages"), list):
        return None

    data = deep_fix_mojibake(data)
    participants: list[str] = []
    for entry in data.get("participants") or []:
        if isinstance(entry, dict):
            name = entry.get("name") or entry.get("username")
        else:
            name = str(entry)
        if name:
            participants.append(str(name))

    messages: list[ParsedMessage] = []
    for raw_message in data["messages"]:
        if not isinstance(raw_message, dict):
            continue
        content = raw_message.get("content")
        if content is None:
            content = raw_message.get("text")
        media = _extract_media(raw_message)
        reactions = raw_message.get("reactions") if isinstance(raw_message.get("reactions"), list) else []
        share = raw_message.get("share") if isinstance(raw_message.get("share"), dict) else None
        ts = normalize_epoch_ms(
            raw_message.get("timestamp_ms")
            if raw_message.get("timestamp_ms") is not None
            else raw_message.get("timestamp")
        )
        messages.append(
            ParsedMessage(
                sender=raw_message.get("sender_name") or raw_message.get("sender"),
                timestamp_ms=ts,
                content=_as_text(content),
                msg_type=_message_type(raw_message, media, _as_text(content)),
                media_paths=media,
                reactions=reactions or [],
                share=share,
                raw=raw_message,
                is_unsent=bool(raw_message.get("is_unsent")),
            )
        )

    meta = {k: v for k, v in data.items() if k not in {"messages", "participants"}}
    external_id = data.get("thread_path") or meta.get("thread_path")
    if not external_id:
        parent = path.parent.name
        external_id = f"inbox/{parent}" if parent else None

    return ParsedThread(
        external_id=str(external_id) if external_id else None,
        title=data.get("title"),
        participants=participants,
        messages=messages,
        raw_meta=meta,
        source_file=safe_relpath(path, root) if root else path.name,
        is_group=len(participants) > 2,
    )


def parse_message_html(path: Path, root: Path | None = None) -> ParsedThread | None:
    """Analyse un message_N.html d'export Instagram (structure détectée par forme)."""
    try:
        html = read_text_file(path)
    except OSError:
        return None
    tree = htmlkit.parse_html(html)

    title = None
    for tag in ("title", "h1", "h2"):
        nodes = tree.find_all(tag)
        if nodes:
            candidate = nodes[0].all_text().strip()
            if candidate:
                title = candidate
                break

    # Bloc message = div dont la derniere ligne de texte est une date analysable.
    candidates: list[tuple[htmlkit.Node, list[str]]] = []
    for node in tree.iter_all():
        if node.tag not in {"div", "li", "article", "section", "table", "tr"}:
            continue
        lines = node.text_lines()
        if len(lines) < 2:
            continue
        if htmlkit.looks_like_date(lines[-1]):
            candidates.append((node, lines))

    # Ne garder que les blocs les plus internes (un parent contient ses enfants).
    inner: list[tuple[htmlkit.Node, list[str]]] = []
    candidate_nodes = {id(node) for node, _ in candidates}
    for node, lines in candidates:
        has_inner_candidate = any(
            id(child) in candidate_nodes and child is not node
            for child in node.iter_all()
        )
        if not has_inner_candidate:
            inner.append((node, lines))

    messages: list[ParsedMessage] = []
    participants_seen: list[str] = []
    for node, lines in inner:
        sender = lines[0].strip()
        date_raw = lines[-1]
        found = htmlkit.find_date_in_text(date_raw)
        iso = found[1] if found else None
        body_lines = [line for line in lines[1:-1] if line.strip()]
        media = htmlkit.collect_media(node)
        links = [href for href in htmlkit.collect_links(node) if href]
        content = "\n".join(body_lines) if body_lines else None
        msg_type = "media" if media and not content else ("text" if content else "system")
        if links and not media and not content:
            msg_type = "share"
        if sender and sender not in participants_seen:
            participants_seen.append(sender)
        messages.append(
            ParsedMessage(
                sender=sender or None,
                timestamp_ms=iso_to_ms(iso),
                content=content,
                msg_type=msg_type,
                media_paths=media,
                reactions=[],
                share={"links": links} if links else None,
                raw={
                    "html_lines": lines,
                    "media": media,
                    "links": links,
                    "source": "html",
                },
            )
        )

    if not messages:
        return None

    parent = path.parent.name
    return ParsedThread(
        external_id=f"inbox/{parent}" if parent else None,
        title=title or (participants_seen[0] if participants_seen else None),
        participants=participants_seen,
        messages=messages,
        raw_meta={"format": "html", "file": path.name},
        source_file=safe_relpath(path, root) if root else path.name,
        is_group=len(participants_seen) > 2,
    )


def group_key_for(thread: ParsedThread) -> str:
    if thread.external_id:
        return thread.external_id.strip().lower().replace("\\", "/")
    return f"title:{(thread.title or 'sans-titre').strip().lower()}"


def dedup_key_for(group: str, message: ParsedMessage) -> str:
    payload = "|".join(
        [
            group,
            (message.sender or "").strip().lower(),
            str(message.timestamp_ms or ""),
            (message.content or "").strip(),
            ",".join(sorted(message.media_paths)),
        ]
    )
    return sha256_text(payload)


def store_thread(
    case: Case,
    source_id: int,
    thread: ParsedThread,
    owner_names: set[str],
    file_id: int | None = None,
) -> tuple[int, int]:
    """Ecrit une conversation et ses messages. Retourne (conversation_id, nb messages)."""
    case_id = case.case_id
    group = group_key_for(thread)

    existing = dbmod.query_one(
        case.conn,
        "SELECT id, source_files_json, message_count FROM conversations "
        "WHERE case_id = ? AND source_id = ? AND group_key = ?",
        (case_id, source_id, group),
    )
    if existing:
        conversation_id = int(existing["id"])
        files_list = dbmod.json_loads(existing["source_files_json"], []) or []
        if thread.source_file not in files_list:
            files_list.append(thread.source_file)
        case.conn.execute(
            "UPDATE conversations SET source_files_json = ? WHERE id = ?",
            (dbmod.json_dumps(files_list), conversation_id),
        )
    else:
        conversation_id = dbmod.insert(
            case.conn,
            "conversations",
            {
                "case_id": case_id,
                "source_id": source_id,
                "external_id": thread.external_id,
                "group_key": group,
                "title": thread.title,
                "participants_json": dbmod.json_dumps(thread.participants),
                "is_group": 1 if thread.is_group else 0,
                "message_count": 0,
                "source_files_json": dbmod.json_dumps([thread.source_file]),
                "raw_meta_json": dbmod.json_dumps(thread.raw_meta),
            },
        )

    inserted = 0
    for message in thread.messages:
        key = dedup_key_for(group, message)
        already = dbmod.query_one(
            case.conn,
            "SELECT id FROM messages WHERE case_id = ? AND source_id = ? AND dedup_key = ?",
            (case_id, source_id, key),
        )
        if already:
            continue
        sender_norm = (message.sender or "").strip().lower()
        direction = "sent" if sender_norm and sender_norm in owner_names else (
            "received" if message.sender else "unknown"
        )
        dbmod.insert(
            case.conn,
            "messages",
            {
                "case_id": case_id,
                "conversation_id": conversation_id,
                "source_id": source_id,
                "sender": message.sender,
                "direction": direction,
                "timestamp_utc": ms_to_iso(message.timestamp_ms),
                "timestamp_ms": message.timestamp_ms,
                "content": message.content,
                "msg_type": message.msg_type,
                "media_path": message.media_paths[0] if message.media_paths else None,
                "reactions_json": dbmod.json_dumps(message.reactions) if message.reactions else None,
                "share_json": dbmod.json_dumps(message.share) if message.share else None,
                "file_id": file_id,
                "source_file": thread.source_file,
                "original_raw": dbmod.json_dumps(message.raw),
                "dedup_key": key,
            },
        )
        inserted += 1

    stats = dbmod.query_one(
        case.conn,
        "SELECT COUNT(*) AS n, MIN(timestamp_ms) AS mn, MAX(timestamp_ms) AS mx "
        "FROM messages WHERE conversation_id = ?",
        (conversation_id,),
    ) or {}
    case.conn.execute(
        "UPDATE conversations SET message_count = ?, first_message_at = ?, last_message_at = ? "
        "WHERE id = ?",
        (
            stats.get("n") or 0,
            ms_to_iso(stats.get("mn")),
            ms_to_iso(stats.get("mx")),
            conversation_id,
        ),
    )
    # Participants complementaires observes dans les messages
    if not thread.participants:
        senders = dbmod.query_all(
            case.conn,
            "SELECT DISTINCT sender FROM messages WHERE conversation_id = ? AND sender IS NOT NULL",
            (conversation_id,),
        )
        names = [row["sender"] for row in senders]
        case.conn.execute(
            "UPDATE conversations SET participants_json = ? WHERE id = ?",
            (dbmod.json_dumps(names), conversation_id),
        )
    return conversation_id, inserted


def summarize_thread(thread: ParsedThread) -> str:
    return (
        f"{thread.title or thread.external_id or 'conversation'} — "
        f"{len(thread.messages)} message(s), "
        f"{len(thread.participants)} participant(s) : "
        f"{truncate(', '.join(thread.participants), 80)}"
    )
