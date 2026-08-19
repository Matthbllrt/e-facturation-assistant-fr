"""Fusion des personnes rencontrees dans les differentes sources.

SQLite considere deux NULL comme distincts dans une contrainte UNIQUE : on ne
peut donc pas se reposer sur « INSERT OR IGNORE » quand le nom d'utilisateur ou
le nom affiche est absent. Ce module fait la fusion explicitement et complete
les champs manquants au fil des imports, sans jamais ecraser une valeur connue.
"""
from __future__ import annotations

from . import db as dbmod
from .cases import Case
from .utils import utcnow_iso


def upsert_person(
    case: Case,
    *,
    username: str | None = None,
    display_name: str | None = None,
    profile_url: str | None = None,
    first_seen_at: str | None = None,
    is_owner: bool = False,
    notes: str | None = None,
) -> int | None:
    username = (username or "").strip().lower() or None
    display_name = (display_name or "").strip() or None
    if not (username or display_name):
        return None

    case_id = case.case_id
    row = None
    if username:
        row = dbmod.query_one(
            case.conn,
            "SELECT * FROM people WHERE case_id = ? AND username = ?",
            (case_id, username),
        )
    if row is None and display_name:
        # Rapprochement par nom affiche : c'est la seule identite disponible
        # pour les participants d'une conversation (les exports Instagram ne
        # donnent pas leur nom d'utilisateur). Deux personnes portant
        # exactement le meme nom affiche seraient fusionnees ici ; cette table
        # sert d'index de navigation, jamais de source de preuve.
        row = dbmod.query_one(
            case.conn,
            "SELECT * FROM people WHERE case_id = ? AND display_name = ? "
            "ORDER BY is_owner DESC, id LIMIT 1",
            (case_id, display_name),
        )

    if row is None:
        return dbmod.insert(
            case.conn,
            "people",
            {
                "case_id": case_id,
                "username": username,
                "display_name": display_name,
                "profile_url": profile_url,
                "is_owner": 1 if is_owner else 0,
                "first_seen_at": first_seen_at or utcnow_iso(),
                "notes": notes,
            },
        )

    person_id = int(row["id"])
    updates: dict[str, object] = {}
    if username and not row["username"]:
        updates["username"] = username
    if display_name and not row["display_name"]:
        updates["display_name"] = display_name
    if profile_url and not row["profile_url"]:
        updates["profile_url"] = profile_url
    if is_owner and not row["is_owner"]:
        updates["is_owner"] = 1
    if first_seen_at and (not row["first_seen_at"] or first_seen_at < row["first_seen_at"]):
        updates["first_seen_at"] = first_seen_at
    if notes and not row["notes"]:
        updates["notes"] = notes
    if updates:
        assignments = ", ".join(f"{k} = ?" for k in updates)
        case.conn.execute(
            f"UPDATE people SET {assignments} WHERE id = ?",
            (*updates.values(), person_id),
        )
    return person_id


def list_people(case: Case, query: str | None = None, limit: int = 1000) -> list[dict]:
    sql = (
        "SELECT p.*, "
        "  (SELECT COUNT(*) FROM messages m WHERE m.case_id = p.case_id "
        "     AND LOWER(m.sender) = LOWER(IFNULL(p.display_name, p.username))) AS message_count "
        "FROM people p WHERE p.case_id = ?"
    )
    params: list[object] = [case.case_id]
    if query:
        sql += " AND (p.username LIKE ? OR p.display_name LIKE ?)"
        params.extend([f"%{query}%", f"%{query}%"])
    sql += " ORDER BY p.is_owner DESC, message_count DESC, IFNULL(p.username, p.display_name) LIMIT ?"
    params.append(limit)
    return dbmod.query_all(case.conn, sql, params)
