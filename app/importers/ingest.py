"""Ingestion des fichiers dans un dossier d'enquête.

Principes (sections 9 et 13) :
  * le fichier original est TOUJOURS copie tel quel dans cases/<slug>/sources ;
  * son SHA-256, sa taille et sa date sont enregistrés a l'import ;
  * rien n'est jamais réécrit : les analyses produisent des copies normalisées.
"""
from __future__ import annotations

import os
import shutil
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

from .. import db as dbmod
from ..cases import Case
from ..config import MEDIA_EXTENSIONS
from ..utils import (
    file_mtime_iso,
    guess_mime,
    is_within,
    safe_relpath,
    sha256_file,
    slugify,
    utcnow_iso,
)

ProgressFn = Callable[[str, int, int], None]

SKIP_DIR_NAMES = {"__MACOSX", ".git", "node_modules", ".DS_Store"}


@dataclass
class IngestResult:
    source_id: int
    root_path: Path
    file_count: int
    total_bytes: int
    extracted_archives: int
    skipped: list[str]


def _unique_dir(parent: Path, name: str) -> Path:
    """Créé un dossier unique : name, name-2, name-3..."""
    base = slugify(name) or "source"
    candidate = parent / base
    index = 2
    while candidate.exists():
        candidate = parent / f"{base}-{index}"
        index += 1
    candidate.mkdir(parents=True)
    return candidate


def _safe_extract_zip(zip_path: Path, dest: Path, skipped: list[str]) -> int:
    """Decompresse en refusant les chemins hors du dossier cible (zip-slip)."""
    count = 0
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            member_name = info.filename.replace("\\", "/")
            if any(part in SKIP_DIR_NAMES for part in member_name.split("/")):
                continue
            target = (dest / member_name).resolve()
            if not is_within(target, dest):
                skipped.append(f"{zip_path.name}:{member_name} (chemin hors archive, ignore)")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, length=1024 * 1024)
            count += 1
    return count


def _iter_files(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_NAMES]
        for name in filenames:
            if name in SKIP_DIR_NAMES:
                continue
            yield Path(dirpath) / name


def register_file(
    case: Case,
    source_id: int | None,
    abs_path: Path,
    category: str | None = None,
    original_path: str | None = None,
) -> int:
    """Enregistre un fichier (déjà présent dans le dossier d'enquête)."""
    rel = safe_relpath(abs_path, case.path)
    existing = dbmod.query_one(
        case.conn,
        "SELECT id FROM files WHERE case_id = ? AND rel_path = ?",
        (case.case_id, rel),
    )
    if existing:
        return int(existing["id"])
    stat = abs_path.stat()
    return dbmod.insert(
        case.conn,
        "files",
        {
            "case_id": case.case_id,
            "source_id": source_id,
            "rel_path": rel,
            "original_name": abs_path.name,
            "original_path": original_path,
            "extension": abs_path.suffix.lower(),
            "mime_guess": guess_mime(abs_path),
            "size_bytes": stat.st_size,
            "sha256": sha256_file(abs_path),
            "file_mtime": file_mtime_iso(abs_path),
            "imported_at": utcnow_iso(),
            "category": category,
            "parsed": 0,
        },
    )


def ingest(
    case: Case,
    input_path: Path,
    *,
    kind: str = "instagram_export",
    label: str | None = None,
    declared_origin: str | None = None,
    declared_date: str | None = None,
    observations: str | None = None,
    move: bool = False,
    progress: ProgressFn | None = None,
) -> IngestResult:
    """Copie une source (fichier, dossier ou ZIP) dans le dossier d'enquête."""
    case.require_writable("import de fichiers")
    input_path = Path(input_path)
    if not input_path.exists():
        raise FileNotFoundError(f"Chemin introuvable : {input_path}")

    label = label or input_path.name
    skipped: list[str] = []
    extracted_archives = 0

    case.sources_dir.mkdir(parents=True, exist_ok=True)
    dest_root = _unique_dir(case.sources_dir, label)

    source_id = dbmod.insert(
        case.conn,
        "sources",
        {
            "case_id": case.case_id,
            "label": label,
            "kind": kind,
            "declared_origin": declared_origin,
            "declared_date": declared_date,
            "observations": observations,
            "imported_at": utcnow_iso(),
            "root_path": safe_relpath(dest_root, case.path),
            "status": "copying",
        },
    )
    case.conn.commit()

    # 1) copie integrale de l'original
    if input_path.is_dir():
        shutil.copytree(input_path, dest_root, dirs_exist_ok=True)
    else:
        shutil.copy2(input_path, dest_root / input_path.name)

    # 2) decompression des archives rencontrees (recursive, profondeur 3)
    for depth in range(3):
        archives = [p for p in _iter_files(dest_root) if p.suffix.lower() == ".zip"]
        pending = [a for a in archives if not (a.parent / (a.stem + "__extrait")).exists()]
        if not pending:
            break
        for archive in pending:
            target = archive.parent / (archive.stem + "__extrait")
            target.mkdir(parents=True, exist_ok=True)
            try:
                _safe_extract_zip(archive, target, skipped)
                extracted_archives += 1
            except zipfile.BadZipFile as exc:
                skipped.append(f"{archive.name} : archive illisible ({exc})")

    # 3) enregistrement + empreintes
    total_bytes = 0
    file_count = 0
    files = list(_iter_files(dest_root))
    for index, path in enumerate(files, start=1):
        try:
            register_file(case, source_id, path, original_path=str(input_path))
            total_bytes += path.stat().st_size
            file_count += 1
        except OSError as exc:
            skipped.append(f"{path.name} : {exc}")
        if progress and index % 200 == 0:
            progress("hash", index, len(files))
    case.conn.commit()

    case.conn.execute(
        "UPDATE sources SET file_count = ?, total_bytes = ?, status = 'imported' WHERE id = ?",
        (file_count, total_bytes, source_id),
    )
    case.conn.commit()
    case.audit("source_ingeree", f"{label} ({file_count} fichiers)")

    if move:
        try:
            if input_path.is_dir():
                shutil.rmtree(input_path)
            else:
                input_path.unlink()
        except OSError:
            pass

    return IngestResult(
        source_id=source_id,
        root_path=dest_root,
        file_count=file_count,
        total_bytes=total_bytes,
        extracted_archives=extracted_archives,
        skipped=skipped,
    )


def media_files_of(root: Path) -> list[Path]:
    return [p for p in _iter_files(root) if p.suffix.lower() in MEDIA_EXTENSIONS]
