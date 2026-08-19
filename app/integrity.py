"""Mode preuve et intégrité des fichiers (section 13).

Le manifeste fige l'empreinte SHA-256 de chaque fichier source au moment de
l'import. La vérification recalcule les empreintes et signalé toute
divergence : c'est ce qui permet de demontrer que les fichiers analysés n'ont
pas été modifiés après leur import.
"""
from __future__ import annotations

import json
import os
import stat
from pathlib import Path
from typing import Any

from . import db as dbmod
from .cases import Case
from .config import APP_NAME, APP_VERSION
from .utils import sha256_file, sha256_text, utcnow_iso


def build_manifest(case: Case) -> dict[str, Any]:
    """Construit le manifeste a partir des empreintes enregistrées a l'import."""
    rows = dbmod.query_all(
        case.conn,
        "SELECT f.rel_path, f.original_name, f.original_path, f.size_bytes, f.sha256, "
        "       f.file_mtime, f.imported_at, f.category, s.label AS source_label "
        "FROM files f LEFT JOIN sources s ON s.id = f.source_id "
        "WHERE f.case_id = ? ORDER BY f.rel_path",
        (case.case_id,),
    )
    entries = [
        {
            "nom_du_fichier": row["original_name"],
            "chemin": row["rel_path"],
            "chemin_origine": row["original_path"],
            "sha256": row["sha256"],
            "taille_octets": row["size_bytes"],
            "date_du_fichier": row["file_mtime"],
            "date_import": row["imported_at"],
            "categorie": row["category"],
            "source": row["source_label"],
        }
        for row in rows
    ]
    manifest = {
        "outil": APP_NAME,
        "version": APP_VERSION,
        "dossier": case.slug,
        "compte": f"@{case.username}",
        "genere_le": utcnow_iso(),
        "mode_preuve": case.evidence_mode,
        "algorithme": "SHA-256",
        "nombre_de_fichiers": len(entries),
        "taille_totale_octets": sum(int(e["taille_octets"] or 0) for e in entries),
        "fichiers": entries,
    }
    manifest["empreinte_du_manifeste"] = sha256_text(
        json.dumps(manifest["fichiers"], ensure_ascii=False, sort_keys=True)
    )
    return manifest


def write_manifest(case: Case) -> Path:
    manifest = build_manifest(case)
    case.manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    case.audit("manifest_ecrit", f"{manifest['nombre_de_fichiers']} fichiers")
    return case.manifest_path


def verify(case: Case, deep: bool = True) -> dict[str, Any]:
    """Recalcule les empreintes et compare avec celles enregistrées."""
    rows = dbmod.query_all(
        case.conn,
        "SELECT id, rel_path, sha256, size_bytes FROM files WHERE case_id = ? ORDER BY rel_path",
        (case.case_id,),
    )
    checked = 0
    missing: list[str] = []
    modified: list[dict] = []
    unreadable: list[dict] = []

    for row in rows:
        path = case.path / row["rel_path"]
        if not path.exists():
            missing.append(row["rel_path"])
            continue
        if not deep:
            checked += 1
            continue
        try:
            digest = sha256_file(path)
        except OSError as exc:
            unreadable.append({"chemin": row["rel_path"], "erreur": str(exc)})
            continue
        checked += 1
        if row["sha256"] and digest != row["sha256"]:
            modified.append(
                {
                    "chemin": row["rel_path"],
                    "sha256_import": row["sha256"],
                    "sha256_actuel": digest,
                    "taille_actuelle": path.stat().st_size,
                    "taille_import": row["size_bytes"],
                }
            )

    result = {
        "verifie_le": utcnow_iso(),
        "fichiers_enregistres": len(rows),
        "fichiers_verifies": checked,
        "fichiers_absents": missing,
        "fichiers_modifies": modified,
        "fichiers_illisibles": unreadable,
        "integrite_intacte": not missing and not modified and not unreadable,
        "profondeur": "empreintes recalculees" if deep else "presence des fichiers seulement",
    }
    case.audit(
        "verification_integrite",
        "intacte" if result["integrite_intacte"] else
        f"{len(modified)} modifie(s), {len(missing)} absent(s)",
    )
    return result


def _set_readonly(path: Path, readonly: bool) -> int:
    """Passe les fichiers d'un dossier en lecture seule (ou l'inverse)."""
    changed = 0
    for dirpath, _dirnames, filenames in os.walk(path):
        for name in filenames:
            target = Path(dirpath) / name
            try:
                mode = target.stat().st_mode
                if readonly:
                    new_mode = mode & ~stat.S_IWUSR & ~stat.S_IWGRP & ~stat.S_IWOTH
                else:
                    new_mode = mode | stat.S_IWUSR
                if new_mode != mode:
                    os.chmod(target, new_mode)
                    changed += 1
            except OSError:
                continue
    return changed


def enable_evidence_mode(case: Case) -> dict[str, Any]:
    """Active le mode preuve : fichiers sources en lecture seule + manifeste."""
    protected = _set_readonly(case.sources_dir, True)
    case.set_evidence_mode(True)
    manifest_path = write_manifest(case)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    return {
        "evidence_mode": True,
        "fichiers_proteges": protected,
        "manifest": str(manifest_path),
        "nombre_de_fichiers": manifest["nombre_de_fichiers"],
        "empreinte_du_manifeste": manifest["empreinte_du_manifeste"],
        "message": (
            "Mode preuve actif : les fichiers sources sont en lecture seule et "
            "aucun import ni suppression n'est possible tant qu'il est actif."
        ),
    }


def disable_evidence_mode(case: Case) -> dict[str, Any]:
    case.set_evidence_mode(False)
    restored = _set_readonly(case.sources_dir, False)
    return {
        "evidence_mode": False,
        "fichiers_liberes": restored,
        "message": (
            "Mode preuve désactivé : les imports sont de nouveau possibles. "
            "Le manifeste precedent reste conservé et peut être compare a tout moment."
        ),
    }
