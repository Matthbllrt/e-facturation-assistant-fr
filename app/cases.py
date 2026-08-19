"""Gestion des dossiers d'enquête.

Un dossier d'enquête = cases/<slug>/ contenant :
  case.json      description lisible du dossier
  case.db        base SQLite locale
  manifest.json  empreintes SHA-256 (mode preuve)
  sources/       copies integrales des fichiers fournis
  extracted/     contenu décompressé des archives
  reports/       rapports générés
  public/        collectes publiques
"""
from __future__ import annotations

import json
import shutil
import sqlite3
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import db as dbmod
from .config import CASE_SUBDIRS, APP_VERSION, data_root
from .utils import clean_username, is_valid_username, slugify, utcnow_iso


class CaseError(Exception):
    """Erreur fonctionnelle sur un dossier d'enquête."""


class EvidenceModeError(CaseError):
    """Action refusée car le mode preuve est actif."""


@dataclass
class Case:
    slug: str
    username: str
    path: Path
    created_at: str
    display_name: str | None = None
    evidence_mode: bool = False
    notes: str | None = None
    _conn: sqlite3.Connection | None = field(default=None, repr=False, compare=False)

    # -- chemins ----------------------------------------------------------
    @property
    def db_path(self) -> Path:
        return self.path / "case.db"

    @property
    def sources_dir(self) -> Path:
        return self.path / "sources"

    @property
    def extracted_dir(self) -> Path:
        return self.path / "extracted"

    @property
    def reports_dir(self) -> Path:
        return self.path / "reports"

    @property
    def public_dir(self) -> Path:
        return self.path / "public"

    @property
    def manifest_path(self) -> Path:
        return self.path / "manifest.json"

    @property
    def meta_path(self) -> Path:
        return self.path / "case.json"

    # -- base ------------------------------------------------------------
    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            self._conn = dbmod.connect(self.db_path)
        return self._conn

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            finally:
                self._conn = None

    @property
    def case_id(self) -> int:
        row = dbmod.query_one(self.conn, "SELECT id FROM cases WHERE slug = ?", (self.slug,))
        if not row:
            raise CaseError(f"Dossier introuvable en base : {self.slug}")
        return int(row["id"])

    # -- mode preuve -------------------------------------------------------
    def require_writable(self, action: str) -> None:
        """Empeche toute modification des données originales en mode preuve."""
        if self.evidence_mode:
            raise EvidenceModeError(
                f"Mode preuve actif : l'action « {action} » est bloquée. "
                "Desactivez le mode preuve pour modifier le dossier."
            )

    def set_evidence_mode(self, enabled: bool) -> None:
        self.evidence_mode = bool(enabled)
        self.conn.execute(
            "UPDATE cases SET evidence_mode = ? WHERE slug = ?",
            (1 if enabled else 0, self.slug),
        )
        self.conn.commit()
        self.write_meta()
        self.audit("evidence_mode", "active" if enabled else "desactive")

    def audit(self, action: str, detail: str | None = None) -> None:
        try:
            dbmod.insert(
                self.conn,
                "audit_log",
                {
                    "case_id": self.case_id,
                    "at": utcnow_iso(),
                    "action": action,
                    "detail": detail,
                },
            )
            self.conn.commit()
        except sqlite3.Error:
            pass

    # -- meta --------------------------------------------------------------
    def to_dict(self) -> dict[str, Any]:
        return {
            "slug": self.slug,
            "username": self.username,
            "display_name": self.display_name,
            "path": str(self.path),
            "created_at": self.created_at,
            "evidence_mode": self.evidence_mode,
            "notes": self.notes,
            "app_version": APP_VERSION,
        }

    def write_meta(self) -> None:
        self.meta_path.write_text(
            json.dumps(self.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def ensure_dirs(self) -> None:
        for sub in CASE_SUBDIRS:
            (self.path / sub).mkdir(parents=True, exist_ok=True)


_REGISTRY: dict[str, Case] = {}
_REGISTRY_LOCK = threading.RLock()


def create_case(username_raw: str, notes: str | None = None) -> Case:
    """Créé le dossier d'enquête pour @username."""
    username = clean_username(username_raw)
    if not username:
        raise CaseError("Nom d'utilisateur vide.")
    if not is_valid_username(username):
        raise CaseError(
            "Nom d'utilisateur Instagram invalide. Caracteres autorisés : "
            "lettres, chiffres, point et tiret bas (30 max)."
        )
    slug = slugify(username)
    root = data_root() / slug
    if (root / "case.json").exists():
        return open_case(slug)

    root.mkdir(parents=True, exist_ok=True)
    case = Case(
        slug=slug,
        username=username,
        path=root,
        created_at=utcnow_iso(),
        evidence_mode=False,
        notes=notes,
    )
    case.ensure_dirs()
    dbmod.init_schema(case.conn)
    dbmod.insert(
        case.conn,
        "cases",
        {
            "slug": slug,
            "username": username,
            "display_name": None,
            "created_at": case.created_at,
            "notes": notes,
            "evidence_mode": 0,
        },
    )
    case.conn.commit()
    case.write_meta()
    case.audit("case_created", f"@{username}")
    with _REGISTRY_LOCK:
        _REGISTRY[slug] = case
    return case


def open_case(slug: str) -> Case:
    slug = slugify(slug)
    with _REGISTRY_LOCK:
        cached = _REGISTRY.get(slug)
        if cached is not None and cached.meta_path.exists():
            return cached

    root = data_root() / slug
    meta_file = root / "case.json"
    if not meta_file.exists():
        raise CaseError(f"Dossier d'enquête introuvable : {slug}")
    meta = json.loads(meta_file.read_text(encoding="utf-8"))
    case = Case(
        slug=meta.get("slug", slug),
        username=meta.get("username", slug),
        path=root,
        created_at=meta.get("created_at", utcnow_iso()),
        display_name=meta.get("display_name"),
        evidence_mode=bool(meta.get("evidence_mode", False)),
        notes=meta.get("notes"),
    )
    case.ensure_dirs()
    dbmod.init_schema(case.conn)
    row = dbmod.query_one(case.conn, "SELECT * FROM cases WHERE slug = ?", (case.slug,))
    if row is None:
        dbmod.insert(
            case.conn,
            "cases",
            {
                "slug": case.slug,
                "username": case.username,
                "display_name": case.display_name,
                "created_at": case.created_at,
                "notes": case.notes,
                "evidence_mode": 1 if case.evidence_mode else 0,
            },
        )
        case.conn.commit()
    else:
        case.evidence_mode = bool(row["evidence_mode"])
    with _REGISTRY_LOCK:
        _REGISTRY[case.slug] = case
    return case


def list_cases() -> list[dict[str, Any]]:
    root = data_root()
    out: list[dict[str, Any]] = []
    for child in sorted(root.iterdir()) if root.exists() else []:
        meta_file = child / "case.json"
        if not (child.is_dir() and meta_file.exists()):
            continue
        try:
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        meta["path"] = str(child)
        out.append(meta)
    out.sort(key=lambda m: m.get("created_at") or "", reverse=True)
    return out


def delete_case(slug: str) -> None:
    """Supprime un dossier d'enquête. Refuse si le mode preuve est actif."""
    case = open_case(slug)
    case.require_writable("suppression du dossier")
    case.close()
    with _REGISTRY_LOCK:
        _REGISTRY.pop(case.slug, None)
    shutil.rmtree(case.path, ignore_errors=True)
