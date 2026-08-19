"""Détection des éléments potentiellement manquants ou supprimés (section 6).

REGLE ABSOLUE : ce module ne reconstitué AUCUN contenu. Il signalé uniquement
des indices verifiables d'absence, avec un niveau de confiance explicite :
  CONFIRME  : la source elle-même atteste l'absence (ex. « is_unsent »: true,
              ou un media référencé par un message mais absent de l'archive) ;
  PROBABLE  : plusieurs indices structurels concordent (fichier d'une série
              numerotee manquant, media orphelin dans le dossier du fil) ;
  POSSIBLE  : anomalie statistique (long silence dans un fil très actif) ;
              un silence n'est jamais une preuve de suppression ;
  INCONNU   : indice insuffisant pour trancher.
"""
from __future__ import annotations

import re
import statistics
from pathlib import Path
from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..config import (
    CONFIDENCE_CONFIRMED,
    CONFIDENCE_POSSIBLE,
    CONFIDENCE_PROBABLE,
)
from ..utils import ms_to_iso, truncate, utcnow_iso

_NUMBERED_RE = re.compile(r"^(.*?)(\d+)(\.[a-z]+)$", re.I)

DAY_MS = 86_400_000


def _record_gap(
    case: Case,
    gap_type: str,
    confidence: str,
    description: str,
    *,
    conversation_id: int | None = None,
    indicator: str | None = None,
    window_start: str | None = None,
    window_end: str | None = None,
    source_ref: str | None = None,
    raw: Any = None,
) -> bool:
    existing = dbmod.query_one(
        case.conn,
        "SELECT id FROM gaps WHERE case_id = ? AND gap_type = ? AND description = ? "
        "AND IFNULL(conversation_id, -1) = IFNULL(?, -1)",
        (case.case_id, gap_type, description, conversation_id),
    )
    if existing:
        return False
    dbmod.insert(
        case.conn,
        "gaps",
        {
            "case_id": case.case_id,
            "conversation_id": conversation_id,
            "detected_at": utcnow_iso(),
            "gap_type": gap_type,
            "confidence": confidence,
            "description": description,
            "indicator": indicator,
            "window_start": window_start,
            "window_end": window_end,
            "source_ref": source_ref,
            "original_raw": dbmod.json_dumps(raw) if raw is not None else None,
        },
    )
    return True


def _detect_unsent(case: Case, source_id: int) -> int:
    """Messages marques comme retirés par la source elle-même."""
    rows = dbmod.query_all(
        case.conn,
        "SELECT m.id, m.conversation_id, m.sender, m.timestamp_utc, m.source_file, m.original_raw, "
        "       c.title FROM messages m JOIN conversations c ON c.id = m.conversation_id "
        "WHERE m.case_id = ? AND m.source_id = ? AND m.msg_type = 'unsent'",
        (case.case_id, source_id),
    )
    count = 0
    for row in rows:
        moment = row["timestamp_utc"] or "date inconnue"
        created = _record_gap(
            case,
            "message_retire",
            CONFIDENCE_CONFIRMED,
            f"Message retiré par son auteur — fil « {row['title'] or 'sans titre'} », "
            f"expéditeur {row['sender'] or 'inconnu'}, {moment}. "
            "Le contenu n'est pas présent dans l'export et n'est pas reconstituable.",
            conversation_id=row["conversation_id"],
            indicator="champ « is_unsent » = true dans le fichier source",
            window_start=row["timestamp_utc"],
            window_end=row["timestamp_utc"],
            source_ref=row["source_file"],
            raw=dbmod.json_loads(row["original_raw"]),
        )
        count += 1 if created else 0
    return count


def _detect_missing_media(case: Case, source_id: int) -> int:
    """Médias référencés par un message mais absents de l'archive."""
    rows = dbmod.query_all(
        case.conn,
        "SELECT m.id, m.conversation_id, m.media_path, m.timestamp_utc, m.sender, m.source_file, "
        "       c.title FROM messages m JOIN conversations c ON c.id = m.conversation_id "
        "WHERE m.case_id = ? AND m.source_id = ? AND m.media_path IS NOT NULL",
        (case.case_id, source_id),
    )
    if not rows:
        return 0
    known = {
        Path(r["rel_path"]).name.lower()
        for r in dbmod.query_all(
            case.conn, "SELECT rel_path FROM files WHERE case_id = ?", (case.case_id,)
        )
    }
    count = 0
    for row in rows:
        media = str(row["media_path"])
        if media.startswith("http"):
            continue
        if Path(media).name.lower() in known:
            continue
        created = _record_gap(
            case,
            "media_reference_absent",
            CONFIDENCE_CONFIRMED,
            f"Média référencé par un message mais absent des fichiers importés : « {media} » "
            f"(fil « {row['title'] or 'sans titre'} », {row['timestamp_utc'] or 'date inconnue'}).",
            conversation_id=row["conversation_id"],
            indicator="chemin présent dans le message, fichier introuvable dans la source",
            window_start=row["timestamp_utc"],
            window_end=row["timestamp_utc"],
            source_ref=row["source_file"],
            raw={"media_path": media},
        )
        count += 1 if created else 0
    return count


def _detect_orphan_media(case: Case, source_id: int) -> int:
    """Fichiers media présents dans un dossier de conversation sans message associe."""
    files = dbmod.query_all(
        case.conn,
        "SELECT id, rel_path FROM files WHERE case_id = ? AND source_id = ? AND category = 'media_file'",
        (case.case_id, source_id),
    )
    if not files:
        return 0
    referenced = {
        Path(str(r["media_path"])).name.lower()
        for r in dbmod.query_all(
            case.conn,
            "SELECT media_path FROM messages WHERE case_id = ? AND media_path IS NOT NULL",
            (case.case_id,),
        )
    }
    count = 0
    for row in files:
        rel = str(row["rel_path"])
        lower = rel.lower()
        if not any(marker in lower for marker in ("/inbox/", "message_requests", "archived_threads")):
            continue
        if Path(rel).name.lower() in referenced:
            continue
        created = _record_gap(
            case,
            "media_orphelin",
            CONFIDENCE_PROBABLE,
            f"Média présent dans un dossier de conversation sans message correspondant dans "
            f"l'export : « {rel} ». Indice d'un message supprimé ou non exporté.",
            indicator="fichier dans messages/inbox non référencé par un message",
            source_ref=rel,
            raw={"file": rel},
        )
        count += 1 if created else 0
    return count


def _detect_missing_numbered_files(case: Case, source_id: int) -> int:
    """Series message_1..N : détecté les numéros manquants."""
    rows = dbmod.query_all(
        case.conn,
        "SELECT rel_path FROM files WHERE case_id = ? AND source_id = ? "
        "AND (extension = '.json' OR extension = '.html')",
        (case.case_id, source_id),
    )
    series: dict[tuple[str, str, str], set[int]] = {}
    for row in rows:
        path = Path(str(row["rel_path"]))
        match = _NUMBERED_RE.match(path.name)
        if not match:
            continue
        prefix, number, ext = match.groups()
        if not prefix:
            continue
        series.setdefault((path.parent.as_posix(), prefix, ext), set()).add(int(number))

    count = 0
    for (folder, prefix, ext), numbers in series.items():
        if len(numbers) < 2:
            continue
        # Les exports Meta numerotent toujours a partir de 1 : une serie
        # commencant a 2 signale un fichier absent.
        expected = set(range(1, max(numbers) + 1))
        missing = sorted(expected - numbers)
        if not missing:
            continue
        created = _record_gap(
            case,
            "fichier_serie_manquant",
            CONFIDENCE_PROBABLE,
            f"Série de fichiers incomplète dans « {folder} » : "
            f"{prefix}[{', '.join(str(n) for n in missing)}]{ext} absent(s) "
            f"alors que {prefix}{min(numbers)}{ext} à {prefix}{max(numbers)}{ext} sont présents. "
            "Le contenu de ce ou ces fichiers n'est pas reconstituable.",
            indicator="numérotation discontinue dans une série d'export",
            source_ref=folder,
            raw={"folder": folder, "missing": missing, "present": sorted(numbers)},
        )
        count += 1 if created else 0
    return count


def _detect_reaction_without_content(case: Case, source_id: int) -> int:
    """Réaction présente alors que le message cible est vide de contenu."""
    rows = dbmod.query_all(
        case.conn,
        "SELECT m.id, m.conversation_id, m.sender, m.timestamp_utc, m.reactions_json, "
        "       m.source_file, c.title FROM messages m JOIN conversations c ON c.id = m.conversation_id "
        "WHERE m.case_id = ? AND m.source_id = ? AND m.reactions_json IS NOT NULL "
        "AND (m.content IS NULL OR m.content = '') AND m.media_path IS NULL",
        (case.case_id, source_id),
    )
    count = 0
    for row in rows:
        reactions = dbmod.json_loads(row["reactions_json"], []) or []
        actors = ", ".join(str(r.get("actor")) for r in reactions if isinstance(r, dict) and r.get("actor"))
        created = _record_gap(
            case,
            "reaction_sans_cible",
            CONFIDENCE_PROBABLE,
            f"Réaction enregistrée sur un message dont le contenu est absent de l'export "
            f"(fil « {row['title'] or 'sans titre'} », {row['timestamp_utc'] or 'date inconnue'}, "
            f"réaction de : {actors or 'auteur inconnu'}). Le contenu d'origine n'est pas reconstituable.",
            conversation_id=row["conversation_id"],
            indicator="champ « réactions » non vide, contenu et media absents",
            window_start=row["timestamp_utc"],
            window_end=row["timestamp_utc"],
            source_ref=row["source_file"],
            raw=reactions,
        )
        count += 1 if created else 0
    return count


def _detect_temporal_gaps(case: Case, source_id: int, min_messages: int = 30) -> int:
    """Longues interruptions dans un fil habituellement très actif.

    Signale en POSSIBLE uniquement : un silence n'est pas une preuve de
    suppression. La description le précise explicitement.
    """
    conversations = dbmod.query_all(
        case.conn,
        "SELECT id, title, message_count FROM conversations "
        "WHERE case_id = ? AND source_id = ? AND message_count >= ?",
        (case.case_id, source_id, min_messages),
    )
    count = 0
    for conv in conversations:
        stamps = [
            int(r["timestamp_ms"])
            for r in dbmod.query_all(
                case.conn,
                "SELECT timestamp_ms FROM messages WHERE conversation_id = ? "
                "AND timestamp_ms IS NOT NULL ORDER BY timestamp_ms",
                (conv["id"],),
            )
        ]
        if len(stamps) < min_messages:
            continue
        deltas = [b - a for a, b in zip(stamps, stamps[1:]) if b > a]
        if len(deltas) < 10:
            continue
        median = statistics.median(deltas)
        if median <= 0:
            continue
        for previous, current in zip(stamps, stamps[1:]):
            delta = current - previous
            if delta < 7 * DAY_MS or delta < median * 30:
                continue
            created = _record_gap(
                case,
                "silence_inhabituel",
                CONFIDENCE_POSSIBLE,
                f"Interruption de {delta // DAY_MS} jours dans le fil « {conv['title'] or 'sans titre'} » "
                f"(rythme habituel : environ {max(1, int(median // 60000))} minute(s) entre deux messages). "
                "Cette interruption peut correspondre a une absence d'echange, a des messages non "
                "exportes ou a des messages supprimés : aucune de ces hypothèses n'est établie.",
                conversation_id=conv["id"],
                indicator="ecart superieur a 30 fois l'ecart médian du fil et a 7 jours",
                window_start=ms_to_iso(previous),
                window_end=ms_to_iso(current),
                source_ref=f"conversation #{conv['id']}",
                raw={"gap_ms": delta, "median_ms": median},
            )
            count += 1 if created else 0
    return count


def detect_gaps_for_source(case: Case, source_id: int) -> int:
    total = 0
    total += _detect_unsent(case, source_id)
    total += _detect_missing_media(case, source_id)
    total += _detect_orphan_media(case, source_id)
    total += _detect_missing_numbered_files(case, source_id)
    total += _detect_reaction_without_content(case, source_id)
    total += _detect_temporal_gaps(case, source_id)
    case.conn.commit()
    return total


def list_gaps(case: Case, gap_type: str | None = None, confidence: str | None = None) -> list[dict]:
    sql = (
        "SELECT g.*, c.title AS conversation_title FROM gaps g "
        "LEFT JOIN conversations c ON c.id = g.conversation_id WHERE g.case_id = ?"
    )
    params: list[Any] = [case.case_id]
    if gap_type:
        sql += " AND g.gap_type = ?"
        params.append(gap_type)
    if confidence:
        sql += " AND g.confidence = ?"
        params.append(confidence)
    sql += " ORDER BY CASE g.confidence WHEN 'CONFIRME' THEN 0 WHEN 'PROBABLE' THEN 1 " \
           "WHEN 'POSSIBLE' THEN 2 ELSE 3 END, g.id"
    rows = dbmod.query_all(case.conn, sql, params)
    for row in rows:
        row["label"] = "ÉLÉMENT POTENTIELLEMENT MANQUANT"
        row["summary"] = truncate(row.get("description"), 220)
    return rows
