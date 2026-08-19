"""Configuration globale de Instagram Evidence Recovery.

Tout est local. Aucune donnée n'est envoyée vers un service distant, a
l'exception de la collecte publique explicitement demandee par l'utilisateur
(section 2 du cahier des charges), qui interroge uniquement des URL publiques.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

APP_NAME = "Instagram Evidence Recovery"
APP_VERSION = "1.0.0"

# En executable PyInstaller, le code est extrait dans un dossier temporaire :
# l'interface embarquee s'y trouve, mais les donnees de l'utilisateur doivent
# rester a cote de l'executable, en clair et accessibles apres fermeture.
FROZEN = bool(getattr(sys, "frozen", False))

if FROZEN:
    BUNDLE_ROOT = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    PROJECT_ROOT = Path(sys.executable).resolve().parent
else:
    BUNDLE_ROOT = Path(__file__).resolve().parent.parent
    PROJECT_ROOT = BUNDLE_ROOT

# Racine des dossiers d'enquete. Surchargeable via IER_DATA_DIR (utile pour
# les tests et pour un deploiement sur un disque externe).
DATA_ROOT = Path(os.environ.get("IER_DATA_DIR", PROJECT_ROOT / "cases")).resolve()

FRONTEND_DIR = BUNDLE_ROOT / "frontend"

# Sous-dossiers standards d'un dossier d'enquete
CASE_SUBDIRS = (
    "sources",      # copies integrales des fichiers fournis par l'utilisateur
    "extracted",    # contenu decompresse des archives ZIP
    "reports",      # rapports generes
    "exports",      # exports CSV/JSON intermediaires
    "public",       # captures de donnees publiques
)

# Extensions considerees comme "media"
MEDIA_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".bmp", ".tiff",
    ".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v", ".3gp",
    ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".opus",
}

TEXTUAL_EXTENSIONS = {".json", ".html", ".htm", ".txt", ".csv", ".xml", ".md"}

# Taille max lue en memoire pour un fichier texte analyse (256 Mo).
MAX_TEXT_FILE_BYTES = 256 * 1024 * 1024

# Taille des blocs pour le hachage SHA-256
HASH_CHUNK_SIZE = 1024 * 1024

# Niveaux de confiance normalises (section 6 et 12)
CONFIDENCE_CONFIRMED = "CONFIRME"
CONFIDENCE_PROBABLE = "PROBABLE"
CONFIDENCE_POSSIBLE = "POSSIBLE"
CONFIDENCE_UNKNOWN = "INCONNU"

CONFIDENCE_ORDER = {
    CONFIDENCE_CONFIRMED: 0,
    CONFIDENCE_PROBABLE: 1,
    CONFIDENCE_POSSIBLE: 2,
    CONFIDENCE_UNKNOWN: 3,
}

# Libelles d'affichage. Les valeurs stockees en base restent en ASCII pour
# rester stables entre versions ; seul l'affichage est accentue.
CONFIDENCE_LABELS = {
    CONFIDENCE_CONFIRMED: "CONFIRMÉ",
    CONFIDENCE_PROBABLE: "PROBABLE",
    CONFIDENCE_POSSIBLE: "POSSIBLE",
    CONFIDENCE_UNKNOWN: "INCONNU",
}

# Valeur affichee lorsqu'aucune date reelle n'existe dans la source (section 7).
UNKNOWN_DATE_LABEL = "Date inconnue"


def data_root() -> Path:
    """Racine des dossiers d'enquête, créée si absente."""
    DATA_ROOT.mkdir(parents=True, exist_ok=True)
    return DATA_ROOT
