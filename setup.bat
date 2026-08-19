@echo off
REM ==========================================================================
REM  Instagram Evidence Recovery - Installation (Windows)
REM  Cree un environnement Python isole et installe les dependances.
REM  A lancer une seule fois. Ensuite, utilisez start.bat.
REM ==========================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Instagram Evidence Recovery - Installation

echo.
echo   ======================================================
echo    Instagram Evidence Recovery - Installation
echo   ======================================================
echo.

REM --- 1. Recherche de Python ------------------------------------------------
set "PYCMD="
where py >nul 2>&1
if %errorlevel%==0 (
    py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3,9) else 1)" >nul 2>&1
    if !errorlevel!==0 set "PYCMD=py -3"
)
if not defined PYCMD (
    where python >nul 2>&1
    if %errorlevel%==0 (
        python -c "import sys; sys.exit(0 if sys.version_info >= (3,9) else 1)" >nul 2>&1
        if !errorlevel!==0 set "PYCMD=python"
    )
)

if not defined PYCMD (
    echo   [ERREUR] Python 3.9 ou superieur est introuvable sur cet ordinateur.
    echo.
    echo   Installez Python depuis https://www.python.org/downloads/windows/
    echo   IMPORTANT : cochez la case "Add Python to PATH" pendant l'installation,
    echo   puis relancez ce fichier setup.bat.
    echo.
    pause
    exit /b 1
)

echo   [1/4] Python detecte :
%PYCMD% --version
echo.

REM --- 2. Environnement virtuel ---------------------------------------------
if exist ".venv\Scripts\python.exe" (
    echo   [2/4] Environnement virtuel deja present.
) else (
    echo   [2/4] Creation de l'environnement virtuel...
    %PYCMD% -m venv .venv
    if errorlevel 1 (
        echo   [ERREUR] Impossible de creer l'environnement virtuel.
        pause
        exit /b 1
    )
)

REM --- 3. Dependances --------------------------------------------------------
echo   [3/4] Installation des dependances (cela peut prendre 1 a 3 minutes)...
".venv\Scripts\python.exe" -m pip install --upgrade pip --quiet
".venv\Scripts\python.exe" -m pip install -r requirements.txt --quiet
if errorlevel 1 (
    echo.
    echo   [ERREUR] L'installation des dependances a echoue.
    echo   Verifiez votre connexion Internet et relancez setup.bat.
    pause
    exit /b 1
)

REM --- 4. Verification -------------------------------------------------------
echo   [4/4] Verification de l'installation...
".venv\Scripts\python.exe" -c "import fastapi, uvicorn, reportlab; print('   Dependances OK')"
if errorlevel 1 (
    echo   [ERREUR] Verification echouee.
    pause
    exit /b 1
)

if not exist "cases" mkdir "cases"

echo.
echo   ======================================================
echo    Installation terminee.
echo.
echo    Lancez maintenant : start.bat
echo.
echo    Toutes vos donnees resteront dans le sous-dossier
echo    "cases" de ce repertoire. Rien n'est envoye sur
echo    Internet.
echo   ======================================================
echo.
pause
