# -*- mode: python ; coding: utf-8 -*-
"""Recette PyInstaller : un seul .exe autonome pour Windows.

Construction : build_exe.bat (ou `pyinstaller InstagramEvidenceRecovery.spec`).
L'interface (frontend/) est embarquee dans l'executable ; les dossiers
d'enquete restent en clair a cote de l'executable, dans « cases ».
"""
import os
import sys

block_cipher = None

hidden = [
    "uvicorn.logging",
    "uvicorn.loops",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols",
    "uvicorn.protocols.http",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan",
    "uvicorn.lifespan.on",
    "uvicorn.lifespan.off",
    "anyio._backends._asyncio",
    "reportlab.graphics.barcode",
    "reportlab.pdfbase._fontdata_enc_winansi",
    "reportlab.pdfbase._fontdata_widths_helvetica",
    "email.mime.multipart",
    "sqlite3",
]

# pypdf est optionnel : son absence n'empeche pas la construction.
try:
    import pypdf  # noqa: F401

    hidden.append("pypdf")
except BaseException:
    # Une installation pypdf cassee ne doit pas empecher la construction :
    # la lecture des PDF est une fonction optionnelle.
    pass

a = Analysis(
    ["run.py"],
    pathex=[os.path.abspath(".")],
    binaries=[],
    datas=[("frontend", "frontend")],
    hiddenimports=hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "numpy", "pandas", "pytest", "playwright"],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="InstagramEvidenceRecovery",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,          # la console affiche l'adresse locale et l'etat du service
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
