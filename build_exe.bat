@echo off
REM ==========================================================================
REM  Construction d'un executable Windows autonome (.exe)
REM  Necessite setup.bat au prealable. Resultat : dist\InstagramEvidenceRecovery.exe
REM ==========================================================================
setlocal
cd /d "%~dp0"
title Instagram Evidence Recovery - Construction de l'executable

if not exist ".venv\Scripts\python.exe" (
    echo   [ERREUR] Lancez d'abord setup.bat.
    pause
    exit /b 1
)

echo   Installation de PyInstaller...
".venv\Scripts\python.exe" -m pip install --upgrade pyinstaller --quiet
if errorlevel 1 (
    echo   [ERREUR] Installation de PyInstaller impossible.
    pause
    exit /b 1
)

echo   Construction de l'executable (5 a 10 minutes)...
".venv\Scripts\python.exe" -m PyInstaller InstagramEvidenceRecovery.spec --noconfirm --clean
if errorlevel 1 (
    echo   [ERREUR] La construction a echoue.
    pause
    exit /b 1
)

echo.
echo   ======================================================
echo    Termine : dist\InstagramEvidenceRecovery.exe
echo.
echo    Cet executable fonctionne sans installer Python.
echo    Les dossiers d'enquete sont crees dans un sous-dossier
echo    "cases" a cote de l'executable.
echo   ======================================================
echo.
pause
