"""Application locale FastAPI : API + interface embarquee.

Le serveur n'ecoute que sur 127.0.0.1 : rien n'est expose sur le reseau.
"""
from __future__ import annotations

import webbrowser
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .api.routes import router
from .cases import CaseError, EvidenceModeError
from .config import APP_NAME, APP_VERSION, FRONTEND_DIR, data_root

app = FastAPI(
    title=APP_NAME,
    version=APP_VERSION,
    description=(
        "Outil local de récupération et de conservation de preuves Instagram. "
        "Toutes les données restent sur cette machine."
    ),
)

app.include_router(router)


@app.exception_handler(EvidenceModeError)
async def evidence_mode_handler(_request: Request, exc: EvidenceModeError):
    return JSONResponse(status_code=409, content={"detail": str(exc)})


@app.exception_handler(CaseError)
async def case_error_handler(_request: Request, exc: CaseError):
    return JSONResponse(status_code=400, content={"detail": str(exc)})


@app.middleware("http")
async def local_only(request: Request, call_next):
    """Refuse toute requête qui ne vient pas de la machine locale."""
    client = request.client.host if request.client else ""
    if client not in {"127.0.0.1", "::1", "localhost", "testclient", ""}:
        return JSONResponse(
            status_code=403,
            content={"detail": "Cet outil n'accepte que les connexions locales."},
        )
    return await call_next(request)


if FRONTEND_DIR.exists():
    app.mount("/ui", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="ui")


@app.get("/")
async def index():
    page = FRONTEND_DIR / "index.html"
    if page.exists():
        return FileResponse(str(page))
    return JSONResponse({"app": APP_NAME, "version": APP_VERSION, "api": "/api/health"})


def serve(host: str = "127.0.0.1", port: int = 8734, open_browser: bool = True) -> None:
    import uvicorn

    data_root()
    url = f"http://{host}:{port}/"
    print(f"\n  {APP_NAME} {APP_VERSION}")
    print(f"  Dossiers d'enquête : {data_root()}")
    print(f"  Interface : {url}")
    print("  Traitement 100 % local. Ferme cette fenêtre pour arreter le programme.\n")
    if open_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    uvicorn.run(app, host=host, port=port, log_level="warning")
