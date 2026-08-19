"""Routes de l'API locale (consommee uniquement par l'interface embarquee)."""
from __future__ import annotations

import shutil
import tempfile
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, JSONResponse

from .. import db as dbmod
from .. import integrity as integrity_mod
from .. import sources_local
from ..analysis import compare as compare_mod
from ..analysis import gaps as gaps_mod
from ..analysis import search as search_mod
from ..analysis import stats as stats_mod
from ..analysis import timeline as timeline_mod
from ..androidmod import adb as adb_mod
from ..androidmod import guide as guide_mod
from ..cases import Case, CaseError, EvidenceModeError, create_case, list_cases, open_case
from ..config import APP_NAME, APP_VERSION, UNKNOWN_DATE_LABEL, data_root
from ..importers import pipeline as pipeline_mod
from ..importers.detector import CATEGORY_LABELS, RELATION_LABELS
from ..jobs import MANAGER
from ..people import list_people
from ..publicdata import collect as public_mod
from ..reporting.builder import build_report, preview
from ..reporting.render import generate_all
from ..utils import human_size, is_within, truncate

router = APIRouter(prefix="/api")


def _case(slug: str) -> Case:
    try:
        return open_case(slug)
    except CaseError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


def _guard(func, *args, **kwargs):
    try:
        return func(*args, **kwargs)
    except EvidenceModeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except CaseError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


# ---------------------------------------------------------------------------
# Generalites
# ---------------------------------------------------------------------------
@router.get("/health")
def health() -> dict[str, Any]:
    return {
        "app": APP_NAME,
        "version": APP_VERSION,
        "data_root": str(data_root()),
        "local_only": True,
        "message": "Traitement 100 % local. Aucune donnée n'est envoyée vers un service tiers.",
    }


# ---------------------------------------------------------------------------
# Dossiers d'enquete
# ---------------------------------------------------------------------------
@router.get("/cases")
def get_cases() -> list[dict]:
    return list_cases()


@router.post("/cases")
def post_case(payload: dict = Body(...)) -> dict:
    username = (payload or {}).get("username", "")
    notes = (payload or {}).get("notes")
    try:
        case = create_case(username, notes=notes)
    except CaseError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return case.to_dict()


@router.get("/cases/{slug}")
def get_case(slug: str) -> dict:
    case = _case(slug)
    payload = case.to_dict()
    payload["stats"] = stats_mod.recovery_stats(case)
    return payload


@router.delete("/cases/{slug}")
def delete_case_route(slug: str) -> dict:
    from ..cases import delete_case

    _guard(delete_case, slug)
    return {"deleted": slug}


@router.get("/cases/{slug}/stats")
def get_stats(slug: str) -> dict:
    return stats_mod.recovery_stats(_case(slug))


@router.get("/cases/{slug}/checklist")
def get_checklist(slug: str) -> dict:
    case = _case(slug)
    result = stats_mod.checklist(case)
    result["non_recuperable"] = stats_mod.not_recoverable_notes()
    return result


@router.get("/cases/{slug}/audit")
def get_audit(slug: str, limit: int = 200) -> list[dict]:
    case = _case(slug)
    return dbmod.query_all(
        case.conn,
        "SELECT at, action, detail FROM audit_log WHERE case_id = ? ORDER BY id DESC LIMIT ?",
        (case.case_id, limit),
    )


# ---------------------------------------------------------------------------
# Imports
# ---------------------------------------------------------------------------
def _launch_import(case: Case, path: Path, payload: dict, kind: str, cleanup: Path | None = None):
    label = payload.get("label") or path.name
    job = MANAGER.create(f"Import : {label}", case_slug=case.slug)

    def work(current_job):
        progress = MANAGER.progress(current_job)
        progress("copie et empreintes", 0, 0)
        try:
            if kind == "instagram_export":
                _result, report = pipeline_mod.import_and_analyze(
                    case,
                    path,
                    kind=kind,
                    label=label,
                    declared_origin=payload.get("declared_origin"),
                    declared_date=payload.get("declared_date"),
                    observations=payload.get("observations"),
                    progress=progress,
                )
                return report.to_dict()
            return sources_local.add_local_source(
                case,
                path,
                label=label,
                declared_origin=payload.get("declared_origin"),
                declared_date=payload.get("declared_date"),
                observations=payload.get("observations"),
                kind=kind,
            )
        finally:
            if cleanup and cleanup.exists():
                shutil.rmtree(cleanup, ignore_errors=True)

    MANAGER.run(job, work)
    return job.to_dict()


@router.post("/cases/{slug}/import/path")
def import_from_path(slug: str, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    if case.evidence_mode:
        raise HTTPException(
            status_code=409,
            detail="Mode preuve actif : désactivé-le pour importer de nouvelles données.",
        )
    raw_path = (payload or {}).get("path", "").strip().strip('"')
    if not raw_path:
        raise HTTPException(status_code=400, detail="Chemin manquant.")
    path = Path(raw_path).expanduser()
    if not path.exists():
        raise HTTPException(status_code=400, detail=f"Chemin introuvable : {path}")
    kind = (payload or {}).get("kind") or "instagram_export"
    return _launch_import(case, path, payload or {}, kind)


@router.post("/cases/{slug}/import/upload")
async def import_upload(
    slug: str,
    file: UploadFile = File(...),
    label: str = Query(default=""),
    kind: str = Query(default="instagram_export"),
    declared_origin: str = Query(default=""),
    declared_date: str = Query(default=""),
    observations: str = Query(default=""),
) -> dict:
    case = _case(slug)
    if case.evidence_mode:
        raise HTTPException(
            status_code=409,
            detail="Mode preuve actif : désactivé-le pour importer de nouvelles données.",
        )
    staging = Path(tempfile.mkdtemp(prefix="ier_upload_"))
    target = staging / (Path(file.filename or "fichier").name)
    with open(target, "wb") as handle:
        while chunk := await file.read(4 * 1024 * 1024):
            handle.write(chunk)
    payload = {
        "label": label or target.name,
        "declared_origin": declared_origin or None,
        "declared_date": declared_date or None,
        "observations": observations or None,
    }
    return _launch_import(case, target, payload, kind, cleanup=staging)


@router.get("/jobs")
def get_jobs(case: str | None = None) -> list[dict]:
    return MANAGER.list(case_slug=case)


@router.get("/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    job = MANAGER.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Tache inconnue.")
    return job.to_dict()


@router.get("/cases/{slug}/sources")
def get_sources(slug: str) -> list[dict]:
    case = _case(slug)
    rows = dbmod.query_all(
        case.conn,
        "SELECT s.*, "
        "  (SELECT COUNT(*) FROM conversations c WHERE c.source_id = s.id) AS conversations, "
        "  (SELECT COUNT(*) FROM messages m WHERE m.source_id = s.id) AS messages, "
        "  (SELECT COUNT(*) FROM media md WHERE md.source_id = s.id) AS medias "
        "FROM sources s WHERE s.case_id = ? ORDER BY s.id DESC",
        (case.case_id,),
    )
    for row in rows:
        row["taille_lisible"] = human_size(row["total_bytes"])
        summary = dbmod.query_one(
            case.conn,
            "SELECT summary_json FROM imports WHERE source_id = ? ORDER BY id DESC LIMIT 1",
            (row["id"],),
        )
        row["resume"] = dbmod.json_loads(summary["summary_json"]) if summary else None
    return rows


@router.get("/cases/{slug}/files")
def get_files(slug: str, category: str | None = None, q: str | None = None, limit: int = 500) -> list[dict]:
    case = _case(slug)
    sql = "SELECT id, rel_path, original_name, extension, size_bytes, sha256, file_mtime, imported_at, category, parsed, parse_note FROM files WHERE case_id = ?"
    params: list[Any] = [case.case_id]
    if category:
        sql += " AND category = ?"
        params.append(category)
    if q:
        sql += " AND rel_path LIKE ?"
        params.append(f"%{q}%")
    sql += " ORDER BY rel_path LIMIT ?"
    params.append(limit)
    rows = dbmod.query_all(case.conn, sql, params)
    for row in rows:
        row["taille_lisible"] = human_size(row["size_bytes"])
        row["categorie_label"] = CATEGORY_LABELS.get(row["category"], row["category"] or "Non classe")
    return rows


# ---------------------------------------------------------------------------
# Conversations
# ---------------------------------------------------------------------------
@router.get("/cases/{slug}/conversations")
def get_conversations(slug: str, q: str | None = None, source_id: int | None = None) -> list[dict]:
    case = _case(slug)
    sql = (
        "SELECT group_key, "
        "  MAX(title) AS title, "
        "  MAX(external_id) AS external_id, "
        "  SUM(message_count) AS raw_message_count, "
        "  MIN(first_message_at) AS first_message_at, "
        "  MAX(last_message_at) AS last_message_at, "
        "  MAX(is_group) AS is_group, "
        "  GROUP_CONCAT(DISTINCT source_id) AS source_ids, "
        "  GROUP_CONCAT(DISTINCT id) AS conversation_ids, "
        "  MAX(participants_json) AS participants_json "
        "FROM conversations WHERE case_id = ?"
    )
    params: list[Any] = [case.case_id]
    if source_id:
        sql += " AND source_id = ?"
        params.append(source_id)
    if q:
        sql += " AND (title LIKE ? OR participants_json LIKE ?)"
        params.extend([f"%{q}%", f"%{q}%"])
    sql += " GROUP BY group_key ORDER BY last_message_at DESC"
    rows = dbmod.query_all(case.conn, sql, params)

    for row in rows:
        ids = [int(x) for x in str(row["conversation_ids"]).split(",") if x]
        marks = ",".join("?" for _ in ids)
        distinct = dbmod.query_one(
            case.conn,
            f"SELECT COUNT(DISTINCT dedup_key) AS n, "
            f"       SUM(media_path IS NOT NULL) AS médias "
            f"FROM messages WHERE conversation_id IN ({marks})",
            ids,
        ) or {}
        row["message_count"] = distinct.get("n") or 0
        row["media_count"] = distinct.get("medias") or 0
        row["participants"] = dbmod.json_loads(row["participants_json"], []) or []
        row["source_count"] = len(str(row["source_ids"]).split(","))
        row.pop("participants_json", None)
    return rows


@router.get("/cases/{slug}/conversations/messages")
def get_messages(
    slug: str,
    group_key: str,
    q: str | None = None,
    sender: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    msg_type: str | None = None,
    direction: str | None = None,
    only_media: bool = False,
    only_links: bool = False,
    limit: int = 2000,
    offset: int = 0,
) -> dict:
    case = _case(slug)
    conversations = dbmod.query_all(
        case.conn,
        "SELECT id, source_id, title FROM conversations WHERE case_id = ? AND group_key = ?",
        (case.case_id, group_key),
    )
    if not conversations:
        raise HTTPException(status_code=404, detail="Conversation introuvable.")
    ids = [c["id"] for c in conversations]
    marks = ",".join("?" for _ in ids)

    sql = (
        "SELECT m.*, s.label AS source_label FROM messages m "
        "LEFT JOIN sources s ON s.id = m.source_id "
        f"WHERE m.conversation_id IN ({marks})"
    )
    params: list[Any] = list(ids)
    if q:
        sql += " AND (m.content LIKE ? OR m.media_path LIKE ?)"
        params.extend([f"%{q}%", f"%{q}%"])
    if sender:
        sql += " AND m.sender LIKE ?"
        params.append(f"%{sender}%")
    if date_from:
        sql += " AND m.timestamp_utc >= ?"
        params.append(date_from)
    if date_to:
        sql += " AND m.timestamp_utc <= ?"
        params.append(date_to)
    if msg_type:
        sql += " AND m.msg_type = ?"
        params.append(msg_type)
    if direction:
        sql += " AND m.direction = ?"
        params.append(direction)
    if only_media:
        sql += " AND m.media_path IS NOT NULL"
    if only_links:
        sql += " AND (m.share_json IS NOT NULL OR m.content LIKE '%http%')"
    sql += " ORDER BY m.timestamp_ms IS NULL, m.timestamp_ms ASC, m.id ASC LIMIT ? OFFSET ?"
    params.extend([limit, offset])

    rows = dbmod.query_all(case.conn, sql, params)

    seen: set[str] = set()
    messages: list[dict] = []
    for row in rows:
        key = row["dedup_key"] or f"id:{row['id']}"
        if key in seen:
            continue
        seen.add(key)
        messages.append(
            {
                "id": row["id"],
                "sender": row["sender"],
                "direction": row["direction"],
                "timestamp": row["timestamp_utc"],
                "date_label": row["timestamp_utc"] or UNKNOWN_DATE_LABEL,
                "content": row["content"],
                "type": row["msg_type"],
                "media_path": row["media_path"],
                "reactions": dbmod.json_loads(row["reactions_json"], []),
                "share": dbmod.json_loads(row["share_json"]),
                "source_file": row["source_file"],
                "source_label": row["source_label"],
            }
        )

    gaps = gaps_mod.list_gaps(case)
    conversation_gaps = [g for g in gaps if g["conversation_id"] in ids]

    return {
        "group_key": group_key,
        "title": conversations[0]["title"],
        "messages": messages,
        "count": len(messages),
        "gaps": conversation_gaps,
        "note": (
            "Les messages affichés proviennent uniquement des fichiers importés. "
            "Aucun message n'est reconstitué : les absences détectées sont listées "
            "separement comme éléments potentiellement manquants."
        ),
    }


@router.get("/cases/{slug}/messages/{message_id}/raw")
def get_message_raw(slug: str, message_id: int) -> dict:
    case = _case(slug)
    row = dbmod.query_one(
        case.conn,
        "SELECT m.*, f.rel_path, f.sha256 FROM messages m LEFT JOIN files f ON f.id = m.file_id "
        "WHERE m.id = ? AND m.case_id = ?",
        (message_id, case.case_id),
    )
    if not row:
        raise HTTPException(status_code=404, detail="Message introuvable.")
    return {
        "id": row["id"],
        "source_file": row["source_file"],
        "file_path": row["rel_path"],
        "file_sha256": row["sha256"],
        "dedup_key": row["dedup_key"],
        "donnee_originale": dbmod.json_loads(row["original_raw"], row["original_raw"]),
    }


# ---------------------------------------------------------------------------
# Relations
# ---------------------------------------------------------------------------
@router.get("/cases/{slug}/relations")
def get_relations(
    slug: str,
    kind: str = "followers",
    sort: str = "date_desc",
    q: str | None = None,
    source_id: int | None = None,
    limit: int = 5000,
) -> dict:
    case = _case(slug)
    table = "followers" if kind in {"followers", "pending_received"} else "following"
    sql = (
        f"SELECT r.*, s.label AS snapshot_label, s.captured_at, src.label AS source_label, "
        f"       src.id AS source_id FROM {table} r "
        f"JOIN relationship_snapshots s ON s.id = r.snapshot_id "
        f"JOIN sources src ON src.id = s.source_id "
        f"WHERE r.case_id = ? AND r.status = ?"
    )
    params: list[Any] = [case.case_id, kind]
    if q:
        sql += " AND (r.username LIKE ? OR r.display_name LIKE ?)"
        params.extend([f"%{q}%", f"%{q}%"])
    if source_id:
        sql += " AND src.id = ?"
        params.append(source_id)

    order = {
        "date_desc": "r.timestamp_ms IS NULL, r.timestamp_ms DESC",
        "date_asc": "r.timestamp_ms IS NULL, r.timestamp_ms ASC",
        "alpha": "LOWER(IFNULL(r.username, r.display_name)) ASC",
        "alpha_desc": "LOWER(IFNULL(r.username, r.display_name)) DESC",
    }.get(sort, "r.timestamp_ms IS NULL, r.timestamp_ms DESC")
    sql += f" ORDER BY {order} LIMIT ?"
    params.append(limit)

    rows = dbmod.query_all(case.conn, sql, params)
    entries = [
        {
            "username": row["username"],
            "display_name": row["display_name"],
            "profile_url": row["profile_url"],
            "date": row["timestamp_utc"],
            "date_label": row["timestamp_utc"] or UNKNOWN_DATE_LABEL,
            "has_real_date": row["timestamp_utc"] is not None,
            "status": row["status"],
            "source_file": row["source_file"],
            "source_label": row["source_label"],
            "source_id": row["source_id"],
            "snapshot_date": row["captured_at"],
            "imported_at": row["snapshot_label"],
        }
        for row in rows
    ]
    without_date = sum(1 for e in entries if not e["has_real_date"])
    return {
        "kind": kind,
        "label": RELATION_LABELS.get(kind, kind),
        "count": len(entries),
        "sans_date_reelle": without_date,
        "entries": entries,
        "note": (
            "Une date n'est affichée que si elle figure reellement dans le fichier "
            f"source. L'ordre des entrées d'un export n'est jamais interprété comme une "
            f"chronologie : {without_date} entree(s) sont affichées « {UNKNOWN_DATE_LABEL} »."
        ),
    }


@router.get("/cases/{slug}/relation-kinds")
def get_relation_kinds(slug: str) -> list[dict]:
    case = _case(slug)
    rows = dbmod.query_all(
        case.conn,
        "SELECT status, COUNT(*) AS n FROM ("
        "  SELECT status FROM followers WHERE case_id = ? "
        "  UNION ALL SELECT status FROM following WHERE case_id = ?"
        ") GROUP BY status ORDER BY n DESC",
        (case.case_id, case.case_id),
    )
    for row in rows:
        row["label"] = RELATION_LABELS.get(row["status"], row["status"])
    return rows


@router.get("/cases/{slug}/people")
def get_people(slug: str, q: str | None = None) -> list[dict]:
    return list_people(_case(slug), query=q)


# ---------------------------------------------------------------------------
# Chronologie, manquants, recherche
# ---------------------------------------------------------------------------
@router.get("/cases/{slug}/timeline")
def get_timeline(
    slug: str,
    date_from: str | None = None,
    date_to: str | None = None,
    kinds: str | None = None,
    confidences: str | None = None,
    limit: int = 5000,
) -> dict:
    case = _case(slug)
    result = timeline_mod.build(
        case,
        date_from=date_from,
        date_to=date_to,
        kinds=[k for k in (kinds or "").split(",") if k] or None,
        confidences=[c for c in (confidences or "").split(",") if c] or None,
        limit=limit,
    )
    result["kinds_available"] = timeline_mod.kinds_available(case)
    return result


@router.get("/cases/{slug}/gaps")
def get_gaps(slug: str, gap_type: str | None = None, confidence: str | None = None) -> dict:
    case = _case(slug)
    rows = gaps_mod.list_gaps(case, gap_type=gap_type, confidence=confidence)
    types = dbmod.query_all(
        case.conn,
        "SELECT gap_type, confidence, COUNT(*) AS n FROM gaps WHERE case_id = ? "
        "GROUP BY gap_type, confidence ORDER BY n DESC",
        (case.case_id,),
    )
    return {
        "items": rows,
        "count": len(rows),
        "par_type": types,
        "avertissement": (
            "Ces éléments ne sont pas des contenus retrouvés : ce sont des indices "
            "d'absence. Aucun message, aucune date et aucun contenu n'a été inventé."
        ),
    }


@router.get("/cases/{slug}/search")
def get_search(
    slug: str,
    q: str = "",
    kinds: str | None = None,
    person: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    source_ref: str | None = None,
    limit: int = 200,
) -> dict:
    case = _case(slug)
    return search_mod.search(
        case,
        q,
        kinds=[k for k in (kinds or "").split(",") if k] or None,
        person=person,
        date_from=date_from,
        date_to=date_to,
        source_ref=source_ref,
        limit=limit,
    )


@router.post("/cases/{slug}/reindex")
def post_reindex(slug: str) -> dict:
    case = _case(slug)
    return {"indexed": search_mod.reindex(case)}


# ---------------------------------------------------------------------------
# Comparaison d'exports
# ---------------------------------------------------------------------------
@router.post("/cases/{slug}/compare")
def post_compare(slug: str, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    try:
        source_a = int(payload["source_a"])
        source_b = int(payload["source_b"])
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="Deux identifiants de source sont requis.") from exc
    if source_a == source_b:
        raise HTTPException(status_code=400, detail="Choisis deux exports différents.")
    try:
        return compare_mod.compare_sources(case, source_a, source_b)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/cases/{slug}/comparisons")
def get_comparisons(slug: str) -> list[dict]:
    return compare_mod.list_comparisons(_case(slug))


@router.get("/cases/{slug}/comparisons/{comparison_id}")
def get_comparison(slug: str, comparison_id: int) -> dict:
    result = compare_mod.get_comparison(_case(slug), comparison_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Comparaison introuvable.")
    return result


# ---------------------------------------------------------------------------
# Donnees publiques
# ---------------------------------------------------------------------------
@router.get("/cases/{slug}/public")
def get_public(slug: str) -> dict:
    case = _case(slug)
    return {
        "history": public_mod.history(case),
        "latest": public_mod.latest(case),
        "url": public_mod.PROFILE_URL.format(username=case.username),
        "avertissement": (
            "Seules les informations publiques sont collectees, sans identifiant, "
            "sans cookie et sans contournement. Si Instagram limite l'accès, "
            "le blocage est enregistré tel quel."
        ),
    }


@router.post("/cases/{slug}/public/collect")
def post_public_collect(slug: str) -> dict:
    case = _case(slug)
    return _guard(public_mod.collect_public, case)


@router.post("/cases/{slug}/public/manual")
def post_public_manual(slug: str, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    return _guard(
        public_mod.add_manual,
        case,
        content=(payload or {}).get("content"),
        url=(payload or {}).get("url"),
        note=(payload or {}).get("note"),
        method=(payload or {}).get("method") or "manual_html",
    )


# ---------------------------------------------------------------------------
# Preuves et sources locales
# ---------------------------------------------------------------------------
@router.get("/cases/{slug}/evidence")
def get_evidence(slug: str, evidence_type: str | None = None) -> list[dict]:
    return sources_local.list_evidence(_case(slug), evidence_type=evidence_type)


@router.post("/cases/{slug}/evidence/note")
def post_note(slug: str, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    label = (payload or {}).get("label")
    observations = (payload or {}).get("observations")
    if not (label and observations):
        raise HTTPException(status_code=400, detail="Un libellé et une observation sont requis.")
    evidence_id = _guard(
        sources_local.add_note,
        case,
        label,
        observations,
        (payload or {}).get("declared_origin"),
        (payload or {}).get("declared_date"),
    )
    return {"id": evidence_id}


@router.patch("/cases/{slug}/evidence/{evidence_id}")
def patch_evidence(slug: str, evidence_id: int, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    updated = _guard(
        sources_local.update_evidence,
        case,
        evidence_id,
        label=(payload or {}).get("label"),
        declared_origin=(payload or {}).get("declared_origin"),
        declared_date=(payload or {}).get("declared_date"),
        observations=(payload or {}).get("observations"),
    )
    return {"updated": bool(updated)}


@router.get("/cases/{slug}/media")
def get_media(slug: str, kind: str | None = None, limit: int = 500, offset: int = 0) -> dict:
    case = _case(slug)
    sql = (
        "SELECT m.id, m.rel_path, m.kind, m.taken_at, m.context, m.caption, "
        "       f.rel_path AS file_path, f.sha256, f.size_bytes "
        "FROM media m LEFT JOIN files f ON f.id = m.file_id WHERE m.case_id = ?"
    )
    params: list[Any] = [case.case_id]
    if kind:
        sql += " AND m.kind = ?"
        params.append(kind)
    sql += " ORDER BY m.taken_at IS NULL, m.taken_at DESC LIMIT ? OFFSET ?"
    params.extend([limit, offset])
    rows = dbmod.query_all(case.conn, sql, params)
    total = dbmod.scalar(case.conn, "SELECT COUNT(*) FROM media WHERE case_id = ?", (case.case_id,))
    for row in rows:
        row["taille_lisible"] = human_size(row["size_bytes"])
        row["date_label"] = row["taken_at"] or UNKNOWN_DATE_LABEL
    return {"items": rows, "total": total or 0, "offset": offset}


@router.get("/cases/{slug}/file")
def get_file(slug: str, rel_path: str) -> FileResponse:
    """Sert un fichier du dossier d'enquête (jamais hors de ce dossier)."""
    case = _case(slug)
    target = (case.path / rel_path).resolve()
    if not is_within(target, case.path) or not target.is_file():
        raise HTTPException(status_code=404, detail="Fichier introuvable dans ce dossier.")
    return FileResponse(str(target), filename=target.name)


@router.get("/cases/{slug}/resolve-media")
def resolve_media(slug: str, media_path: str) -> dict:
    """Retrouve le fichier local correspondant a un chemin cite dans un message."""
    case = _case(slug)
    name = Path(media_path.replace("\\", "/")).name
    row = dbmod.query_one(
        case.conn,
        "SELECT rel_path, sha256, size_bytes FROM files WHERE case_id = ? AND original_name = ? LIMIT 1",
        (case.case_id, name),
    )
    if not row:
        return {"found": False, "media_path": media_path, "message": "Fichier absent des sources importées."}
    return {"found": True, "rel_path": row["rel_path"], "sha256": row["sha256"], "size": row["size_bytes"]}


# ---------------------------------------------------------------------------
# Mode preuve et integrite
# ---------------------------------------------------------------------------
@router.post("/cases/{slug}/evidence-mode")
def post_evidence_mode(slug: str, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    enabled = bool((payload or {}).get("enabled"))
    if enabled:
        return integrity_mod.enable_evidence_mode(case)
    return integrity_mod.disable_evidence_mode(case)


@router.get("/cases/{slug}/integrity")
def get_integrity(slug: str, deep: bool = True) -> dict:
    return integrity_mod.verify(_case(slug), deep=deep)


@router.get("/cases/{slug}/manifest")
def get_manifest(slug: str) -> dict:
    case = _case(slug)
    integrity_mod.write_manifest(case)
    return integrity_mod.build_manifest(case)


# ---------------------------------------------------------------------------
# Rapport
# ---------------------------------------------------------------------------
@router.get("/cases/{slug}/report/preview")
def get_report_preview(slug: str) -> dict:
    case = _case(slug)
    return preview(build_report(case, deep_verify=False))


@router.post("/cases/{slug}/report")
def post_report(slug: str) -> dict:
    case = _case(slug)
    job = MANAGER.create("Génération du rapport", case_slug=case.slug)

    def work(current_job):
        progress = MANAGER.progress(current_job)
        progress("collecte des données", 1, 3)
        report = build_report(case)
        progress("rendu des formats", 2, 3)
        produced = generate_all(case, report)
        progress("termine", 3, 3)
        return produced

    MANAGER.run(job, work)
    return job.to_dict()


@router.get("/cases/{slug}/reports")
def get_reports(slug: str) -> list[dict]:
    case = _case(slug)
    out: list[dict] = []
    if not case.reports_dir.exists():
        return out
    for child in sorted(case.reports_dir.iterdir(), reverse=True):
        if child.is_dir():
            files = {p.suffix.lstrip("."): str(p) for p in child.glob("*.*")}
            out.append(
                {
                    "nom": child.name,
                    "chemin": str(child),
                    "rel_html": f"reports/{child.name}/rapport.html",
                    "rel_pdf": f"reports/{child.name}/rapport.pdf"
                    if (child / "rapport.pdf").exists()
                    else None,
                    "rel_json": f"reports/{child.name}/rapport.json",
                    "rel_zip": f"reports/{child.name}.zip"
                    if (case.reports_dir / f"{child.name}.zip").exists()
                    else None,
                    "fichiers": files,
                }
            )
    return out


# ---------------------------------------------------------------------------
# Android
# ---------------------------------------------------------------------------
@router.get("/android/guide")
def get_android_guide() -> dict:
    return guide_mod.full_guide()


@router.get("/android/status")
def get_android_status() -> dict:
    return adb_mod.status()


@router.get("/android/scan")
def get_android_scan(serial: str | None = None, path: str | None = None) -> Any:
    try:
        if path:
            return adb_mod.scan_directory(path, serial=serial)
        return adb_mod.scan_default_dirs(serial=serial)
    except adb_mod.AdbError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/cases/{slug}/android/pull")
def post_android_pull(slug: str, payload: dict = Body(...)) -> dict:
    case = _case(slug)
    remote_path = (payload or {}).get("path")
    if not remote_path:
        raise HTTPException(status_code=400, detail="Chemin distant manquant.")
    job = MANAGER.create(f"Import Android : {remote_path}", case_slug=case.slug)

    def work(_job):
        return adb_mod.pull_to_case(
            case,
            remote_path,
            serial=(payload or {}).get("serial"),
            label=(payload or {}).get("label"),
            observations=(payload or {}).get("observations"),
        )

    MANAGER.run(job, work)
    return job.to_dict()
