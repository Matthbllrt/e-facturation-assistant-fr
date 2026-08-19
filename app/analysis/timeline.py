"""Chronologie (section 12).

Chaque événement affiché sa SOURCE et son NIVEAU DE CONFIANCE. Les événements
dont la date exacte est inconnue mais qui sont bornes entre deux exports sont
regroupes a part, sous une fenêtre temporelle explicite : ils ne sont jamais
places arbitrairement a une date précise.
"""
from __future__ import annotations

from collections import OrderedDict
from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..config import CONFIDENCE_ORDER, UNKNOWN_DATE_LABEL

KIND_LABELS = {
    "message": "Conversation",
    "follow": "Abonné",
    "followers": "Nouvel abonné",
    "following": "Abonnement",
    "pending_sent": "Demande envoyée",
    "pending_received": "Demande reçue",
    "blocked": "Compte bloqué",
    "close_friends": "Ami proche",
    "recently_unfollowed": "Désabonnement",
    "recently_followed": "Abonnement récent",
    "removed_suggestions": "Suggestion masquée",
    "like": "J'aime",
    "comment": "Commentaire",
    "search": "Recherche",
    "login": "Connexion",
    "device": "Appareil",
    "post": "Publication",
    "story": "Story",
    "archived": "Contenu archivé",
    "profile": "Profil",
    "account_history": "Historique du compte",
    "media": "Média",
    "diff_ajout": "Ajout détecté entre deux exports",
    "diff_disparition": "Disparition détectée entre deux exports",
    "note": "Note",
    "public": "Collecte publique",
    "android": "Source Android",
    "contacts": "Contacts",
    "activite": "Activité",
}


def build(
    case: Case,
    *,
    date_from: str | None = None,
    date_to: str | None = None,
    kinds: list[str] | None = None,
    confidences: list[str] | None = None,
    limit: int = 5000,
) -> dict[str, Any]:
    """Construit la chronologie regroupee par jour."""
    sql = "SELECT * FROM events WHERE case_id = ?"
    params: list[Any] = [case.case_id]
    if date_from:
        sql += " AND (occurred_at IS NULL OR occurred_at >= ?)"
        params.append(date_from)
    if date_to:
        sql += " AND (occurred_at IS NULL OR occurred_at <= ?)"
        params.append(date_to)
    if kinds:
        sql += f" AND kind IN ({','.join('?' for _ in kinds)})"
        params.extend(kinds)
    if confidences:
        sql += f" AND confidence IN ({','.join('?' for _ in confidences)})"
        params.extend(confidences)
    sql += " ORDER BY occurred_ms IS NULL, occurred_ms ASC LIMIT ?"
    params.append(limit)

    rows = dbmod.query_all(case.conn, sql, params)

    days: "OrderedDict[str, list[dict]]" = OrderedDict()
    undated: list[dict] = []
    for row in rows:
        entry = {
            "id": row["id"],
            "time": (row["occurred_at"] or "")[11:16] or None,
            "date": (row["occurred_at"] or "")[:10] or None,
            "kind": row["kind"],
            "kind_label": KIND_LABELS.get(row["kind"], row["kind"]),
            "title": row["title"],
            "detail": row["detail"],
            "confidence": row["confidence"],
            "source": row["source_ref"] or "source non précisée",
            "ref_table": row["ref_table"],
            "ref_id": row["ref_id"],
            "period_start": row["period_start"],
            "period_end": row["period_end"],
        }
        if row["occurred_at"]:
            days.setdefault(entry["date"], []).append(entry)
        else:
            entry["window_label"] = (
                f"entre {(row['period_start'] or '?')[:10]} et {(row['period_end'] or '?')[:10]}"
                if row["period_start"] or row["period_end"]
                else UNKNOWN_DATE_LABEL
            )
            undated.append(entry)

    for entries in days.values():
        entries.sort(key=lambda e: (e["time"] or "", CONFIDENCE_ORDER.get(e["confidence"], 9)))

    # Les elements potentiellement manquants apparaissent aussi dans la
    # chronologie, explicitement etiquetes, jamais comme un contenu retrouve.
    gap_rows = dbmod.query_all(
        case.conn,
        "SELECT id, gap_type, confidence, description, window_start, window_end, source_ref "
        "FROM gaps WHERE case_id = ?",
        (case.case_id,),
    )
    for row in gap_rows:
        entry = {
            "id": row["id"],
            "kind": "gap",
            "kind_label": "ÉLÉMENT POTENTIELLEMENT MANQUANT",
            "title": "ÉLÉMENT POTENTIELLEMENT MANQUANT",
            "detail": row["description"],
            "confidence": row["confidence"],
            "source": row["source_ref"] or "source non précisée",
            "ref_table": "gaps",
            "ref_id": row["id"],
            "period_start": row["window_start"],
            "period_end": row["window_end"],
        }
        start = row["window_start"]
        if start and (not date_from or start >= date_from) and (not date_to or start <= date_to):
            entry["date"] = start[:10]
            entry["time"] = start[11:16]
            days.setdefault(entry["date"], []).append(entry)
        else:
            entry["window_label"] = (
                f"entre {(row['window_start'] or '?')[:10]} et {(row['window_end'] or '?')[:10]}"
                if row["window_start"] or row["window_end"]
                else UNKNOWN_DATE_LABEL
            )
            undated.append(entry)

    ordered_days = OrderedDict(sorted(days.items()))
    return {
        "days": [
            {"date": day, "events": sorted(entries, key=lambda e: e.get("time") or "")}
            for day, entries in ordered_days.items()
        ],
        "undated": undated,
        "total_events": sum(len(v) for v in ordered_days.values()) + len(undated),
        "legend": {
            "CONFIRMÉ": "Donnée présente telle quelle dans une source vérifiée.",
            "PROBABLE": "Plusieurs indices concordants, sans certitude.",
            "POSSIBLE": "Indice unique ou anomalie statistique.",
            "INCONNU": "Indice insuffisant pour conclure.",
        },
    }


def kinds_available(case: Case) -> list[dict]:
    rows = dbmod.query_all(
        case.conn,
        "SELECT kind, COUNT(*) AS n FROM events WHERE case_id = ? GROUP BY kind ORDER BY n DESC",
        (case.case_id,),
    )
    for row in rows:
        row["label"] = KIND_LABELS.get(row["kind"], row["kind"])
    return rows
