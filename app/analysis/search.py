"""Moteur de recherche global (section 11).

Indexe messages, personnes, événements, éléments de preuve, fichiers, médias,
éléments manquants et collectes publiques dans une table FTS5 locale. Si FTS5
n'est pas disponible sur l'installation SQLite, un repli LIKE est utilisé.
"""
from __future__ import annotations

import re
from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..utils import truncate

INDEXABLE_KINDS = (
    "message",
    "personne",
    "evenement",
    "preuve",
    "fichier",
    "media",
    "manquant",
    "public",
    "conversation",
)

_FTS_SPECIAL = re.compile(r'["*()^:]')


def _uses_fts(case: Case) -> bool:
    value = dbmod.scalar(case.conn, "SELECT value FROM meta WHERE key = 'fts5'")
    return value == "1"


def _clear(case: Case) -> None:
    case.conn.execute("DELETE FROM search_index")


def _add(
    case: Case,
    body: str | None,
    title: str | None,
    kind: str,
    ref_table: str,
    ref_id: int | None,
    person: str | None = None,
    ts: str | None = None,
    source_ref: str | None = None,
) -> None:
    if not (body or title):
        return
    case.conn.execute(
        "INSERT INTO search_index (body, title, kind, ref_table, ref_id, person, ts, source_ref) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (body or "", title or "", kind, ref_table, ref_id, person or "", ts or "", source_ref or ""),
    )


def reindex(case: Case) -> int:
    """Reconstruit l'index de recherche pour tout le dossier."""
    _clear(case)
    case_id = case.case_id
    total = 0

    for row in dbmod.query_all(
        case.conn,
        "SELECT m.id, m.content, m.sender, m.timestamp_utc, m.source_file, m.msg_type, "
        "       m.media_path, c.title AS conv_title FROM messages m "
        "JOIN conversations c ON c.id = m.conversation_id WHERE m.case_id = ?",
        (case_id,),
    ):
        body = " ".join(
            part for part in [row["content"], row["media_path"], row["msg_type"]] if part
        )
        _add(
            case,
            body,
            f"{row['sender'] or 'expéditeur inconnu'} — {row['conv_title'] or 'conversation'}",
            "message",
            "messages",
            row["id"],
            person=row["sender"],
            ts=row["timestamp_utc"],
            source_ref=row["source_file"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, title, participants_json, external_id FROM conversations WHERE case_id = ?",
        (case_id,),
    ):
        participants = dbmod.json_loads(row["participants_json"], []) or []
        _add(
            case,
            " ".join(str(p) for p in participants) + " " + (row["external_id"] or ""),
            row["title"] or "Conversation",
            "conversation",
            "conversations",
            row["id"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, username, display_name, profile_url, notes FROM people WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(
                str(v) for v in [row["username"], row["display_name"], row["profile_url"], row["notes"]] if v
            ),
            row["username"] or row["display_name"] or "personne",
            "personne",
            "people",
            row["id"],
            person=row["username"] or row["display_name"],
        )
        total += 1

    for table, kind in (("followers", "abonne"), ("following", "abonnement")):
        for row in dbmod.query_all(
            case.conn,
            f"SELECT id, username, display_name, status, timestamp_utc, source_file "
            f"FROM {table} WHERE case_id = ?",
            (case_id,),
        ):
            _add(
                case,
                " ".join(str(v) for v in [row["username"], row["display_name"], row["status"]] if v),
                f"{kind} : {row['username'] or row['display_name'] or 'inconnu'}",
                "personne",
                table,
                row["id"],
                person=row["username"],
                ts=row["timestamp_utc"],
                source_ref=row["source_file"],
            )
            total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, title, detail, kind, occurred_at, source_ref FROM events WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(str(v) for v in [row["title"], row["detail"], row["kind"]] if v),
            row["title"],
            "evenement",
            "events",
            row["id"],
            ts=row["occurred_at"],
            source_ref=row["source_ref"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, label, evidence_type, declared_origin, observations, extracted_text, declared_date "
        "FROM evidence WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(
                str(v)
                for v in [
                    row["evidence_type"],
                    row["declared_origin"],
                    row["observations"],
                    truncate(row["extracted_text"], 20000),
                ]
                if v
            ),
            row["label"] or "Element de preuve",
            "preuve",
            "evidence",
            row["id"],
            ts=row["declared_date"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, rel_path, original_name, category, sha256, file_mtime FROM files WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(
                str(v) for v in [row["rel_path"], row["original_name"], row["category"], row["sha256"]] if v
            ),
            row["original_name"] or row["rel_path"],
            "fichier",
            "files",
            row["id"],
            ts=row["file_mtime"],
            source_ref=row["rel_path"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, rel_path, caption, context, taken_at FROM media WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(str(v) for v in [row["rel_path"], row["caption"], row["context"]] if v),
            row["caption"] or row["rel_path"],
            "media",
            "media",
            row["id"],
            ts=row["taken_at"],
            source_ref=row["rel_path"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, description, gap_type, confidence, source_ref, window_start FROM gaps WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(str(v) for v in [row["description"], row["gap_type"], row["confidence"]] if v),
            "ÉLÉMENT POTENTIELLEMENT MANQUANT",
            "manquant",
            "gaps",
            row["id"],
            ts=row["window_start"],
            source_ref=row["source_ref"],
        )
        total += 1

    for row in dbmod.query_all(
        case.conn,
        "SELECT id, username, full_name, biography, external_url, url, collected_at "
        "FROM public_profile WHERE case_id = ?",
        (case_id,),
    ):
        _add(
            case,
            " ".join(
                str(v)
                for v in [row["username"], row["full_name"], row["biography"], row["external_url"]]
                if v
            ),
            f"Collecte publique {row['collected_at']}",
            "public",
            "public_profile",
            row["id"],
            ts=row["collected_at"],
            source_ref=row["url"],
        )
        total += 1

    case.conn.commit()
    return total


def _fts_query(text: str) -> str:
    cleaned = _FTS_SPECIAL.sub(" ", text).strip()
    tokens = [t for t in cleaned.split() if t]
    if not tokens:
        return ""
    return " ".join(f'"{token}"*' for token in tokens)


def search(
    case: Case,
    query: str,
    *,
    kinds: list[str] | None = None,
    person: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    source_ref: str | None = None,
    limit: int = 200,
    offset: int = 0,
) -> dict[str, Any]:
    """Recherche globale filtrable."""
    query = (query or "").strip()
    params: list[Any] = []
    where: list[str] = []

    if _uses_fts(case) and query:
        match = _fts_query(query)
        base = "SELECT rowid, body, title, kind, ref_table, ref_id, person, ts, source_ref FROM search_index"
        if match:
            base += " WHERE search_index MATCH ?"
            params.append(match)
        else:
            base += " WHERE 1=1"
    else:
        base = "SELECT rowid, body, title, kind, ref_table, ref_id, person, ts, source_ref FROM search_index WHERE 1=1"
        if query:
            where.append("(body LIKE ? OR title LIKE ?)")
            params.extend([f"%{query}%", f"%{query}%"])

    if kinds:
        marks = ",".join("?" for _ in kinds)
        where.append(f"kind IN ({marks})")
        params.extend(kinds)
    if person:
        where.append("person LIKE ?")
        params.append(f"%{person}%")
    if date_from:
        where.append("ts >= ? AND ts != ''")
        params.append(date_from)
    if date_to:
        where.append("ts <= ? AND ts != ''")
        params.append(date_to)
    if source_ref:
        where.append("source_ref LIKE ?")
        params.append(f"%{source_ref}%")

    sql = base
    for clause in where:
        sql += f" AND {clause}"
    sql += " ORDER BY ts DESC LIMIT ? OFFSET ?"
    params.extend([limit, offset])

    try:
        rows = dbmod.query_all(case.conn, sql, params)
    except Exception:
        # Repli si la syntaxe MATCH echoue (caracteres exotiques)
        safe_sql = (
            "SELECT rowid, body, title, kind, ref_table, ref_id, person, ts, source_ref "
            "FROM search_index WHERE body LIKE ? ORDER BY ts DESC LIMIT ?"
        )
        rows = dbmod.query_all(case.conn, safe_sql, [f"%{query}%", limit])

    results = []
    for row in rows:
        results.append(
            {
                "kind": row["kind"],
                "title": row["title"],
                "excerpt": _excerpt(row["body"], query),
                "ref_table": row["ref_table"],
                "ref_id": row["ref_id"],
                "person": row["person"] or None,
                "timestamp": row["ts"] or None,
                "source_ref": row["source_ref"] or None,
            }
        )
    return {"query": query, "count": len(results), "results": results}


def _excerpt(body: str, query: str, width: int = 220) -> str:
    if not body:
        return ""
    if query:
        lowered = body.lower()
        position = lowered.find(query.lower().split()[0]) if query.split() else -1
        if position >= 0:
            start = max(0, position - width // 3)
            return ("…" if start else "") + truncate(body[start : start + width], width)
    return truncate(body, width)
