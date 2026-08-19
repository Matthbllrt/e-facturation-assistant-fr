"""Tests des briques de base : utilitaires, base SQLite, dossiers d'enquête."""
from __future__ import annotations

import pytest


# ---------------------------------------------------------------- utilitaires
def test_clean_username_accepte_les_formes_courantes():
    from app.utils import clean_username, is_valid_username

    assert clean_username("@Mon_Compte") == "mon_compte"
    assert clean_username("https://www.instagram.com/foo.bar/") == "foo.bar"
    assert clean_username("  mon.compte  ") == "mon.compte"
    assert is_valid_username("mon_compte") is True
    assert is_valid_username("nom invalide") is False
    assert is_valid_username("") is False


def test_les_dates_non_reconnues_ne_sont_jamais_devinees():
    from app.utils import parse_datetime

    assert parse_datetime("pas une date") is None
    assert parse_datetime("") is None
    assert parse_datetime(None) is None
    assert parse_datetime(0) is None
    assert parse_datetime("2025-05-12T14:37:00") == "2025-05-12T14:37:00+00:00"


def test_epoch_secondes_millisecondes_et_microsecondes():
    from app.utils import ms_to_iso, normalize_epoch_ms

    assert ms_to_iso(1715515020) == ms_to_iso(1715515020000)
    assert normalize_epoch_ms(1715515020) == 1715515020000
    assert normalize_epoch_ms(1715515020000) == 1715515020000
    assert normalize_epoch_ms(None) is None
    assert normalize_epoch_ms("texte") is None
    assert normalize_epoch_ms(0) is None


def test_reparation_du_mojibake_des_exports_meta():
    from app.utils import fix_meta_mojibake

    assert fix_meta_mojibake("CafÃ© crÃ¨me") == "Café crème"
    assert fix_meta_mojibake("Café crème") == "Café crème"
    assert fix_meta_mojibake("texte simple") == "texte simple"


def test_sha256_reproductible(tmp_path):
    from app.utils import sha256_file

    target = tmp_path / "f.bin"
    target.write_bytes(b"contenu de test")
    first = sha256_file(target)
    assert first == sha256_file(target)
    assert len(first) == 64


# ---------------------------------------------------------------- base
def test_schema_complet(data_dir):
    from app import db
    from app.cases import create_case

    case = create_case("@compte_test")
    tables = {
        row["name"]
        for row in db.query_all(
            case.conn, "SELECT name FROM sqlite_master WHERE type = 'table'"
        )
    }
    attendues = {
        "cases", "sources", "files", "people", "conversations", "messages",
        "followers", "following", "relationship_snapshots", "events", "media",
        "evidence", "imports", "gaps", "comparisons", "public_profile", "audit_log",
    }
    assert attendues <= tables


# ---------------------------------------------------------------- dossiers
def test_creation_du_dossier_enquete(data_dir):
    from app.cases import create_case, list_cases, open_case

    case = create_case("@Mon_Compte")
    assert case.slug == "mon_compte"
    assert case.path.exists()
    assert (case.path / "sources").is_dir()
    assert (case.path / "case.json").exists()
    assert case.db_path.exists()

    encore = open_case("mon_compte")
    assert encore.username == "mon_compte"
    assert len(list_cases()) == 1


def test_nom_utilisateur_invalide_refuse(data_dir):
    from app.cases import CaseError, create_case

    with pytest.raises(CaseError):
        create_case("nom invalide !")
    with pytest.raises(CaseError):
        create_case("")


def test_creation_idempotente(data_dir):
    from app.cases import create_case, list_cases

    create_case("@compte")
    create_case("@compte")
    assert len(list_cases()) == 1


def test_deduplication_des_personnes(case):
    from app import db
    from app.people import upsert_person

    for _ in range(3):
        upsert_person(case, display_name="Amie X")
        upsert_person(case, username="amie_x")
    case.conn.commit()
    assert db.scalar(case.conn, "SELECT COUNT(*) FROM people") == 2

    # Le nom d'utilisateur complète ensuite la fiche existante.
    upsert_person(case, username="amie_x", display_name="Amie X", profile_url="https://x")
    case.conn.commit()
    rows = db.query_all(case.conn, "SELECT username, display_name, profile_url FROM people")
    assert any(r["username"] == "amie_x" and r["profile_url"] == "https://x" for r in rows)
