"""Tests d'import et d'analyse d'exports Instagram (ZIP, dossier, JSON, HTML)."""
from __future__ import annotations

import json

import fixtures


def test_import_zip_complet(case, exports, tmp_path):
    from app import db
    from app.importers.pipeline import import_and_analyze

    export_a, _ = exports
    archive = fixtures.zip_export(export_a, tmp_path / "instagram.zip")

    _result, report = import_and_analyze(case, archive, label="Export 2024", declared_date="2024-11-14")

    assert report.conversations == 2
    assert report.messages == 9
    assert report.profile_found is True
    assert report.relations["followers"] == 4
    assert report.relations["following"] == 3
    assert report.relations["pending_sent"] == 1
    assert report.relations["blocked"] == 1
    assert report.unknown_files == 0
    assert report.warnings == []

    # Tous les fichiers copiés sont hachés.
    total, hachés = db.query_one(
        case.conn,
        "SELECT COUNT(*) AS total, SUM(sha256 IS NOT NULL) AS haches FROM files WHERE case_id = ?",
        (case.case_id,),
    ).values()
    assert total == hachés and total > 0


def test_import_dossier_non_compresse(case, exports):
    from app.importers.pipeline import import_and_analyze

    _export_a, export_b = exports
    _result, report = import_and_analyze(case, export_b, label="Export 2025")
    assert report.conversations == 3
    assert report.messages == 10


def test_import_html(case, tmp_path):
    from app import db
    from app.importers.pipeline import import_and_analyze

    source = fixtures.build_export_html(tmp_path / "export_html")
    _result, report = import_and_analyze(case, source, label="Export HTML")

    assert report.conversations == 1
    assert report.messages == 3
    assert report.relations["followers"] == 2

    messages = db.query_all(
        case.conn,
        "SELECT sender, content, timestamp_utc, media_path FROM messages "
        "WHERE case_id = ? ORDER BY timestamp_ms",
        (case.case_id,),
    )
    assert messages[0]["sender"] == "Amie X"
    assert "Comment tu vas" in messages[0]["content"]
    assert messages[0]["timestamp_utc"].startswith("2025-05-12T14:37")
    # Le dernier message HTML ne porte qu'une image.
    assert messages[-1]["media_path"] and "img_html.jpg" in messages[-1]["media_path"]

    followers = db.query_all(
        case.conn, "SELECT username, timestamp_utc FROM followers WHERE case_id = ?", (case.case_id,)
    )
    noms = {row["username"] for row in followers}
    assert noms == {"amie_x", "bob_y"}
    assert all(row["timestamp_utc"] for row in followers)


def test_detection_par_cles_json_et_non_par_nom(tmp_path):
    """Un fichier renommé par Meta reste correctement détecté."""
    from app.importers.detector import CAT_FOLLOWING, CAT_MESSAGES, detect_file

    renomme = tmp_path / "un_nom_totalement_different.json"
    renomme.write_text(
        json.dumps({"relationships_following": [
            {"string_list_data": [{"href": "https://www.instagram.com/x", "value": "x", "timestamp": 1}]}
        ]}),
        encoding="utf-8",
    )
    detection = detect_file(renomme)
    assert detection.category == CAT_FOLLOWING
    assert detection.confidence == "forte"

    fil = tmp_path / "zzz.json"
    fil.write_text(
        json.dumps({"participants": [{"name": "A"}], "messages": [{"sender_name": "A", "content": "s"}]}),
        encoding="utf-8",
    )
    assert detect_file(fil).category == CAT_MESSAGES


def test_detection_par_chemin_quand_le_json_est_anonyme(tmp_path):
    from app.importers.detector import CAT_FOLLOWERS, detect_file

    dossier = tmp_path / "connections" / "followers_and_following"
    dossier.mkdir(parents=True)
    cible = dossier / "followers_1.json"
    cible.write_text(
        json.dumps([{"string_list_data": [{"href": "https://www.instagram.com/a", "value": "a"}]}]),
        encoding="utf-8",
    )
    assert detect_file(cible, root=tmp_path).category == CAT_FOLLOWERS


def test_le_mojibake_est_repare_dans_les_messages(case, exports):
    from app import db
    from app.importers.pipeline import import_and_analyze

    export_a, _ = exports
    import_and_analyze(case, export_a, label="Export 2024")
    contenus = [
        row["content"]
        for row in db.query_all(
            case.conn, "SELECT content FROM messages WHERE case_id = ?", (case.case_id,)
        )
    ]
    assert "Tu as les photos de la soirée ?" in contenus


def test_direction_des_messages(loaded_case):
    from app import db

    envoyes = db.scalar(
        db.connect(loaded_case.db_path),
        "SELECT COUNT(*) FROM messages WHERE direction = 'sent' AND sender = 'Mon Compte'",
    )
    assert envoyes > 0
    inconnus = db.scalar(
        loaded_case.conn, "SELECT COUNT(*) FROM messages WHERE direction = 'unknown'"
    )
    assert inconnus == 0


def test_donnee_originale_conservee_integralement(loaded_case):
    from app import db

    row = db.query_one(
        loaded_case.conn,
        "SELECT original_raw FROM messages WHERE content = 'Salut !' LIMIT 1",
    )
    brut = json.loads(row["original_raw"])
    assert brut["sender_name"] == "Amie X"
    assert "timestamp_ms" in brut


def test_deduplication_entre_deux_exports(loaded_case):
    from app import db

    # Le fil « Amie X » : 7 messages en 2024, 7 en 2025 dont 1 nouveau et 1 disparu.
    distincts = db.scalar(
        loaded_case.conn,
        "SELECT COUNT(DISTINCT m.dedup_key) FROM messages m "
        "JOIN conversations c ON c.id = m.conversation_id WHERE c.title = 'Amie X'",
    )
    assert distincts == 8


def test_zip_slip_refuse(case, tmp_path):
    """Une archive contenant un chemin hors dossier est ignorée, pas extraite."""
    import zipfile

    from app.importers.ingest import ingest

    piege = tmp_path / "piege.zip"
    with zipfile.ZipFile(piege, "w") as archive:
        archive.writestr("../../evasion.txt", "contenu")
        archive.writestr("normal.txt", "contenu")

    result = ingest(case, piege, label="archive piegee")
    assert any("hors archive" in note for note in result.skipped)
    assert not (case.path.parent / "evasion.txt").exists()
    assert not (tmp_path / "evasion.txt").exists()


def test_import_bloque_en_mode_preuve(loaded_case, exports):
    import pytest

    from app.cases import EvidenceModeError
    from app.importers.pipeline import import_and_analyze
    from app.integrity import enable_evidence_mode

    enable_evidence_mode(loaded_case)
    with pytest.raises(EvidenceModeError):
        import_and_analyze(loaded_case, exports[0], label="Tentative")
