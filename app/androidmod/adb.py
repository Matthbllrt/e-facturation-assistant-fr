"""Module Android optionnel (section 10).

Perimetre strictement limite au stockage PARTAGE de TON appareil, avec ton
autorisation explicite (débogage USB active et appareil autorisé) :
  /sdcard/DCIM, /sdcard/Pictures, /sdcard/Download, /sdcard/Movies,
  /sdcard/Documents, /sdcard/WhatsApp... c'est-a-dire les dossiers qu'un
  gestionnaire de fichiers ordinaire affiché déjà.

Ce module ne fait JAMAIS :
  * de rooting, ni automatique ni assiste ;
  * d'accès au sandbox privé d'Instagram (/data/data/com.instagram.android) ;
  * d'extraction des données privées d'autres applications ;
  * de contournement d'une protection Android.
Ces chemins sont explicitement refuses plus bas.
"""
from __future__ import annotations

import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..cases import Case

ADB_TIMEOUT = 120

# Dossiers publics proposes par defaut
PUBLIC_DIRS = [
    ("/sdcard/DCIM/Camera", "Pellicule photo"),
    ("/sdcard/Pictures/Instagram", "Images enregistrées par Instagram"),
    ("/sdcard/Pictures/Screenshots", "Captures d'ecran"),
    ("/sdcard/DCIM/Screenshots", "Captures d'écran (variante)"),
    ("/sdcard/Movies/Instagram", "Videos Instagram"),
    ("/sdcard/Download", "Telechargements (archives ZIP Instagram, PDF, e-mails)"),
    ("/sdcard/Documents", "Documents"),
    ("/sdcard/Android/media", "Médias publics d'applications"),
]

# Chemins interdits : sandbox applicatif et donnees systeme protegees
FORBIDDEN_PATTERNS = (
    re.compile(r"^/data(/|$)", re.I),
    re.compile(r"^/system(/|$)", re.I),
    re.compile(r"/Android/data(/|$)", re.I),
    re.compile(r"com\.instagram", re.I),
    re.compile(r"/dbdata(/|$)", re.I),
    re.compile(r"/data_mirror(/|$)", re.I),
)


class AdbError(Exception):
    pass


@dataclass
class AdbDevice:
    serial: str
    state: str
    model: str | None = None


def adb_path() -> str | None:
    return shutil.which("adb")


def is_available() -> bool:
    return adb_path() is not None


def _run(args: list[str], timeout: int = ADB_TIMEOUT) -> subprocess.CompletedProcess:
    binary = adb_path()
    if not binary:
        raise AdbError(
            "adb est introuvable. Installe « Android SDK Platform Tools » puis "
            "ajoute le dossier contenant adb.exe au PATH de Windows."
        )
    try:
        return subprocess.run(
            [binary, *args], capture_output=True, text=True, timeout=timeout, check=False
        )
    except subprocess.TimeoutExpired as exc:
        raise AdbError(f"La commande adb a dépassé le délai ({timeout}s).") from exc


def check_forbidden(remote_path: str) -> None:
    for pattern in FORBIDDEN_PATTERNS:
        if pattern.search(remote_path or ""):
            raise AdbError(
                f"Chemin refusé : « {remote_path} ». Cet outil n'accede qu'au stockage "
                "partage de l'appareil. Les données privées des applications "
                "(dont Instagram) ne sont pas accessibles sans contourner Android, "
                "ce que cet outil ne fait pas."
            )


def list_devices() -> list[AdbDevice]:
    result = _run(["devices", "-l"])
    devices: list[AdbDevice] = []
    for line in result.stdout.splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        serial, state = parts[0], parts[1] if len(parts) > 1 else "unknown"
        model = None
        for part in parts[2:]:
            if part.startswith("model:"):
                model = part.split(":", 1)[1]
        devices.append(AdbDevice(serial=serial, state=state, model=model))
    return devices


def scan_directory(remote_path: str, serial: str | None = None, limit: int = 500) -> dict[str, Any]:
    """Liste le contenu d'un dossier public de l'appareil (lecture seule)."""
    check_forbidden(remote_path)
    args = (["-s", serial] if serial else []) + [
        "shell",
        "ls",
        "-l",
        f"'{remote_path}'",
    ]
    result = _run(args)
    if result.returncode != 0 or "No such file" in (result.stderr + result.stdout):
        return {
            "path": remote_path,
            "exists": False,
            "entries": [],
            "message": result.stderr.strip() or result.stdout.strip() or "Dossier absent.",
        }
    entries = []
    for line in result.stdout.splitlines()[:limit]:
        line = line.strip()
        if not line or line.startswith("total "):
            continue
        parts = line.split(None, 7)
        if len(parts) < 8:
            entries.append({"raw": line, "name": parts[-1] if parts else line})
            continue
        entries.append(
            {
                "permissions": parts[0],
                "size": parts[4],
                "date": f"{parts[5]} {parts[6]}",
                "name": parts[7],
                "is_dir": parts[0].startswith("d"),
            }
        )
    return {"path": remote_path, "exists": True, "entries": entries, "count": len(entries)}


def scan_default_dirs(serial: str | None = None) -> list[dict[str, Any]]:
    out = []
    for path, label in PUBLIC_DIRS:
        try:
            info = scan_directory(path, serial=serial, limit=50)
        except AdbError as exc:
            info = {"path": path, "exists": False, "entries": [], "message": str(exc)}
        info["label"] = label
        out.append(info)
    return out


def pull_to_case(
    case: Case,
    remote_path: str,
    *,
    serial: str | None = None,
    label: str | None = None,
    observations: str | None = None,
) -> dict[str, Any]:
    """Copie un dossier public de l'appareil dans le dossier d'enquête."""
    case.require_writable("import Android")
    check_forbidden(remote_path)

    staging = case.path / "_adb_staging"
    staging.mkdir(parents=True, exist_ok=True)
    target = staging / Path(remote_path.rstrip("/")).name
    if target.exists():
        shutil.rmtree(target, ignore_errors=True)

    args = (["-s", serial] if serial else []) + ["pull", remote_path, str(target)]
    result = _run(args, timeout=1800)
    if result.returncode != 0 and not target.exists():
        shutil.rmtree(staging, ignore_errors=True)
        raise AdbError(
            f"Échec de la copie depuis l'appareil : {result.stderr.strip() or result.stdout.strip()}"
        )

    from ..sources_local import add_local_source

    try:
        info = add_local_source(
            case,
            target,
            label=label or f"Android {remote_path}",
            declared_origin=f"Appareil Android (adb pull {remote_path})"
            + (f" — serie {serial}" if serial else ""),
            observations=observations,
            kind="android",
        )
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    info["remote_path"] = remote_path
    info["adb_output"] = (result.stdout or result.stderr or "").strip()[-2000:]
    case.audit("import_android", remote_path)
    return info


def status() -> dict[str, Any]:
    if not is_available():
        return {
            "adb_installed": False,
            "devices": [],
            "message": (
                "adb n'est pas installé. Le module Android est optionnel : "
                "tu peux aussi brancher le téléphone en mode « transfert de fichiers » "
                "et ajouter les dossiers directement depuis l'onglet Sources."
            ),
        }
    try:
        devices = list_devices()
    except AdbError as exc:
        return {"adb_installed": True, "devices": [], "message": str(exc)}
    return {
        "adb_installed": True,
        "devices": [d.__dict__ for d in devices],
        "authorized": [d.__dict__ for d in devices if d.state == "device"],
        "unauthorized": [d.__dict__ for d in devices if d.state != "device"],
        "message": (
            "Aucun appareil détecté. Active le débogage USB dans les options "
            "développeur, branche le téléphone, puis accepte la demande "
            "d'autorisation affichée a l'écran."
            if not devices
            else f"{len(devices)} appareil(s) detecte(s)."
        ),
    }
