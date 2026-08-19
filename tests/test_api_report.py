"""Tests de l'API locale, des sources locales et de la génération du rapport."""
from __future__ import annotations

import json
import time

import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def client(data_dir):
    from app.main import app

    return TestClient(app)


def _attendre(client, job_id, timeout=90):
    limite = time.time() + timeout
    while time.time() < limite:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in {"termine", "erreur"}:
            return job
        time.sleep(0.2)
    raise AssertionError("La tâche n'a pas abouti dans le délai imparti.")


# ---------------------------------------------------------------- API
def test_sante_et_cycle_de_vie_du_dossier(client):
    sante = client.get("/api/health").json()
    assert sante["local_only"] is True

    assert client.get("/api/cases").json() == []

    cree = client.post("/api/cases", json={"username": "@Mon_Compte"})
    assert cree.status_code == 200
    assert cree.json()["slug"] == "mon_compte"

    assert client.post("/api/cases", json={"username": "nom invalide"}).status_code == 400
    assert client.get("/api/cases/inconnu").status_code == 404
    assert len(client.get("/api/cases").json()) == 1


def test_import_par_chemin_puis_consultation(client, exports):
    export_a, export_b = exports
    client.post("/api/cases", json={"username": "mon_compte"})

    job = client.post(
        "/api/cases/mon_compte/import/path",
        json={"path": str(export_a), "label": "Export 2024", "declared_date": "2024-11-14"},
    ).json()
    fini = _attendre(client, job["id"])
    assert fini["status"] == "termine", fini.get("error")
    assert fini["result"]["messages"] == 9

    job2 = client.post(
        "/api/cases/mon_compte/import/path",
        json={"path": str(export_b), "label": "Export 2025", "declared_date": "2025-10-27"},
    ).json()
    assert _attendre(client, job2["id"])["status"] == "termine"

    conversations = client.get("/api/cases/mon_compte/conversations").json()
    assert len(conversations) == 3
    fil = next(c for c in conversations if c["title"] == "Amie X")
    assert fil["message_count"] == 8
    assert fil["source_count"] == 2

    messages = client.get(
        "/api/cases/mon_compte/conversations/messages",
        params={"group_key": fil["group_key"]},
    ).json()
    assert messages["count"] == 8
    assert any(m["type"] == "unsent" for m in messages["messages"])

    brut = client.get(f"/api/cases/mon_compte/messages/{messages['messages'][0]['id']}/raw").json()
    assert "donnee_originale" in brut and brut["donnee_originale"]

    # comparaison
    comparaison = client.post(
        "/api/cases/mon_compte/compare", json={"source_a": 1, "source_b": 2}
    ).json()
    assert comparaison["summary"]["relations_added"] == 3

    # filtres de relations
    tri = client.get(
        "/api/cases/mon_compte/relations", params={"kind": "followers", "sort": "alpha"}
    ).json()
    noms = [entry["username"] for entry in tri["entries"]]
    assert noms == sorted(noms)

    attente = client.get(
        "/api/cases/mon_compte/relations", params={"kind": "pending_sent"}
    ).json()
    assert attente["entries"][0]["date_label"] == "Date inconnue"
    assert attente["sans_date_reelle"] == attente["count"]

    # recherche, chronologie, lacunes
    assert client.get("/api/cases/mon_compte/search", params={"q": "photos"}).json()["count"] > 0
    assert client.get("/api/cases/mon_compte/timeline").json()["days"]
    assert client.get("/api/cases/mon_compte/gaps").json()["count"] >= 4


def test_import_par_televersement(client, exports, tmp_path):
    import fixtures

    export_a, _ = exports
    archive = fixtures.zip_export(export_a, tmp_path / "export.zip")
    client.post("/api/cases", json={"username": "mon_compte"})

    with open(archive, "rb") as handle:
        job = client.post(
            "/api/cases/mon_compte/import/upload",
            files={"file": ("export.zip", handle, "application/zip")},
            params={"label": "Export televerse"},
        ).json()
    fini = _attendre(client, job["id"])
    assert fini["status"] == "termine", fini.get("error")
    assert fini["result"]["conversations"] == 2


def test_chemin_inexistant_refuse(client):
    client.post("/api/cases", json={"username": "mon_compte"})
    reponse = client.post(
        "/api/cases/mon_compte/import/path", json={"path": "/chemin/qui/n/existe/pas"}
    )
    assert reponse.status_code == 400


def test_le_service_refuse_de_servir_un_fichier_hors_du_dossier(client, exports):
    export_a, _ = exports
    client.post("/api/cases", json={"username": "mon_compte"})
    job = client.post(
        "/api/cases/mon_compte/import/path", json={"path": str(export_a), "label": "E"}
    ).json()
    _attendre(client, job["id"])

    for tentative in ("../../../etc/passwd", "/etc/passwd", "sources/../../../etc/hosts"):
        reponse = client.get("/api/cases/mon_compte/file", params={"rel_path": tentative})
        assert reponse.status_code == 404


def test_mode_preuve_via_api(client, exports):
    export_a, _ = exports
    client.post("/api/cases", json={"username": "mon_compte"})
    job = client.post(
        "/api/cases/mon_compte/import/path", json={"path": str(export_a), "label": "E"}
    ).json()
    _attendre(client, job["id"])

    active = client.post("/api/cases/mon_compte/evidence-mode", json={"enabled": True}).json()
    assert active["evidence_mode"] is True
    assert active["nombre_de_fichiers"] > 0

    bloque = client.post(
        "/api/cases/mon_compte/import/path", json={"path": str(export_a), "label": "Encore"}
    )
    assert bloque.status_code == 409

    verification = client.get("/api/cases/mon_compte/integrity").json()
    assert verification["integrite_intacte"] is True

    client.post("/api/cases/mon_compte/evidence-mode", json={"enabled": False})
    assert client.get("/api/cases/mon_compte").json()["evidence_mode"] is False


def test_notes_et_sources_locales(client, tmp_path):
    client.post("/api/cases", json={"username": "mon_compte"})

    dossier = tmp_path / "captures"
    dossier.mkdir()
    (dossier / "capture.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00" * 40)
    (dossier / "mail.txt").write_text("Objet : nouvelle connexion detectee", encoding="utf-8")

    job = client.post(
        "/api/cases/mon_compte/import/path",
        json={"path": str(dossier), "kind": "local_files", "label": "Ancien telephone",
              "declared_origin": "carte SD", "declared_date": "2019-05-12"},
    ).json()
    assert _attendre(client, job["id"])["status"] == "termine"

    client.post(
        "/api/cases/mon_compte/evidence/note",
        json={"label": "Souvenir", "observations": "Compte desactive vers mai 2019",
              "declared_date": "2019-05-20"},
    )
    preuves = client.get("/api/cases/mon_compte/evidence").json()
    assert len(preuves) == 3
    assert any(p["evidence_type"] == "note" for p in preuves)
    assert any(p["sha256"] for p in preuves if p["evidence_type"] != "note")

    trouve = client.get("/api/cases/mon_compte/search", params={"q": "connexion"}).json()
    assert trouve["count"] > 0


# ---------------------------------------------------------------- rapport
def test_rapport_complet(loaded_case):
    from app.reporting.builder import build_report
    from app.reporting.render import generate_all

    rapport = build_report(loaded_case)
    for section in (
        "compte", "periode", "sources", "integrite", "conversations", "chronologie",
        "abonnes", "abonnements", "changements", "medias", "elements_manquants", "limites",
    ):
        assert section in rapport

    produit = generate_all(loaded_case, rapport)
    from pathlib import Path

    html = Path(produit["fichiers"]["html"])
    assert html.exists() and html.stat().st_size > 5000
    texte = html.read_text(encoding="utf-8")
    for attendu in (
        "1. Compte concerné", "2. Période étudiée", "3. Sources utilisées",
        "4. Intégrité des fichiers", "5. Conversations retrouvées", "6. Chronologie",
        "7. Abonnés", "8. Abonnements", "9. Changements détectés", "10. Médias",
        "11. Éléments potentiellement manquants", "12. Limites de la récupération",
        "ÉLÉMENT POTENTIELLEMENT MANQUANT", "Date inconnue", "SOURCE :",
    ):
        assert attendu in texte, attendu

    assert Path(produit["fichiers"]["json"]).exists()
    assert Path(produit["fichiers"]["zip"]).exists()
    noms_csv = {Path(p).name for p in produit["fichiers"]["csv"]}
    assert {"abonnes.csv", "chronologie.csv", "elements_manquants.csv", "messages.csv"} <= noms_csv

    pdf = produit["fichiers"].get("pdf")
    if pdf:
        assert Path(pdf).stat().st_size > 3000
    else:
        assert produit["avertissements"]


def test_csv_indique_date_inconnue(loaded_case):
    import csv
    from pathlib import Path

    from app.reporting.builder import build_report
    from app.reporting.render import render_csv_files

    rapport = build_report(loaded_case, deep_verify=False)
    dossier = Path(loaded_case.path) / "exports" / "test_csv"
    render_csv_files(rapport, dossier)

    with open(dossier / "autres_relations.csv", encoding="utf-8-sig") as handle:
        lignes = list(csv.reader(handle, delimiter=";"))
    entetes = lignes[0]
    index_date = entetes.index("date_dans_la_source")
    valeurs = [ligne[index_date] for ligne in lignes[1:]]
    assert "Date inconnue" in valeurs


def test_apercu_du_rapport_et_limites(loaded_case):
    from app.reporting.builder import build_report, preview

    apercu = preview(build_report(loaded_case, deep_verify=False))
    assert apercu["resume"]
    assert any("Limites" not in ligne for ligne in apercu["resume"])
    assert len(apercu["limites"]) >= 5
    assert any("UTC" in ligne for ligne in apercu["limites"])


# ---------------------------------------------------------------- PDF source
def test_extraction_de_texte_pdf(case, tmp_path):
    """Un PDF fourni comme preuve devient interrogeable par la recherche."""
    pytest.importorskip("pypdf")
    reportlab_canvas = pytest.importorskip("reportlab.pdfgen.canvas")

    from app import sources_local
    from app.analysis.search import search

    pdf = tmp_path / "preuve.pdf"
    document = reportlab_canvas.Canvas(str(pdf))
    document.drawString(70, 750, "Notification Instagram du 12 mai 2019")
    document.drawString(70, 730, "Connexion depuis un nouvel appareil")
    document.save()

    resultat = sources_local.add_local_source(
        case, pdf, label="Notification PDF", declared_origin="boite e-mail archivee"
    )
    assert resultat["files"] == 1

    preuves = sources_local.list_evidence(case)
    assert preuves[0]["evidence_type"] == "pdf"
    assert "Notification Instagram" in preuves[0]["extract_preview"]

    trouve = search(case, "nouvel appareil")
    assert trouve["count"] >= 1


def test_image_sans_ocr_est_signalee(case, tmp_path):
    """Aucune reconnaissance de texte n'est effectuee : l'outil le dit."""
    from app import sources_local

    image = tmp_path / "capture.png"
    image.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)
    resultat = sources_local.add_local_source(case, image, label="Capture")
    assert any("reconnaissance de caract" in note for note in resultat["extraction_notes"])
