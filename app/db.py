"""Couche SQLite locale.

Chaque dossier d'enquête possède sa propre base `case.db`, ce qui rend le
dossier entièrement portable (on peut le copier sur une clé USB et le rouvrir
ailleurs sans perdre le lien entre la base et les fichiers sources).
"""
from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterable, Iterator

SCHEMA_VERSION = 1

_LOCKS: dict[str, threading.RLock] = {}
_LOCKS_GUARD = threading.Lock()

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);

-- Dossier d'enquete (une seule ligne par base, conservee pour l'auto-description)
CREATE TABLE IF NOT EXISTS cases (
    id              INTEGER PRIMARY KEY,
    slug            TEXT NOT NULL UNIQUE,
    username        TEXT NOT NULL,
    display_name    TEXT,
    created_at      TEXT NOT NULL,
    notes           TEXT,
    evidence_mode   INTEGER NOT NULL DEFAULT 0
);

-- Une source = un lot fourni par l'utilisateur (une archive, un dossier,
-- un ensemble de captures, un import ADB, une collecte publique...)
CREATE TABLE IF NOT EXISTS sources (
    id                INTEGER PRIMARY KEY,
    case_id           INTEGER NOT NULL REFERENCES cases(id),
    label             TEXT NOT NULL,
    kind              TEXT NOT NULL,        -- instagram_export | local_files | android | public_capture | manual
    declared_origin   TEXT,                 -- provenance declaree par l'utilisateur
    declared_date     TEXT,                 -- date declaree (periode couverte)
    observations      TEXT,
    imported_at       TEXT NOT NULL,
    root_path         TEXT,                 -- chemin dans cases/<slug>/sources/...
    file_count        INTEGER NOT NULL DEFAULT 0,
    total_bytes       INTEGER NOT NULL DEFAULT 0,
    status            TEXT NOT NULL DEFAULT 'imported'
);
CREATE INDEX IF NOT EXISTS idx_sources_case ON sources(case_id);

-- Un import = une execution d'analyse sur une source
CREATE TABLE IF NOT EXISTS imports (
    id            INTEGER PRIMARY KEY,
    case_id       INTEGER NOT NULL REFERENCES cases(id),
    source_id     INTEGER REFERENCES sources(id),
    started_at    TEXT NOT NULL,
    finished_at   TEXT,
    status        TEXT NOT NULL DEFAULT 'running',
    summary_json  TEXT,
    error         TEXT
);
CREATE INDEX IF NOT EXISTS idx_imports_case ON imports(case_id);

-- Tout fichier vu par l'outil, avec son empreinte (section 13)
CREATE TABLE IF NOT EXISTS files (
    id             INTEGER PRIMARY KEY,
    case_id        INTEGER NOT NULL REFERENCES cases(id),
    source_id      INTEGER REFERENCES sources(id),
    rel_path       TEXT NOT NULL,       -- chemin relatif au dossier d'enquete
    original_name  TEXT,
    original_path  TEXT,                -- chemin d'origine declare (avant copie)
    extension      TEXT,
    mime_guess     TEXT,
    size_bytes     INTEGER NOT NULL DEFAULT 0,
    sha256         TEXT,
    file_mtime     TEXT,                -- date du fichier (systeme de fichiers)
    imported_at    TEXT NOT NULL,
    category       TEXT,                -- messages | followers | media | profile | ...
    parsed         INTEGER NOT NULL DEFAULT 0,
    parse_note     TEXT,
    UNIQUE(case_id, rel_path)
);
CREATE INDEX IF NOT EXISTS idx_files_case ON files(case_id);
CREATE INDEX IF NOT EXISTS idx_files_sha ON files(sha256);
CREATE INDEX IF NOT EXISTS idx_files_category ON files(case_id, category);

-- Personnes rencontrees (participants, abonnes, abonnements, auteurs)
CREATE TABLE IF NOT EXISTS people (
    id            INTEGER PRIMARY KEY,
    case_id       INTEGER NOT NULL REFERENCES cases(id),
    username      TEXT,
    display_name  TEXT,
    profile_url   TEXT,
    is_owner      INTEGER NOT NULL DEFAULT 0,
    first_seen_at TEXT,
    notes         TEXT,
    UNIQUE(case_id, username, display_name)
);
CREATE INDEX IF NOT EXISTS idx_people_case ON people(case_id);
CREATE INDEX IF NOT EXISTS idx_people_username ON people(case_id, username);

CREATE TABLE IF NOT EXISTS conversations (
    id                INTEGER PRIMARY KEY,
    case_id           INTEGER NOT NULL REFERENCES cases(id),
    source_id         INTEGER REFERENCES sources(id),
    external_id       TEXT,              -- identifiant issu de l'export (thread_path)
    group_key         TEXT,              -- cle de regroupement du meme fil entre plusieurs exports
    title             TEXT,
    participants_json TEXT,              -- liste JSON des participants
    is_group          INTEGER NOT NULL DEFAULT 0,
    first_message_at  TEXT,
    last_message_at   TEXT,
    message_count     INTEGER NOT NULL DEFAULT 0,
    source_files_json TEXT,
    raw_meta_json     TEXT
);
CREATE INDEX IF NOT EXISTS idx_conv_case ON conversations(case_id);
CREATE INDEX IF NOT EXISTS idx_conv_ext ON conversations(case_id, external_id, source_id);
CREATE INDEX IF NOT EXISTS idx_conv_group ON conversations(case_id, group_key);

CREATE TABLE IF NOT EXISTS messages (
    id               INTEGER PRIMARY KEY,
    case_id          INTEGER NOT NULL REFERENCES cases(id),
    conversation_id  INTEGER NOT NULL REFERENCES conversations(id),
    source_id        INTEGER REFERENCES sources(id),
    sender           TEXT,
    direction        TEXT,               -- sent | received | unknown
    timestamp_utc    TEXT,               -- ISO 8601, NULL si reellement absent
    timestamp_ms     INTEGER,            -- epoch ms si present dans la source
    content          TEXT,
    msg_type         TEXT,               -- text | media | share | call | reaction_only | system
    media_path       TEXT,
    reactions_json   TEXT,
    share_json       TEXT,
    file_id          INTEGER REFERENCES files(id),
    source_file      TEXT,
    original_raw     TEXT NOT NULL,      -- donnee brute d'origine, jamais modifiee
    dedup_key        TEXT
);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id, timestamp_ms);
CREATE INDEX IF NOT EXISTS idx_msg_case_ts ON messages(case_id, timestamp_ms);
CREATE INDEX IF NOT EXISTS idx_msg_sender ON messages(case_id, sender);
CREATE INDEX IF NOT EXISTS idx_msg_dedup ON messages(case_id, dedup_key);

-- Instantane de relations : un export = un snapshot date
CREATE TABLE IF NOT EXISTS relationship_snapshots (
    id            INTEGER PRIMARY KEY,
    case_id       INTEGER NOT NULL REFERENCES cases(id),
    source_id     INTEGER NOT NULL REFERENCES sources(id),
    kind          TEXT NOT NULL,        -- followers | following | pending | blocked | recently_followed | close_friends
    label         TEXT,
    captured_at   TEXT,                 -- date declaree de l'export (peut etre NULL)
    imported_at   TEXT NOT NULL,
    entry_count   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_snap_case ON relationship_snapshots(case_id, kind);

CREATE TABLE IF NOT EXISTS followers (
    id             INTEGER PRIMARY KEY,
    case_id        INTEGER NOT NULL REFERENCES cases(id),
    snapshot_id    INTEGER NOT NULL REFERENCES relationship_snapshots(id),
    username       TEXT,
    display_name   TEXT,
    profile_url    TEXT,
    timestamp_utc  TEXT,               -- NULL si absent de la source
    timestamp_ms   INTEGER,
    status         TEXT DEFAULT 'present',
    source_file    TEXT,
    original_raw   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_followers_case ON followers(case_id, username);
CREATE INDEX IF NOT EXISTS idx_followers_snap ON followers(snapshot_id);

CREATE TABLE IF NOT EXISTS following (
    id             INTEGER PRIMARY KEY,
    case_id        INTEGER NOT NULL REFERENCES cases(id),
    snapshot_id    INTEGER NOT NULL REFERENCES relationship_snapshots(id),
    username       TEXT,
    display_name   TEXT,
    profile_url    TEXT,
    timestamp_utc  TEXT,
    timestamp_ms   INTEGER,
    status         TEXT DEFAULT 'present',
    source_file    TEXT,
    original_raw   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_following_case ON following(case_id, username);
CREATE INDEX IF NOT EXISTS idx_following_snap ON following(snapshot_id);

CREATE TABLE IF NOT EXISTS media (
    id            INTEGER PRIMARY KEY,
    case_id       INTEGER NOT NULL REFERENCES cases(id),
    source_id     INTEGER REFERENCES sources(id),
    file_id       INTEGER REFERENCES files(id),
    rel_path      TEXT,
    kind          TEXT,                 -- image | video | audio | unknown
    taken_at      TEXT,
    context       TEXT,                 -- message | post | story | archive | local
    conversation_id INTEGER REFERENCES conversations(id),
    caption       TEXT,
    original_raw  TEXT
);
CREATE INDEX IF NOT EXISTS idx_media_case ON media(case_id);

-- Evenements de la chronologie (section 12)
CREATE TABLE IF NOT EXISTS events (
    id            INTEGER PRIMARY KEY,
    case_id       INTEGER NOT NULL REFERENCES cases(id),
    occurred_at   TEXT,                 -- NULL = date inconnue
    occurred_ms   INTEGER,
    period_start  TEXT,                 -- pour un evenement borne entre deux exports
    period_end    TEXT,
    kind          TEXT NOT NULL,        -- message | follow | unfollow | media | login | note | gap | ...
    title         TEXT NOT NULL,
    detail        TEXT,
    confidence    TEXT NOT NULL,        -- CONFIRME | PROBABLE | POSSIBLE | INCONNU
    source_ref    TEXT,                 -- description de la source
    file_id       INTEGER REFERENCES files(id),
    ref_table     TEXT,
    ref_id        INTEGER,
    original_raw  TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_case ON events(case_id, occurred_ms);
CREATE INDEX IF NOT EXISTS idx_events_kind ON events(case_id, kind);

-- Elements potentiellement manquants (section 6) - jamais presente comme un message
CREATE TABLE IF NOT EXISTS gaps (
    id               INTEGER PRIMARY KEY,
    case_id          INTEGER NOT NULL REFERENCES cases(id),
    conversation_id  INTEGER REFERENCES conversations(id),
    detected_at      TEXT NOT NULL,
    gap_type         TEXT NOT NULL,     -- reply_to_absent | reaction_without_target | orphan_media | export_diff | ...
    confidence       TEXT NOT NULL,
    description      TEXT NOT NULL,
    indicator        TEXT,              -- indice brut ayant declenche la detection
    window_start     TEXT,
    window_end       TEXT,
    source_ref       TEXT,
    original_raw     TEXT
);
CREATE INDEX IF NOT EXISTS idx_gaps_case ON gaps(case_id, gap_type);

-- Elements de preuve libres (captures, notes, PDF, notifications...)
CREATE TABLE IF NOT EXISTS evidence (
    id              INTEGER PRIMARY KEY,
    case_id         INTEGER NOT NULL REFERENCES cases(id),
    source_id       INTEGER REFERENCES sources(id),
    file_id         INTEGER REFERENCES files(id),
    label           TEXT,
    evidence_type   TEXT,               -- screenshot | pdf | html | email | note | url | json | csv | video
    declared_origin TEXT,
    declared_date   TEXT,
    observations    TEXT,
    extracted_text  TEXT,
    added_at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_evidence_case ON evidence(case_id);

-- Donnees publiques collectees (section 2)
CREATE TABLE IF NOT EXISTS public_profile (
    id             INTEGER PRIMARY KEY,
    case_id        INTEGER NOT NULL REFERENCES cases(id),
    collected_at   TEXT NOT NULL,
    method         TEXT NOT NULL,        -- http_public | manual_html | manual_json | manual_note
    url            TEXT,
    http_status    INTEGER,
    username       TEXT,
    full_name      TEXT,
    biography      TEXT,
    external_url   TEXT,
    followers_count INTEGER,
    following_count INTEGER,
    posts_count    INTEGER,
    profile_pic_url TEXT,
    is_private     INTEGER,
    raw_payload    TEXT,
    file_id        INTEGER REFERENCES files(id),
    note           TEXT
);
CREATE INDEX IF NOT EXISTS idx_public_case ON public_profile(case_id, collected_at);

-- Comparaisons entre exports (section 8)
CREATE TABLE IF NOT EXISTS comparisons (
    id            INTEGER PRIMARY KEY,
    case_id       INTEGER NOT NULL REFERENCES cases(id),
    source_a      INTEGER NOT NULL REFERENCES sources(id),
    source_b      INTEGER NOT NULL REFERENCES sources(id),
    created_at    TEXT NOT NULL,
    result_json   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comparisons_case ON comparisons(case_id);

-- Journal d'audit : toute action modifiant le dossier
CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY,
    case_id     INTEGER NOT NULL REFERENCES cases(id),
    at          TEXT NOT NULL,
    action      TEXT NOT NULL,
    detail      TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_case ON audit_log(case_id, at);
"""

FTS_SCHEMA = """
CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(
    body,
    title,
    kind UNINDEXED,
    ref_table UNINDEXED,
    ref_id UNINDEXED,
    person UNINDEXED,
    ts UNINDEXED,
    source_ref UNINDEXED,
    tokenize='unicode61 remove_diacritics 2'
);
"""

FALLBACK_SEARCH_SCHEMA = """
CREATE TABLE IF NOT EXISTS search_index (
    rowid     INTEGER PRIMARY KEY,
    body      TEXT,
    title     TEXT,
    kind      TEXT,
    ref_table TEXT,
    ref_id    INTEGER,
    person    TEXT,
    ts        TEXT,
    source_ref TEXT
);
CREATE INDEX IF NOT EXISTS idx_search_body ON search_index(body);
"""


def _lock_for(path: Path) -> threading.RLock:
    key = str(path)
    with _LOCKS_GUARD:
        lock = _LOCKS.get(key)
        if lock is None:
            lock = threading.RLock()
            _LOCKS[key] = lock
        return lock


def connect(db_path: Path) -> sqlite3.Connection:
    """Ouvre une connexion SQLite configuree."""
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path), timeout=30.0, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def has_fts5(conn: sqlite3.Connection) -> bool:
    try:
        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS __fts_probe USING fts5(x)")
        conn.execute("DROP TABLE IF EXISTS __fts_probe")
        return True
    except sqlite3.Error:
        return False


def _dedupe_people(conn: sqlite3.Connection) -> None:
    """Fusionne les doublons de `people` puis pose l'index unique.

    SQLite traite deux NULL comme distincts dans une contrainte UNIQUE : sans
    cet index base sur IFNULL, une personne sans nom d'utilisateur serait
    reinseree a chaque import.
    """
    existing = {
        row["name"]
        for row in conn.execute("PRAGMA index_list('people')").fetchall()
    }
    if "idx_people_unique" in existing:
        return
    conn.execute(
        "DELETE FROM people WHERE id NOT IN ("
        "  SELECT MIN(id) FROM people "
        "  GROUP BY case_id, IFNULL(username, ''), IFNULL(display_name, ''))"
    )
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_people_unique "
        "ON people(case_id, IFNULL(username, ''), IFNULL(display_name, ''))"
    )


def init_schema(conn: sqlite3.Connection) -> bool:
    """Créé le schema. Retourne True si FTS5 est disponible."""
    conn.executescript(SCHEMA)
    _dedupe_people(conn)
    fts = has_fts5(conn)
    if fts:
        conn.executescript(FTS_SCHEMA)
    else:
        conn.executescript(FALLBACK_SEARCH_SCHEMA)
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', ?)",
        (str(SCHEMA_VERSION),),
    )
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES('fts5', ?)",
        ("1" if fts else "0",),
    )
    conn.commit()
    return fts


@contextmanager
def transaction(conn: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def query_all(conn: sqlite3.Connection, sql: str, params: Iterable[Any] = ()) -> list[dict]:
    cur = conn.execute(sql, tuple(params))
    rows = [dict(r) for r in cur.fetchall()]
    cur.close()
    return rows


def query_one(conn: sqlite3.Connection, sql: str, params: Iterable[Any] = ()) -> dict | None:
    cur = conn.execute(sql, tuple(params))
    row = cur.fetchone()
    cur.close()
    return dict(row) if row else None


def scalar(conn: sqlite3.Connection, sql: str, params: Iterable[Any] = ()) -> Any:
    cur = conn.execute(sql, tuple(params))
    row = cur.fetchone()
    cur.close()
    return row[0] if row else None


def insert(conn: sqlite3.Connection, table: str, values: dict[str, Any]) -> int:
    cols = ", ".join(values.keys())
    marks = ", ".join("?" for _ in values)
    cur = conn.execute(
        f"INSERT INTO {table} ({cols}) VALUES ({marks})", tuple(values.values())
    )
    rid = int(cur.lastrowid)
    cur.close()
    return rid


def json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def json_loads(value: str | None, default: Any = None) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except (ValueError, TypeError):
        return default
