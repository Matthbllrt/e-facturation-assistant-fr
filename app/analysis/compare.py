"""Comparaison de plusieurs exports (section 8).

La comparaison ne créé jamais de date. Quand un élément apparait entre deux
exports, la formulation retenue est celle imposee par le cahier des charges :
« Present dans l'export du <B> et absent de l'export du <A> : ajout intervenu
entre ces deux dates. »
"""
from __future__ import annotations

from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..config import CONFIDENCE_CONFIRMED, CONFIDENCE_PROBABLE, UNKNOWN_DATE_LABEL
from ..utils import truncate, utcnow_iso


def _source_info(case: Case, source_id: int) -> dict[str, Any]:
    row = dbmod.query_one(
        case.conn,
        "SELECT id, label, declared_date, imported_at, kind FROM sources WHERE id = ? AND case_id = ?",
        (source_id, case.case_id),
    )
    if not row:
        raise ValueError(f"Source introuvable : {source_id}")
    row["date_label"] = row["declared_date"] or f"import du {row['imported_at'][:10]}"
    return row


def _relations(case: Case, source_id: int, kind: str) -> dict[str, dict]:
    table = "followers" if kind in {"followers", "pending_received"} else "following"
    rows = dbmod.query_all(
        case.conn,
        f"SELECT r.username, r.display_name, r.profile_url, r.timestamp_utc, r.source_file "
        f"FROM {table} r JOIN relationship_snapshots s ON s.id = r.snapshot_id "
        f"WHERE r.case_id = ? AND s.source_id = ? AND r.status = ?",
        (case.case_id, source_id, kind),
    )
    out: dict[str, dict] = {}
    for row in rows:
        key = (row["username"] or row["display_name"] or "").lower()
        if not key:
            continue
        out[key] = row
    return out


def _conversations(case: Case, source_id: int) -> dict[str, dict]:
    rows = dbmod.query_all(
        case.conn,
        "SELECT id, group_key, title, message_count, first_message_at, last_message_at "
        "FROM conversations WHERE case_id = ? AND source_id = ?",
        (case.case_id, source_id),
    )
    return {row["group_key"]: row for row in rows if row["group_key"]}


def _message_keys(case: Case, source_id: int) -> dict[str, dict]:
    rows = dbmod.query_all(
        case.conn,
        "SELECT m.dedup_key, m.sender, m.timestamp_utc, m.content, m.msg_type, c.title, c.group_key "
        "FROM messages m JOIN conversations c ON c.id = m.conversation_id "
        "WHERE m.case_id = ? AND m.source_id = ?",
        (case.case_id, source_id),
    )
    return {row["dedup_key"]: row for row in rows if row["dedup_key"]}


def _media_keys(case: Case, source_id: int) -> dict[str, dict]:
    rows = dbmod.query_all(
        case.conn,
        "SELECT rel_path, kind, taken_at, context FROM media WHERE case_id = ? AND source_id = ?",
        (case.case_id, source_id),
    )
    out: dict[str, dict] = {}
    for row in rows:
        path = str(row["rel_path"] or "")
        key = path.split("/")[-1].lower() if path else ""
        if key:
            out[key] = row
    return out


def _phrase_added(label_a: str, label_b: str) -> str:
    return (
        f"Present dans l'export « {label_b} » et absent de l'export « {label_a} » : "
        "ajout intervenu entre ces deux dates."
    )


def _phrase_removed(label_a: str, label_b: str) -> str:
    return (
        f"Present dans l'export « {label_a} » et absent de l'export « {label_b} » : "
        "disparition intervenue entre ces deux dates."
    )


RELATION_KINDS = (
    ("followers", "Abonnes"),
    ("following", "Abonnements"),
    ("pending_sent", "Demandes envoyees"),
    ("pending_received", "Demandes recues"),
    ("blocked", "Comptes bloques"),
    ("close_friends", "Amis proches"),
)


def compare_sources(case: Case, source_a: int, source_b: int, persist: bool = True) -> dict[str, Any]:
    """Compare deux exports et retourne un rapport de différences."""
    info_a = _source_info(case, source_a)
    info_b = _source_info(case, source_b)
    label_a = info_a["label"]
    label_b = info_b["label"]
    added_phrase = _phrase_added(info_a["date_label"], info_b["date_label"])
    removed_phrase = _phrase_removed(info_a["date_label"], info_b["date_label"])

    result: dict[str, Any] = {
        "source_a": {"id": source_a, "label": label_a, "date": info_a["declared_date"], "imported_at": info_a["imported_at"]},
        "source_b": {"id": source_b, "label": label_b, "date": info_b["declared_date"], "imported_at": info_b["imported_at"]},
        "created_at": utcnow_iso(),
        "window": {
            "start": info_a["declared_date"] or info_a["imported_at"],
            "end": info_b["declared_date"] or info_b["imported_at"],
            "start_label": info_a["date_label"],
            "end_label": info_b["date_label"],
        },
        "relations": {},
        "conversations": {},
        "messages": {},
        "media": {},
        "notes": [],
    }

    if not info_a["declared_date"] or not info_b["declared_date"]:
        result["notes"].append(
            "Au moins un des deux exports n'a pas de date déclarée : la fenêtre "
            "temporelle utilisé alors la date d'import dans l'outil, qui est "
            "postérieure a la date reelle de génération de l'export."
        )

    for kind, label in RELATION_KINDS:
        rel_a = _relations(case, source_a, kind)
        rel_b = _relations(case, source_b, kind)
        if not rel_a and not rel_b:
            continue
        added = sorted(set(rel_b) - set(rel_a))
        removed = sorted(set(rel_a) - set(rel_b))
        result["relations"][kind] = {
            "label": label,
            "count_a": len(rel_a),
            "count_b": len(rel_b),
            "added": [
                {
                    "username": name,
                    "profile_url": rel_b[name].get("profile_url"),
                    "date_in_source": rel_b[name].get("timestamp_utc") or UNKNOWN_DATE_LABEL,
                    "statement": added_phrase,
                    "source_file": rel_b[name].get("source_file"),
                }
                for name in added
            ],
            "removed": [
                {
                    "username": name,
                    "profile_url": rel_a[name].get("profile_url"),
                    "date_in_source": rel_a[name].get("timestamp_utc") or UNKNOWN_DATE_LABEL,
                    "statement": removed_phrase,
                    "source_file": rel_a[name].get("source_file"),
                }
                for name in removed
            ],
        }

    conv_a = _conversations(case, source_a)
    conv_b = _conversations(case, source_b)
    result["conversations"] = {
        "count_a": len(conv_a),
        "count_b": len(conv_b),
        "added": [
            {
                "group_key": key,
                "title": conv_b[key]["title"],
                "message_count": conv_b[key]["message_count"],
                "statement": added_phrase,
            }
            for key in sorted(set(conv_b) - set(conv_a))
        ],
        "removed": [
            {
                "group_key": key,
                "title": conv_a[key]["title"],
                "message_count": conv_a[key]["message_count"],
                "statement": removed_phrase,
            }
            for key in sorted(set(conv_a) - set(conv_b))
        ],
        "message_count_changes": [
            {
                "group_key": key,
                "title": conv_a[key]["title"],
                "count_a": conv_a[key]["message_count"],
                "count_b": conv_b[key]["message_count"],
                "delta": conv_b[key]["message_count"] - conv_a[key]["message_count"],
            }
            for key in sorted(set(conv_a) & set(conv_b))
            if conv_a[key]["message_count"] != conv_b[key]["message_count"]
        ],
    }

    msg_a = _message_keys(case, source_a)
    msg_b = _message_keys(case, source_b)
    only_b = [msg_b[k] for k in msg_b.keys() - msg_a.keys()]
    only_a = [msg_a[k] for k in msg_a.keys() - msg_b.keys()]
    result["messages"] = {
        "count_a": len(msg_a),
        "count_b": len(msg_b),
        "added": [
            {
                "conversation": row["title"],
                "sender": row["sender"],
                "timestamp": row["timestamp_utc"] or UNKNOWN_DATE_LABEL,
                "excerpt": truncate(row["content"], 160),
                "type": row["msg_type"],
                "statement": added_phrase,
            }
            for row in sorted(only_b, key=lambda r: r["timestamp_utc"] or "")[:2000]
        ],
        "removed": [
            {
                "conversation": row["title"],
                "sender": row["sender"],
                "timestamp": row["timestamp_utc"] or UNKNOWN_DATE_LABEL,
                "excerpt": truncate(row["content"], 160),
                "type": row["msg_type"],
                "statement": removed_phrase,
            }
            for row in sorted(only_a, key=lambda r: r["timestamp_utc"] or "")[:2000]
        ],
    }

    media_a = _media_keys(case, source_a)
    media_b = _media_keys(case, source_b)
    result["media"] = {
        "count_a": len(media_a),
        "count_b": len(media_b),
        "added": [
            {"name": key, "path": media_b[key]["rel_path"], "statement": added_phrase}
            for key in sorted(set(media_b) - set(media_a))[:2000]
        ],
        "removed": [
            {"name": key, "path": media_a[key]["rel_path"], "statement": removed_phrase}
            for key in sorted(set(media_a) - set(media_b))[:2000]
        ],
    }

    result["summary"] = {
        "relations_added": sum(len(v["added"]) for v in result["relations"].values()),
        "relations_removed": sum(len(v["removed"]) for v in result["relations"].values()),
        "conversations_added": len(result["conversations"]["added"]),
        "conversations_removed": len(result["conversations"]["removed"]),
        "messages_added": len(result["messages"]["added"]),
        "messages_removed": len(result["messages"]["removed"]),
        "media_added": len(result["media"]["added"]),
        "media_removed": len(result["media"]["removed"]),
    }

    if persist:
        dbmod.insert(
            case.conn,
            "comparisons",
            {
                "case_id": case.case_id,
                "source_a": source_a,
                "source_b": source_b,
                "created_at": result["created_at"],
                "result_json": dbmod.json_dumps(result),
            },
        )
        _write_diff_events(case, result)
        _write_diff_gaps(case, result)
        case.conn.commit()
        case.audit("comparaison", f"{label_a} vs {label_b}")
    return result


def _write_diff_events(case: Case, result: dict[str, Any]) -> int:
    """Ecrit les différences comme événements bornes entre deux dates."""
    window = result["window"]
    count = 0
    for kind, block in result["relations"].items():
        for entry in block["added"]:
            dbmod.insert(
                case.conn,
                "events",
                {
                    "case_id": case.case_id,
                    "occurred_at": None,
                    "occurred_ms": None,
                    "period_start": window["start"],
                    "period_end": window["end"],
                    "kind": "diff_ajout",
                    "title": f"{block['label']} : @{entry['username']} apparait",
                    "detail": entry["statement"],
                    "confidence": CONFIDENCE_CONFIRMED,
                    "source_ref": f"comparaison {result['source_a']['label']} / {result['source_b']['label']}",
                },
            )
            count += 1
        for entry in block["removed"]:
            dbmod.insert(
                case.conn,
                "events",
                {
                    "case_id": case.case_id,
                    "occurred_at": None,
                    "occurred_ms": None,
                    "period_start": window["start"],
                    "period_end": window["end"],
                    "kind": "diff_disparition",
                    "title": f"{block['label']} : @{entry['username']} disparait",
                    "detail": entry["statement"],
                    "confidence": CONFIDENCE_CONFIRMED,
                    "source_ref": f"comparaison {result['source_a']['label']} / {result['source_b']['label']}",
                },
            )
            count += 1
    return count


def _write_diff_gaps(case: Case, result: dict[str, Any]) -> int:
    """Une conversation ou un message présent dans A et absent de B est un
    élément potentiellement manquant, jamais une preuve de suppression."""
    from .gaps import _record_gap

    window = result["window"]
    label_a = result["source_a"]["label"]
    label_b = result["source_b"]["label"]
    count = 0

    for entry in result["conversations"]["removed"]:
        created = _record_gap(
            case,
            "conversation_absente_export_recent",
            CONFIDENCE_PROBABLE,
            f"Conversation « {entry['title'] or entry['group_key']} » ({entry['message_count']} message(s)) "
            f"présente dans l'export « {label_a} » et absente de l'export « {label_b} ». "
            "Suppression, archivage ou export partiel : la cause n'est pas établie par les données.",
            indicator=f"comparaison {label_a} / {label_b}",
            window_start=window["start"],
            window_end=window["end"],
            source_ref=f"comparaison {label_a} / {label_b}",
            raw=entry,
        )
        count += 1 if created else 0

    removed_messages = result["messages"]["removed"]
    if removed_messages:
        by_conversation: dict[str, int] = {}
        for row in removed_messages:
            by_conversation[row["conversation"] or "conversation inconnue"] = (
                by_conversation.get(row["conversation"] or "conversation inconnue", 0) + 1
            )
        for title, number in by_conversation.items():
            created = _record_gap(
                case,
                "messages_absents_export_recent",
                CONFIDENCE_PROBABLE,
                f"{number} message(s) du fil « {title} » présents dans l'export « {label_a} » "
                f"et absents de l'export « {label_b} ». Le contenu reste consultable dans "
                f"l'export « {label_a} » ; l'outil ne reconstitue rien.",
                indicator=f"comparaison {label_a} / {label_b}",
                window_start=window["start"],
                window_end=window["end"],
                source_ref=f"comparaison {label_a} / {label_b}",
                raw={"count": number, "conversation": title},
            )
            count += 1 if created else 0
    return count


def list_comparisons(case: Case) -> list[dict]:
    rows = dbmod.query_all(
        case.conn,
        "SELECT c.id, c.source_a, c.source_b, c.created_at, "
        "       a.label AS label_a, b.label AS label_b "
        "FROM comparisons c JOIN sources a ON a.id = c.source_a JOIN sources b ON b.id = c.source_b "
        "WHERE c.case_id = ? ORDER BY c.created_at DESC",
        (case.case_id,),
    )
    return rows


def get_comparison(case: Case, comparison_id: int) -> dict | None:
    row = dbmod.query_one(
        case.conn,
        "SELECT result_json FROM comparisons WHERE id = ? AND case_id = ?",
        (comparison_id, case.case_id),
    )
    return dbmod.json_loads(row["result_json"]) if row else None
