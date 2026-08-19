"""Tests d'analyse : lacunes, comparaison, chronologie, recherche, intégrité.

Ces tests vérifient la contrainte la plus importante de l'outil : aucune
information n'est inventée, et toute absence est présentée comme une absence.
"""
from __future__ import annotations

import pytest


# ---------------------------------------------------------------- lacunes
def test_les_quatre_types_de_lacunes_sont_detectes(loaded_case):
    from app.analysis.gaps import list_gaps

    types = {gap["gap_type"] for gap in list_gaps(loaded_case)}
    assert "message_retire" in types            # is_unsent dans la source
    assert "media_orphelin" in types            # photo sans message associé
    assert "fichier_serie_manquant" in types    # message_1.json absent
    assert "reaction_sans_cible" in types       # réaction sans contenu


def test_une_lacune_n_est_jamais_un_message(loaded_case):
    from app import db
    from app.analysis.gaps import list_gaps

    gaps = list_gaps(loaded_case)
    assert gaps
    for gap in gaps:
        assert gap["label"] == "ÉLÉMENT POTENTIELLEMENT MANQUANT"
        assert gap["confidence"] in {"CONFIRME", "PROBABLE", "POSSIBLE", "INCONNU"}
        assert gap["description"]

    # Aucun message n'a été créé pour combler une lacune.
    inventes = db.scalar(
        loaded_case.conn,
        "SELECT COUNT(*) FROM messages WHERE original_raw IS NULL OR original_raw = ''",
    )
    assert inventes == 0


def test_message_retire_confirme_par_la_source(loaded_case):
    from app.analysis.gaps import list_gaps

    retires = [g for g in list_gaps(loaded_case) if g["gap_type"] == "message_retire"]
    assert retires
    gap = retires[0]
    assert gap["confidence"] == "CONFIRME"
    assert "is_unsent" in (gap["indicator"] or "")
    assert "reconstituable" in gap["description"]


def test_silence_inhabituel_reste_possible(case):
    """Un long silence est un indice POSSIBLE, jamais une preuve de suppression."""
    from app import db
    from app.analysis.gaps import _detect_temporal_gaps, list_gaps
    from app.importers.messages import ParsedMessage, ParsedThread, store_thread

    base = 1_700_000_000_000
    messages = []
    for index in range(40):
        # 39 messages espacés d'une minute, puis un trou de 60 jours.
        offset = index * 60_000 if index < 39 else 39 * 60_000 + 60 * 86_400_000
        messages.append(
            ParsedMessage(
                sender="Amie X", timestamp_ms=base + offset, content=f"m{index}",
                msg_type="text", raw={"i": index},
            )
        )
    source_id = db.insert(
        case.conn,
        "sources",
        {"case_id": case.case_id, "label": "test", "kind": "instagram_export",
         "imported_at": "2025-01-01T00:00:00+00:00"},
    )
    thread = ParsedThread(
        external_id="inbox/test", title="Fil actif", participants=["Amie X", "Mon Compte"],
        messages=messages, raw_meta={}, source_file="test.json",
    )
    store_thread(case, source_id, thread, {"mon compte"})
    case.conn.commit()

    assert _detect_temporal_gaps(case, source_id) >= 1
    case.conn.commit()
    silences = [g for g in list_gaps(case) if g["gap_type"] == "silence_inhabituel"]
    assert silences
    assert silences[0]["confidence"] == "POSSIBLE"
    assert "aucune de ces hypothèses n'est établie" in silences[0]["description"].lower()


# ---------------------------------------------------------------- comparaison
def test_comparaison_de_deux_exports(loaded_case):
    from app.analysis.compare import compare_sources

    result = compare_sources(loaded_case, 1, 2)
    followers = result["relations"]["followers"]
    apparus = {entry["username"] for entry in followers["added"]}
    disparus = {entry["username"] for entry in followers["removed"]}

    assert apparus == {"nouveau_venu", "autre_nouveau"}
    assert disparus == {"compte_disparu"}
    assert result["conversations"]["added"][0]["title"] == "Nouveau Contact"
    assert result["summary"]["messages_added"] == 2
    assert result["summary"]["messages_removed"] == 1


def test_la_comparaison_borne_sans_inventer_de_date(loaded_case):
    from app.analysis.compare import compare_sources

    result = compare_sources(loaded_case, 1, 2)
    constat = result["relations"]["followers"]["added"][0]["statement"]
    assert "ajout intervenu entre ces deux dates" in constat
    assert "2024-11-14" in constat and "2025-10-27" in constat


def test_message_absent_du_second_export_signale_comme_lacune(loaded_case):
    from app.analysis.compare import compare_sources
    from app.analysis.gaps import list_gaps

    compare_sources(loaded_case, 1, 2)
    lacunes = [
        gap for gap in list_gaps(loaded_case)
        if gap["gap_type"] == "messages_absents_export_recent"
    ]
    assert lacunes
    assert lacunes[0]["confidence"] == "PROBABLE"
    assert "ne reconstitue rien" in lacunes[0]["description"]


def test_comparer_une_source_avec_elle_meme_ne_produit_rien(loaded_case):
    from app.analysis.compare import compare_sources

    result = compare_sources(loaded_case, 1, 1, persist=False)
    assert result["summary"]["relations_added"] == 0
    assert result["summary"]["messages_added"] == 0
    assert result["summary"]["messages_removed"] == 0


# ---------------------------------------------------------------- relations
def test_absence_de_date_reelle_affichee_comme_inconnue(loaded_case):
    from app import db

    ligne = db.query_one(
        loaded_case.conn,
        "SELECT timestamp_utc FROM following WHERE username = 'compte_prive_x' LIMIT 1",
    )
    assert ligne["timestamp_utc"] is None  # aucune date inventée depuis l'ordre du JSON


def test_les_dates_reelles_sont_conservees(loaded_case):
    from app import db

    ligne = db.query_one(
        loaded_case.conn,
        "SELECT timestamp_utc FROM followers WHERE username = 'amie_x' LIMIT 1",
    )
    assert ligne["timestamp_utc"] is not None


# ---------------------------------------------------------------- chronologie
def test_chronologie_source_et_confiance(loaded_case):
    from app.analysis.timeline import build

    timeline = build(loaded_case)
    assert timeline["days"]
    for day in timeline["days"]:
        for event in day["events"]:
            assert event["confidence"] in {"CONFIRME", "PROBABLE", "POSSIBLE", "INCONNU"}
            assert event["source"]


def test_evenements_non_dates_regroupes_avec_leur_fenetre(loaded_case):
    from app.analysis.compare import compare_sources
    from app.analysis.timeline import build

    compare_sources(loaded_case, 1, 2)
    timeline = build(loaded_case)
    non_dates = [e for e in timeline["undated"] if e["kind"] == "diff_ajout"]
    assert non_dates
    assert non_dates[0]["window_label"].startswith("entre ")
    assert non_dates[0]["date"] is None if "date" in non_dates[0] else True


# ---------------------------------------------------------------- recherche
def test_recherche_globale(loaded_case):
    from app.analysis.search import reindex, search

    reindex(loaded_case)
    resultat = search(loaded_case, "photos")
    assert resultat["count"] > 0
    types = {row["kind"] for row in resultat["results"]}
    assert "message" in types

    vide = search(loaded_case, "chaine_absolument_absente_xyz")
    assert vide["count"] == 0


def test_recherche_avec_filtres(loaded_case):
    from app.analysis.search import reindex, search

    reindex(loaded_case)
    resultat = search(loaded_case, "Salut", kinds=["message"])
    assert all(row["kind"] == "message" for row in resultat["results"])


def test_recherche_tolere_les_caracteres_speciaux(loaded_case):
    from app.analysis.search import reindex, search

    reindex(loaded_case)
    for terme in ['"', "*", "(a b)", "^^", "a:b"]:
        assert "results" in search(loaded_case, terme)


# ---------------------------------------------------------------- intégrité
def test_manifest_et_verification(loaded_case):
    from app.integrity import build_manifest, verify, write_manifest

    chemin = write_manifest(loaded_case)
    assert chemin.exists()
    manifest = build_manifest(loaded_case)
    assert manifest["nombre_de_fichiers"] > 0
    assert manifest["algorithme"] == "SHA-256"
    assert all(entry["sha256"] for entry in manifest["fichiers"])

    resultat = verify(loaded_case)
    assert resultat["integrite_intacte"] is True


def test_alteration_detectee(loaded_case):
    import os

    from app.integrity import enable_evidence_mode, verify

    enable_evidence_mode(loaded_case)
    cible = next(loaded_case.sources_dir.rglob("followers_1.json"))
    os.chmod(cible, 0o644)
    cible.write_text('{"altere": true}', encoding="utf-8")

    resultat = verify(loaded_case)
    assert resultat["integrite_intacte"] is False
    assert any("followers_1.json" in item["chemin"] for item in resultat["fichiers_modifies"])


def test_fichier_disparu_detecte(loaded_case):
    import os

    from app.integrity import verify

    cible = next(loaded_case.sources_dir.rglob("following.json"))
    os.chmod(cible, 0o644)
    cible.unlink()
    resultat = verify(loaded_case)
    assert resultat["integrite_intacte"] is False
    assert resultat["fichiers_absents"]


# ---------------------------------------------------------------- statistiques
def test_niveau_de_recuperation_et_checklist(loaded_case):
    from app.analysis.stats import checklist, recovery_stats

    stats = recovery_stats(loaded_case)
    assert stats["export_count"] == 2
    assert stats["can_compare"] is True
    assert stats["messages"] > 0
    assert stats["files_hashed"] == stats["files"]

    liste = checklist(loaded_case)
    assert 0 <= liste["progress"] <= 100
    labels = {item["label"] for item in liste["items"] if item["done"]}
    assert any("Export Instagram" in label for label in labels)
    assert any(not item["done"] for item in liste["items"])  # rien n'est complété d'office


def test_checklist_vide_sur_dossier_neuf(case):
    from app.analysis.stats import checklist

    liste = checklist(case)
    faits = [item for item in liste["items"] if item["done"]]
    assert len(faits) == 1  # seul le nom d'utilisateur est connu
    assert "Nom d'utilisateur connu" in faits[0]["label"]


# ---------------------------------------------------------------- public
def test_extraction_des_champs_publics_open_graph():
    from app.publicdata.collect import extract_public_fields

    html = (
        '<meta property="og:title" content="Mon Nom (@mon_compte) . Instagram">'
        '<meta property="og:description" content="1,234 Followers, 567 Following, 89 Posts - '
        'See Instagram photos and videos from Mon Nom (@mon_compte)">'
        '<meta property="og:image" content="https://exemple/pp.jpg">'
    )
    champs = extract_public_fields(html)
    assert champs["username"] == "mon_compte"
    assert champs["full_name"] == "Mon Nom"
    assert champs["followers_count"] == 1234
    assert champs["following_count"] == 567
    assert champs["posts_count"] == 89
    assert champs["profile_pic_url"].endswith("pp.jpg")


def test_mur_de_connexion_signale_sans_contournement():
    from app.publicdata.collect import extract_public_fields

    champs = extract_public_fields('<form id="loginForm">Log in to Instagram</form>')
    assert any("contournement" in note for note in champs["extraction_notes"])
    assert champs["followers_count"] is None


def test_collecte_manuelle(case):
    from app.publicdata.collect import add_manual, latest

    add_manual(
        case,
        content='<meta property="og:title" content="Nom (@mon_compte)">',
        url="https://www.instagram.com/mon_compte/",
        note="collée depuis le navigateur",
    )
    dernier = latest(case)
    assert dernier["method"] == "manual_html"
    assert dernier["full_name"] == "Nom"


# ---------------------------------------------------------------- Android
def test_chemins_android_interdits():
    from app.androidmod.adb import AdbError, check_forbidden

    for interdit in (
        "/data/data/com.instagram.android",
        "/sdcard/Android/data/com.instagram.android/cache",
        "/system/bin",
        "/data/user/0/com.instagram.android",
    ):
        with pytest.raises(AdbError):
            check_forbidden(interdit)

    for autorise in ("/sdcard/DCIM/Camera", "/sdcard/Download", "/sdcard/Pictures/Screenshots"):
        check_forbidden(autorise)
