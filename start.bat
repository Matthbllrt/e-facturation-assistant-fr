@echo off
REM ==========================================================================
REM  Instagram Evidence Recovery - Lancement (Windows)
REM  Demarre le service local et ouvre l'interface dans le navigateur.
REM  Fermez cette fenetre pour arreter le programme.
REM ==========================================================================
setlocal
cd /d "%~dp0"
title Instagram Evidence Recovery

if not exist ".venv\Scripts\python.exe" (
    echo.
    echo   [ERREUR] L'application n'est pas encore installee.
    echo   Lancez d'abord setup.bat, puis relancez start.bat.
    echo.
    pause
    exit /b 1
)

echo.
echo   ======================================================
echo    Instagram Evidence Recovery
echo    Traitement 100%% local - aucune donnee ne sort de ce PC
echo   ======================================================
echo.
echo   Le navigateur va s'ouvrir automatiquement.
echo   Si ce n'est pas le cas, ouvrez : http://127.0.0.1:8734/
echo.
echo   Pour arreter : fermez cette fenetre.
echo.

".venv\Scripts\python.exe" run.py --port 8734

if errorlevel 1 (
    echo.
    echo   Le programme s'est arrete avec une erreur.
    echo   Si le port 8734 est deja utilise, lancez :
    echo      .venv\Scripts\python.exe run.py --port 8750
    echo.
    pause
)
