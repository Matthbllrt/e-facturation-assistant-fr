"""Configuration commune des tests.

Chaque test s'exécute dans un répertoire de données isolé, afin de ne jamais
toucher aux dossiers d'enquête réels de l'utilisateur.
"""
from __future__ import annotations

import importlib
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests"))


@pytest.fixture()
def data_dir(tmp_path, monkeypatch):
    """Isole IER_DATA_DIR et recharge la configuration."""
    target = tmp_path / "cases"
    target.mkdir()
    monkeypatch.setenv("IER_DATA_DIR", str(target))

    from app import config as config_module

    importlib.reload(config_module)
    for name in (
        "app.db", "app.utils", "app.cases", "app.people", "app.integrity",
        "app.sources_local", "app.importers.ingest", "app.importers.detector",
        "app.importers.messages", "app.importers.relations", "app.importers.activity",
        "app.importers.pipeline", "app.analysis.gaps", "app.analysis.compare",
        "app.analysis.search", "app.analysis.stats", "app.analysis.timeline",
        "app.publicdata.collect", "app.reporting.builder", "app.reporting.render",
    ):
        module = sys.modules.get(name)
        if module is not None:
            importlib.reload(module)
    yield target


@pytest.fixture()
def exports(tmp_path):
    """Deux exports Instagram factices (2024 et 2025)."""
    import fixtures

    base = tmp_path / "exports"
    base.mkdir()
    return fixtures.build_two_exports(base)


@pytest.fixture()
def case(data_dir):
    from app.cases import create_case

    return create_case("@mon_compte")


@pytest.fixture()
def loaded_case(case, exports):
    """Dossier avec les deux exports importés et analysés."""
    from app.importers.pipeline import import_and_analyze

    export_a, export_b = exports
    import_and_analyze(case, export_a, label="Export 2024", declared_date="2024-11-14")
    import_and_analyze(case, export_b, label="Export 2025", declared_date="2025-10-27")
    return case
