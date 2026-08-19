"""Utilitaires transverses : hachage, dates, encodage, chemins surs."""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
import mimetypes
import re
import unicodedata
from pathlib import Path
from typing import Any

from .config import HASH_CHUNK_SIZE, MEDIA_EXTENSIONS

_USERNAME_RE = re.compile(r"^[A-Za-z0-9._]{1,30}$")
_SLUG_RE = re.compile(r"[^a-z0-9._-]+")


# --------------------------------------------------------------------------
# Temps
# --------------------------------------------------------------------------
def utcnow_iso() -> str:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat()


def ms_to_iso(ms: int | float | None) -> str | None:
    """Convertit un epoch (ms ou s) en ISO 8601 UTC. None si non convertible."""
    if ms is None:
        return None
    try:
        value = float(ms)
    except (TypeError, ValueError):
        return None
    if value <= 0:
        return None
    # Heuristique robuste : microsecondes > millisecondes > secondes
    if value > 1e14:
        value = value / 1_000_000.0
    elif value > 1e11:
        value = value / 1000.0
    if value > 4e10:  # au-dela de l'an ~3238, valeur non exploitable
        return None
    try:
        return (
            _dt.datetime.fromtimestamp(value, _dt.timezone.utc)
            .replace(microsecond=0)
            .isoformat()
        )
    except (OverflowError, OSError, ValueError):
        return None


def normalize_epoch_ms(value: Any) -> int | None:
    """Ramene un epoch quelconque (s, ms, us) en millisecondes."""
    if value is None or isinstance(value, bool):
        return None
    try:
        num = float(value)
    except (TypeError, ValueError):
        return None
    if num <= 0:
        return None
    if num > 1e14:
        num = num / 1000.0
    elif num > 1e11:
        pass  # deja en ms
    else:
        num = num * 1000.0
    if num > 4e13:
        return None
    return int(num)


_DATE_PATTERNS = (
    "%Y-%m-%dT%H:%M:%S%z",
    "%Y-%m-%dT%H:%M:%S.%f%z",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d",
    "%d/%m/%Y %H:%M:%S",
    "%d/%m/%Y %H:%M",
    "%d/%m/%Y",
    "%m/%d/%Y %H:%M",
    "%b %d, %Y %I:%M:%S %p",
    "%b %d, %Y, %I:%M:%S %p",
    "%b %d, %Y, %I:%M %p",
    "%b %d, %Y %I:%M %p",
    "%b %d, %Y, %H:%M:%S",
    "%b %d, %Y, %H:%M",
    "%b %d, %Y %H:%M",
    "%B %d, %Y %I:%M:%S %p",
    "%B %d, %Y, %I:%M %p",
    "%B %d, %Y, %H:%M",
    "%b %d, %Y",
    "%d %b %Y, %H:%M",
    "%Y:%m:%d %H:%M:%S",
)


def parse_datetime(value: Any) -> str | None:
    """Analyse une date textuelle ou numerique. Retourne un ISO UTC ou None.

    Ne devine jamais : si la chaine n'est pas reconnue, retourne None afin que
    l'appelant affiché « Date inconnue ».
    """
    if value is None:
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return ms_to_iso(value)
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    if text.isdigit():
        return ms_to_iso(int(text))
    cleaned = text.replace("Z", "+0000")
    for pattern in _DATE_PATTERNS:
        try:
            dt = _dt.datetime.strptime(cleaned, pattern)
        except ValueError:
            continue
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=_dt.timezone.utc)
        return dt.astimezone(_dt.timezone.utc).replace(microsecond=0).isoformat()
    try:
        dt = _dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=_dt.timezone.utc)
        return dt.astimezone(_dt.timezone.utc).replace(microsecond=0).isoformat()
    except ValueError:
        return None


def iso_to_ms(iso: str | None) -> int | None:
    if not iso:
        return None
    try:
        dt = _dt.datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=_dt.timezone.utc)
    return int(dt.timestamp() * 1000)


def file_mtime_iso(path: Path) -> str | None:
    try:
        return (
            _dt.datetime.fromtimestamp(path.stat().st_mtime, _dt.timezone.utc)
            .replace(microsecond=0)
            .isoformat()
        )
    except OSError:
        return None


# --------------------------------------------------------------------------
# Encodage : les exports Instagram JSON sont encodes en UTF-8 puis re-echappes
# en latin-1 (mojibake classique : "Ã©" au lieu de "e accent aigu").
# --------------------------------------------------------------------------
def fix_meta_mojibake(text: Any) -> Any:
    if not isinstance(text, str) or not text:
        return text
    try:
        repaired = text.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return text
    # On ne conserve la reparation que si elle supprime des sequences suspectes
    if repaired == text:
        return text
    suspicious = ("Ã", "Â", "â\x80", "ð\x9f", "å", "ì")
    if any(marker in text for marker in suspicious):
        return repaired
    return text


def deep_fix_mojibake(value: Any) -> Any:
    if isinstance(value, str):
        return fix_meta_mojibake(value)
    if isinstance(value, list):
        return [deep_fix_mojibake(v) for v in value]
    if isinstance(value, dict):
        return {k: deep_fix_mojibake(v) for k, v in value.items()}
    return value


# --------------------------------------------------------------------------
# Hachage / fichiers
# --------------------------------------------------------------------------
def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(HASH_CHUNK_SIZE)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def guess_mime(path: Path) -> str:
    mime, _ = mimetypes.guess_type(str(path))
    return mime or "application/octet-stream"


def media_kind(path: Path | str) -> str | None:
    ext = Path(str(path)).suffix.lower()
    if ext not in MEDIA_EXTENSIONS:
        return None
    if ext in {".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v", ".3gp"}:
        return "video"
    if ext in {".mp3", ".m4a", ".aac", ".wav", ".ogg", ".opus"}:
        return "audio"
    return "image"


def read_text_file(path: Path, limit: int | None = None) -> str:
    """Lit un fichier texte en tolerant les encodages inhabituels."""
    raw = path.read_bytes() if limit is None else path.open("rb").read(limit)
    for encoding in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def load_json_file(path: Path) -> Any:
    text = read_text_file(path)
    return json.loads(text)


# --------------------------------------------------------------------------
# Noms / chemins
# --------------------------------------------------------------------------
def clean_username(raw: str) -> str:
    """Nettoie « @user », « instagram.com/user/ » -> « user »."""
    value = (raw or "").strip()
    value = value.replace("\\", "/")
    if "instagram.com" in value:
        value = value.split("instagram.com", 1)[1]
    value = value.strip("/@ \t")
    value = value.split("/")[0].split("?")[0]
    return value.strip().lower()


def is_valid_username(username: str) -> bool:
    return bool(_USERNAME_RE.match(username or ""))


def slugify(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value or "")
    ascii_text = normalized.encode("ascii", "ignore").decode("ascii").lower()
    slug = _SLUG_RE.sub("-", ascii_text).strip("-._")
    return slug or "dossier"


def safe_relpath(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.name


def is_within(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def human_size(num_bytes: int | None) -> str:
    if not num_bytes:
        return "0 o"
    units = ["o", "Ko", "Mo", "Go", "To"]
    value = float(num_bytes)
    for unit in units:
        if value < 1024 or unit == units[-1]:
            return f"{value:.0f} {unit}" if unit == "o" else f"{value:.1f} {unit}"
        value /= 1024
    return f"{value:.1f} To"


def truncate(text: str | None, length: int = 160) -> str:
    if not text:
        return ""
    text = " ".join(text.split())
    return text if len(text) <= length else text[: length - 1] + "…"
