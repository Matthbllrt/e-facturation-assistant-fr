"""Sources locales et éléments de preuve (section 9).

Accepte n'importe quel fichier : capture d'écran, video d'écran, sauvegarde de
téléphone, archive, e-mail Instagram sauvegarde, HTML, PDF, TXT, JSON, CSV.
Pour chaque élément sont conservés : le fichier original, son SHA-256, sa
taille, sa date de fichier, sa date d'import, la provenance déclarée et les
observations de l'utilisateur.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from . import db as dbmod
from .cases import Case
from .config import MEDIA_EXTENSIONS
from .utils import read_text_file, truncate, utcnow_iso

EVIDENCE_TYPES = {
    ".png": "capture",
    ".jpg": "capture",
    ".jpeg": "capture",
    ".webp": "capture",
    ".gif": "capture",
    ".heic": "capture",
    ".mp4": "video",
    ".mov": "video",
    ".mkv": "video",
    ".webm": "video",
    ".pdf": "pdf",
    ".html": "html",
    ".htm": "html",
    ".txt": "texte",
    ".csv": "csv",
    ".json": "json",
    ".eml": "email",
    ".msg": "email",
    ".zip": "archive",
    ".db": "sauvegarde",
    ".sqlite": "sauvegarde",
    ".xml": "xml",
}

MAX_EXTRACT_CHARS = 200_000


def _extract_pdf_text(path: Path) -> tuple[str | None, str | None]:
    try:
        from pypdf import PdfReader
    except BaseException as exc:  # pypdf peut echouer a l'import selon l'installation
        return None, f"Lecture PDF indisponible ({type(exc).__name__}). Texte non extrait."
    try:
        reader = PdfReader(str(path))
        chunks = []
        for page in reader.pages[:200]:
            chunks.append(page.extract_text() or "")
        text = "\n".join(chunks).strip()
        if not text:
            return None, (
                "PDF sans couche texte (probablement un scan ou une image) : "
                "aucun texte extrait, le fichier reste consultable tel quel."
            )
        return text[:MAX_EXTRACT_CHARS], None
    except BaseException as exc:
        return None, f"PDF illisible ({type(exc).__name__}: {exc})."


def _extract_html_text(path: Path) -> tuple[str | None, str | None]:
    from .importers.htmlkit import parse_html

    try:
        tree = parse_html(read_text_file(path))
        return truncate(tree.all_text(), MAX_EXTRACT_CHARS) or None, None
    except OSError as exc:
        return None, str(exc)


def extract_text(path: Path) -> tuple[str | None, str | None]:
    """Retourne (texte extrait, note). Aucune OCR n'est effectuée."""
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return _extract_pdf_text(path)
    if suffix in {".html", ".htm"}:
        return _extract_html_text(path)
    if suffix in {".txt", ".csv", ".json", ".xml", ".md", ".eml", ".log"}:
        try:
            return read_text_file(path)[:MAX_EXTRACT_CHARS], None
        except OSError as exc:
            return None, str(exc)
    if suffix in MEDIA_EXTENSIONS:
        return None, (
            "Image ou video : aucun texte n'est extrait automatiquement "
            "(pas de reconnaissance de caractères). Decris son contenu dans "
            "les observations pour la rendre exploitable dans la recherche."
        )
    return None, "Type de fichier non textuel : contenu conservé tel quel."


def add_local_source(
    case: Case,
    input_path: Path,
    *,
    label: str | None = None,
    declared_origin: str | None = None,
    declared_date: str | None = None,
    observations: str | None = None,
    kind: str = "local_files",
    analyze: bool = True,
) -> dict[str, Any]:
    """Ajoute un fichier ou un dossier comme source locale de preuve."""
    from .analysis import search as search_mod
    from .importers import ingest as ingest_mod
    from .importers import pipeline as pipeline_mod

    case.require_writable("ajout d'une source locale")
    input_path = Path(input_path)
    result = ingest_mod.ingest(
        case,
        input_path,
        kind=kind,
        label=label or input_path.name,
        declared_origin=declared_origin,
        declared_date=declared_date,
        observations=observations,
    )

    files = dbmod.query_all(
        case.conn,
        "SELECT id, rel_path, extension, original_name FROM files WHERE case_id = ? AND source_id = ?",
        (case.case_id, result.source_id),
    )
    notes: list[str] = []
    for row in files:
        path = case.path / row["rel_path"]
        text, note = extract_text(path)
        if note:
            notes.append(f"{row['original_name']} : {note}")
        dbmod.insert(
            case.conn,
            "evidence",
            {
                "case_id": case.case_id,
                "source_id": result.source_id,
                "file_id": row["id"],
                "label": row["original_name"],
                "evidence_type": EVIDENCE_TYPES.get(row["extension"] or "", "fichier"),
                "declared_origin": declared_origin,
                "declared_date": declared_date,
                "observations": observations,
                "extracted_text": text,
                "added_at": utcnow_iso(),
            },
        )
    case.conn.commit()

    report = None
    if analyze:
        # Une source locale peut aussi contenir un export Instagram complet :
        # on lui applique la meme analyse, sans rien forcer.
        report = pipeline_mod.analyze_source(case, result.source_id, result.root_path)
    search_mod.reindex(case)
    case.audit("source_locale", f"{label or input_path.name} ({result.file_count} fichiers)")

    return {
        "source_id": result.source_id,
        "files": result.file_count,
        "total_bytes": result.total_bytes,
        "extraction_notes": notes[:100],
        "skipped": result.skipped[:50],
        "analysis": report.to_dict() if report else None,
    }


def add_note(
    case: Case,
    label: str,
    observations: str,
    declared_origin: str | None = None,
    declared_date: str | None = None,
) -> int:
    """Ajoute une observation écrite (sans fichier) comme élément de preuve."""
    case.require_writable("ajout d'une note")
    evidence_id = dbmod.insert(
        case.conn,
        "evidence",
        {
            "case_id": case.case_id,
            "source_id": None,
            "file_id": None,
            "label": label,
            "evidence_type": "note",
            "declared_origin": declared_origin,
            "declared_date": declared_date,
            "observations": observations,
            "extracted_text": observations,
            "added_at": utcnow_iso(),
        },
    )
    dbmod.insert(
        case.conn,
        "events",
        {
            "case_id": case.case_id,
            "occurred_at": declared_date,
            "occurred_ms": None,
            "kind": "note",
            "title": f"Note : {truncate(label, 120)}",
            "detail": truncate(observations, 500),
            "confidence": "INCONNU" if not declared_date else "POSSIBLE",
            "source_ref": declared_origin or "note manuelle de l'utilisateur",
            "ref_table": "evidence",
            "ref_id": evidence_id,
        },
    )
    case.conn.commit()
    from .analysis import search as search_mod

    search_mod.reindex(case)
    return evidence_id


def list_evidence(case: Case, evidence_type: str | None = None) -> list[dict]:
    sql = (
        "SELECT e.*, f.rel_path, f.sha256, f.size_bytes, f.file_mtime, s.label AS source_label "
        "FROM evidence e LEFT JOIN files f ON f.id = e.file_id "
        "LEFT JOIN sources s ON s.id = e.source_id WHERE e.case_id = ?"
    )
    params: list[Any] = [case.case_id]
    if evidence_type:
        sql += " AND e.evidence_type = ?"
        params.append(evidence_type)
    sql += " ORDER BY e.added_at DESC, e.id DESC"
    rows = dbmod.query_all(case.conn, sql, params)
    for row in rows:
        row["extract_preview"] = truncate(row.get("extracted_text"), 300)
        row.pop("extracted_text", None)
    return rows


def update_evidence(
    case: Case,
    evidence_id: int,
    *,
    label: str | None = None,
    declared_origin: str | None = None,
    declared_date: str | None = None,
    observations: str | None = None,
) -> bool:
    """Met a jour les annotations d'un élément de preuve.

    Seules les annotations de l'utilisateur sont modifiables : le fichier
    original, son empreinte et son texte extrait ne le sont jamais.
    """
    case.require_writable("modification d'une annotation")
    updates: dict[str, Any] = {}
    for key, value in (
        ("label", label),
        ("declared_origin", declared_origin),
        ("declared_date", declared_date),
        ("observations", observations),
    ):
        if value is not None:
            updates[key] = value
    if not updates:
        return False
    assignments = ", ".join(f"{k} = ?" for k in updates)
    case.conn.execute(
        f"UPDATE evidence SET {assignments} WHERE id = ? AND case_id = ?",
        (*updates.values(), evidence_id, case.case_id),
    )
    case.conn.commit()
    case.audit("annotation_preuve", f"element #{evidence_id}")
    from .analysis import search as search_mod

    search_mod.reindex(case)
    return True
