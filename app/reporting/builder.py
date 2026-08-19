"""Construction du « Rapport Instagram Evidence Recovery » (section 14).

Le rapport contient, dans l'ordre impose par le cahier des charges :
  1. compte concerne          7. abonnés
  2. période etudiee          8. abonnements
  3. sources utilisées        9. changements détectés
  4. intégrité des fichiers  10. médias
  5. conversations           11. éléments potentiellement manquants
  6. chronologie             12. limites de la récupération
Chaque élément important référencé sa source d'origine.
"""
from __future__ import annotations

from typing import Any

from .. import db as dbmod
from ..analysis import compare as compare_mod
from ..analysis import gaps as gaps_mod
from ..analysis import stats as stats_mod
from ..analysis import timeline as timeline_mod
from ..cases import Case
from ..config import APP_NAME, APP_VERSION, UNKNOWN_DATE_LABEL
from ..integrity import build_manifest, verify
from ..publicdata import collect as public_mod
from ..utils import human_size, truncate, utcnow_iso


def _period(case: Case) -> dict[str, Any]:
    row = dbmod.query_one(
        case.conn,
        "SELECT MIN(timestamp_utc) AS mn, MAX(timestamp_utc) AS mx FROM messages "
        "WHERE case_id = ? AND timestamp_utc IS NOT NULL",
        (case.case_id,),
    ) or {}
    ev = dbmod.query_one(
        case.conn,
        "SELECT MIN(occurred_at) AS mn, MAX(occurred_at) AS mx FROM events "
        "WHERE case_id = ? AND occurred_at IS NOT NULL",
        (case.case_id,),
    ) or {}
    candidates_start = [v for v in (row.get("mn"), ev.get("mn")) if v]
    candidates_end = [v for v in (row.get("mx"), ev.get("mx")) if v]
    return {
        "debut": min(candidates_start) if candidates_start else None,
        "fin": max(candidates_end) if candidates_end else None,
        "messages_debut": row.get("mn"),
        "messages_fin": row.get("mx"),
        "note": (
            "Période couverte par les données effectivement importées. Elle ne "
            "préjuge pas de la durée reelle d'existence du compte."
        ),
    }


def build_report(case: Case, deep_verify: bool = True, timeline_limit: int = 3000) -> dict[str, Any]:
    case_id = case.case_id
    statistics = stats_mod.recovery_stats(case)

    sources = dbmod.query_all(
        case.conn,
        "SELECT id, label, kind, declared_origin, declared_date, observations, imported_at, "
        "       file_count, total_bytes, root_path FROM sources WHERE case_id = ? ORDER BY id",
        (case_id,),
    )
    for source in sources:
        source["taille_lisible"] = human_size(source["total_bytes"])

    conversations = dbmod.query_all(
        case.conn,
        "SELECT c.id, c.group_key, c.title, c.external_id, c.participants_json, c.is_group, "
        "       c.message_count, c.first_message_at, c.last_message_at, c.source_files_json, "
        "       s.label AS source_label "
        "FROM conversations c LEFT JOIN sources s ON s.id = c.source_id "
        "WHERE c.case_id = ? ORDER BY c.last_message_at DESC",
        (case_id,),
    )
    for conv in conversations:
        conv["participants"] = dbmod.json_loads(conv["participants_json"], []) or []
        conv["source_files"] = dbmod.json_loads(conv["source_files_json"], []) or []
        counts = dbmod.query_one(
            case.conn,
            "SELECT SUM(direction = 'sent') AS envoyes, SUM(direction = 'received') AS recus, "
            "       SUM(media_path IS NOT NULL) AS médias "
            "FROM messages WHERE conversation_id = ?",
            (conv["id"],),
        ) or {}
        conv["envoyes"] = counts.get("envoyes") or 0
        conv["recus"] = counts.get("recus") or 0
        conv["medias"] = counts.get("medias") or 0

    followers = dbmod.query_all(
        case.conn,
        "SELECT f.username, f.display_name, f.profile_url, f.timestamp_utc, f.source_file, "
        "       s.label AS source_label, s.declared_date "
        "FROM followers f JOIN relationship_snapshots s2 ON s2.id = f.snapshot_id "
        "JOIN sources s ON s.id = s2.source_id "
        "WHERE f.case_id = ? AND f.status = 'followers' ORDER BY f.username",
        (case_id,),
    )
    following = dbmod.query_all(
        case.conn,
        "SELECT f.username, f.display_name, f.profile_url, f.timestamp_utc, f.source_file, "
        "       s.label AS source_label, s.declared_date "
        "FROM following f JOIN relationship_snapshots s2 ON s2.id = f.snapshot_id "
        "JOIN sources s ON s.id = s2.source_id "
        "WHERE f.case_id = ? AND f.status = 'following' ORDER BY f.username",
        (case_id,),
    )
    for row in followers + following:
        row["date_affichee"] = row["timestamp_utc"] or UNKNOWN_DATE_LABEL

    other_relations = dbmod.query_all(
        case.conn,
        "SELECT status, username, timestamp_utc, source_file FROM ("
        "  SELECT status, username, timestamp_utc, source_file FROM followers WHERE case_id = ? "
        "  UNION ALL SELECT status, username, timestamp_utc, source_file FROM following WHERE case_id = ?"
        ") WHERE status NOT IN ('followers', 'following') ORDER BY status, username",
        (case_id, case_id),
    )

    media = dbmod.query_all(
        case.conn,
        "SELECT m.id, m.rel_path, m.kind, m.taken_at, m.context, m.caption, "
        "       f.sha256, f.size_bytes, s.label AS source_label "
        "FROM media m LEFT JOIN files f ON f.id = m.file_id "
        "LEFT JOIN sources s ON s.id = m.source_id "
        "WHERE m.case_id = ? ORDER BY m.taken_at IS NULL, m.taken_at DESC LIMIT 5000",
        (case_id,),
    )

    comparisons = []
    for row in compare_mod.list_comparisons(case):
        result = compare_mod.get_comparison(case, row["id"])
        if result:
            comparisons.append({"meta": row, "result": result})

    evidence = dbmod.query_all(
        case.conn,
        "SELECT e.id, e.label, e.evidence_type, e.declared_origin, e.declared_date, "
        "       e.observations, e.added_at, f.rel_path, f.sha256, f.size_bytes, f.file_mtime "
        "FROM evidence e LEFT JOIN files f ON f.id = e.file_id WHERE e.case_id = ? ORDER BY e.id",
        (case_id,),
    )

    manifest = build_manifest(case)
    integrity_result = verify(case, deep=deep_verify)

    return {
        "meta": {
            "outil": APP_NAME,
            "version": APP_VERSION,
            "genere_le": utcnow_iso(),
            "mode_preuve": case.evidence_mode,
        },
        "compte": {
            "username": case.username,
            "display_name": case.display_name,
            "dossier": case.slug,
            "chemin": str(case.path),
            "cree_le": case.created_at,
            "notes": case.notes,
        },
        "periode": _period(case),
        "profil_public": public_mod.history(case),
        "sources": sources,
        "integrite": {
            "manifest": {
                "nombre_de_fichiers": manifest["nombre_de_fichiers"],
                "taille_totale_octets": manifest["taille_totale_octets"],
                "taille_lisible": human_size(manifest["taille_totale_octets"]),
                "empreinte_du_manifeste": manifest["empreinte_du_manifeste"],
                "algorithme": manifest["algorithme"],
            },
            "verification": integrity_result,
            "fichiers": manifest["fichiers"],
        },
        "conversations": conversations,
        "chronologie": timeline_mod.build(case, limit=timeline_limit),
        "abonnes": followers,
        "abonnements": following,
        "autres_relations": other_relations,
        "changements": comparisons,
        "medias": media,
        "elements_manquants": gaps_mod.list_gaps(case),
        "preuves": evidence,
        "statistiques": statistics,
        "checklist": stats_mod.checklist(case),
        "limites": limites(case, statistics),
    }


def limites(case: Case, statistics: dict[str, Any]) -> list[str]:
    """Limites de la récupération, adaptees a l'état reel du dossier."""
    out: list[str] = []
    if not statistics["export_imported"]:
        out.append(
            "Aucun export Instagram n'a été importé : ce rapport ne contient donc "
            "aucune conversation, aucun abonné et aucun media issus du compte. "
            "L'export officiel est la seule source complète."
        )
    if not statistics["can_compare"]:
        out.append(
            "Un seul export est disponible : il est impossible de dater les "
            "changements (apparitions, disparitions) par comparaison. Un second "
            "export a une autre date permettrait de borner ces événements."
        )
    if not statistics["public_data"]["collected"]:
        out.append(
            "Aucune collecte publique n'a été enregistrée : l'état public actuel "
            "du profil n'est pas documente dans ce rapport."
        )
    out.extend(
        [
            "Les horodatages issus des exports Instagram au format JSON sont exprimés "
            "en temps universel (UTC). Ceux issus des exports HTML sont écrits par Meta "
            "sans indication de fuseau : ils sont repris tels quels et interpretes en UTC, "
            "ce qui peut introduire un décalage de quelques heures.",
            "L'ordre des entrées dans un fichier d'export n'est jamais interprété comme "
            "une chronologie : lorsqu'aucune date n'existe dans la source, la mention "
            f"« {UNKNOWN_DATE_LABEL} » est affichée.",
            "Les éléments listes comme « potentiellement manquants » sont des indices "
            "d'absence, avec leur niveau de confiance. Aucun contenu manquant n'a été "
            "reconstitué, devine ou complète.",
            "Les captures d'écran et les images ne font l'objet d'aucune reconnaissance "
            "automatique de texte : leur contenu n'est pris en compte que via les "
            "observations saisies par l'utilisateur.",
            "Cet outil n'accede a aucun serveur de Meta autre que l'URL publique du "
            "profil, n'utilisé aucun identifiant et ne contourne aucune protection.",
        ]
    )
    out.extend(stats_mod.not_recoverable_notes())
    return out


def summary_lines(report: dict[str, Any]) -> list[str]:
    statistics = report["statistiques"]
    return [
        f"Compte : @{report['compte']['username']}",
        f"Sources analysees : {len(report['sources'])}",
        f"Fichiers conservés et haches : {statistics['files']}",
        f"Conversations : {statistics['conversations']}",
        f"Messages : {statistics['messages']}",
        f"Abonnes distincts : {statistics['followers']}",
        f"Abonnements distincts : {statistics['following']}",
        f"Medias : {statistics['media']}",
        f"Événements de chronologie : {statistics['events']}",
        f"Éléments potentiellement manquants : {statistics['gaps']}",
        f"Comparaisons d'exports : {statistics['comparisons']}",
        "Integrite : "
        + ("intacte" if report["integrite"]["verification"]["integrite_intacte"] else "DIVERGENCE DETECTEE"),
    ]


def preview(report: dict[str, Any]) -> dict[str, Any]:
    """Version allegee pour l'affichage dans l'interface."""
    return {
        "meta": report["meta"],
        "compte": report["compte"],
        "periode": report["periode"],
        "resume": summary_lines(report),
        "sources": report["sources"],
        "integrite": {
            "manifest": report["integrite"]["manifest"],
            "verification": report["integrite"]["verification"],
        },
        "conversations": [
            {
                "titre": c["title"],
                "participants": c["participants"],
                "messages": c["message_count"],
                "du": c["first_message_at"],
                "au": c["last_message_at"],
                "source": c["source_label"],
            }
            for c in report["conversations"][:200]
        ],
        "elements_manquants": [
            {
                "type": g["gap_type"],
                "confiance": g["confidence"],
                "description": truncate(g["description"], 300),
                "source": g["source_ref"],
            }
            for g in report["elements_manquants"][:200]
        ],
        "limites": report["limites"],
        "checklist": report["checklist"],
    }
