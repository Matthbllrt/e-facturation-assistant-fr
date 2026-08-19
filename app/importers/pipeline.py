"""Orchestration complète d'un import.

Enchainement : ingestion -> détection -> analyse par catégorie ->
identification du propriétaire -> événements -> indexation de recherche.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .. import db as dbmod
from ..cases import Case
from ..people import upsert_person
from ..config import CONFIDENCE_CONFIRMED, MEDIA_EXTENSIONS
from ..utils import (
    media_kind,
    ms_to_iso,
    safe_relpath,
    truncate,
    utcnow_iso,
)
from . import activity as activity_mod
from . import ingest as ingest_mod
from . import messages as messages_mod
from . import relations as relations_mod
from .detector import (
    CAT_ARCHIVE,
    CAT_CONTACTS,
    CAT_MEDIA_FILE,
    CAT_MESSAGES,
    CAT_PROFILE,
    CAT_UNKNOWN,
    CATEGORY_LABELS,
    RELATION_CATEGORIES,
    RELATION_LABELS,
    detect_file,
)

ProgressFn = Callable[[str, int, int], None]


@dataclass
class ImportReport:
    source_id: int
    label: str
    files_seen: int = 0
    files_parsed: int = 0
    conversations: int = 0
    messages: int = 0
    relations: dict[str, int] = field(default_factory=dict)
    media: int = 0
    events: int = 0
    gaps: int = 0
    profile_found: bool = False
    unknown_files: int = 0
    warnings: list[str] = field(default_factory=list)
    categories: dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "source_id": self.source_id,
            "label": self.label,
            "files_seen": self.files_seen,
            "files_parsed": self.files_parsed,
            "conversations": self.conversations,
            "messages": self.messages,
            "relations": self.relations,
            "media": self.media,
            "events": self.events,
            "gaps": self.gaps,
            "profile_found": self.profile_found,
            "unknown_files": self.unknown_files,
            "warnings": self.warnings[:100],
            "categories": self.categories,
        }


def _owner_aliases(case: Case) -> set[str]:
    """Noms sous lesquels le propriétaire du dossier peut apparaitre."""
    aliases = {case.username.lower()}
    if case.display_name:
        aliases.add(case.display_name.strip().lower())
    rows = dbmod.query_all(
        case.conn,
        "SELECT username, display_name FROM people WHERE case_id = ? AND is_owner = 1",
        (case.case_id,),
    )
    for row in rows:
        for value in (row.get("username"), row.get("display_name")):
            if value:
                aliases.add(str(value).strip().lower())
    return {a for a in aliases if a}


def _register_owner(case: Case, username: str | None, display_name: str | None) -> None:
    if not (username or display_name):
        return
    upsert_person(case, username=username, display_name=display_name, is_owner=True)
    if username:
        case.conn.execute(
            "UPDATE people SET is_owner = 1 WHERE case_id = ? AND username = ?",
            (case.case_id, username),
        )
    if display_name:
        case.conn.execute(
            "UPDATE people SET is_owner = 1 WHERE case_id = ? AND display_name = ?",
            (case.case_id, display_name),
        )
    if display_name and not case.display_name:
        case.display_name = display_name
        case.conn.execute(
            "UPDATE cases SET display_name = ? WHERE slug = ?", (display_name, case.slug)
        )
        case.write_meta()


def analyze_source(
    case: Case,
    source_id: int,
    root: Path,
    progress: ProgressFn | None = None,
) -> ImportReport:
    """Analyse tous les fichiers déjà ingeres d'une source."""
    row = dbmod.query_one(case.conn, "SELECT label, declared_date FROM sources WHERE id = ?", (source_id,))
    report = ImportReport(source_id=source_id, label=(row or {}).get("label", "source"))
    declared_date = (row or {}).get("declared_date")

    import_id = dbmod.insert(
        case.conn,
        "imports",
        {
            "case_id": case.case_id,
            "source_id": source_id,
            "started_at": utcnow_iso(),
            "status": "running",
        },
    )
    case.conn.commit()

    files = sorted(ingest_mod._iter_files(root))
    report.files_seen = len(files)
    owner_aliases = _owner_aliases(case)

    # Passe 1 : profil d'abord (fournit les alias du proprietaire)
    detections: list[tuple[Path, Any]] = []
    for path in files:
        try:
            detection = detect_file(path, root=case.path)
        except Exception as exc:  # un fichier corrompu ne doit pas casser l'import
            report.warnings.append(f"{path.name} : détection impossible ({exc})")
            continue
        detections.append((path, detection))
        report.categories[detection.category] = report.categories.get(detection.category, 0) + 1

    profile_entries: list[activity_mod.MetaEntry] = []
    for path, detection in detections:
        if detection.category != CAT_PROFILE:
            continue
        data = detection.payload if detection.payload is not None else activity_mod.load_json(path)
        if data is None:
            continue
        entries = activity_mod.parse_meta_entries(data)
        profile = activity_mod.extract_profile(entries)
        if profile.get("username") or profile.get("full_name"):
            _register_owner(case, profile.get("username"), profile.get("full_name"))
            owner_aliases = _owner_aliases(case)
            report.profile_found = True
            profile_entries.extend(entries)

    snapshots: dict[str, int] = {}
    file_ids: dict[str, int] = {}

    for index, (path, detection) in enumerate(detections, start=1):
        rel = safe_relpath(path, case.path)
        file_row = dbmod.query_one(
            case.conn, "SELECT id FROM files WHERE case_id = ? AND rel_path = ?", (case.case_id, rel)
        )
        file_id = int(file_row["id"]) if file_row else None
        if file_id:
            file_ids[rel] = file_id
            case.conn.execute(
                "UPDATE files SET category = ?, parse_note = ? WHERE id = ?",
                (detection.category, f"{detection.signal} (confiance {detection.confidence})", file_id),
            )

        category = detection.category
        parsed_something = False

        try:
            if category == CAT_MESSAGES:
                thread = None
                if path.suffix.lower() == ".json":
                    thread = messages_mod.parse_message_json(
                        path, detection.payload, root=case.path
                    )
                elif path.suffix.lower() in {".html", ".htm"}:
                    thread = messages_mod.parse_message_html(path, root=case.path)
                if thread:
                    conv_id, inserted = messages_mod.store_thread(
                        case, source_id, thread, owner_aliases, file_id
                    )
                    report.messages += inserted
                    parsed_something = True
                    for name in thread.participants:
                        upsert_person(case, display_name=name)

            elif category in RELATION_CATEGORIES:
                if path.suffix.lower() == ".json":
                    entries = relations_mod.parse_relation_json(
                        path, detection.payload, root=case.path
                    )
                else:
                    entries = relations_mod.parse_relation_html(path, root=case.path)
                if entries:
                    snapshot_id = snapshots.get(category)
                    if snapshot_id is None:
                        snapshot_id = relations_mod.create_snapshot(
                            case,
                            source_id,
                            category,
                            label=f"{RELATION_LABELS.get(category, category)} — {report.label}",
                            captured_at=declared_date,
                        )
                        snapshots[category] = snapshot_id
                    count = relations_mod.store_entries(case, snapshot_id, category, entries)
                    report.relations[category] = report.relations.get(category, 0) + count
                    parsed_something = True

            elif category == CAT_MEDIA_FILE:
                dbmod.insert(
                    case.conn,
                    "media",
                    {
                        "case_id": case.case_id,
                        "source_id": source_id,
                        "file_id": file_id,
                        "rel_path": rel,
                        "kind": media_kind(path) or "unknown",
                        "taken_at": None,
                        "context": "fichier",
                        "caption": None,
                        "original_raw": None,
                    },
                )
                report.media += 1
                parsed_something = True

            elif category == CAT_ARCHIVE:
                # L'archive elle-meme est conservee et hachee ; son contenu a
                # deja ete decompresse puis analyse fichier par fichier.
                parsed_something = True

            elif category == CAT_CONTACTS:
                data = detection.payload if detection.payload is not None else activity_mod.load_json(path)
                entries = activity_mod.parse_meta_entries(data) if data else []
                report.events += activity_mod.store_events(
                    case, CAT_CONTACTS, entries, rel, file_id
                )
                parsed_something = bool(entries)

            elif category != CAT_UNKNOWN:
                if path.suffix.lower() == ".json":
                    data = detection.payload if detection.payload is not None else activity_mod.load_json(path)
                    entries = activity_mod.parse_meta_entries(data) if data is not None else []
                else:
                    entries = activity_mod.html_entries(path, root=case.path)
                if entries:
                    report.events += activity_mod.store_events(
                        case, category, entries, rel, file_id
                    )
                    report.media += activity_mod.store_media(
                        case, source_id, entries, category, file_id
                    )
                    parsed_something = True
            else:
                report.unknown_files += 1
        except Exception as exc:
            report.warnings.append(f"{rel} : analyse partielle ({type(exc).__name__}: {exc})")

        if file_id and parsed_something:
            case.conn.execute("UPDATE files SET parsed = 1 WHERE id = ?", (file_id,))
            report.files_parsed += 1

        if index % 50 == 0:
            case.conn.commit()
            if progress:
                progress("analyse", index, len(detections))

    case.conn.commit()

    report.conversations = int(
        dbmod.scalar(
            case.conn,
            "SELECT COUNT(*) FROM conversations WHERE case_id = ? AND source_id = ?",
            (case.case_id, source_id),
        )
        or 0
    )

    # Evenements issus des conversations et des relations
    report.events += build_message_events(case, source_id)
    report.events += build_relation_events(case, source_id)

    from ..analysis import gaps as gaps_mod
    from ..analysis import search as search_mod

    report.gaps += gaps_mod.detect_gaps_for_source(case, source_id)
    search_mod.reindex(case)

    case.conn.execute(
        "UPDATE imports SET finished_at = ?, status = 'done', summary_json = ? WHERE id = ?",
        (utcnow_iso(), dbmod.json_dumps(report.to_dict()), import_id),
    )
    case.conn.commit()
    case.audit(
        "source_analysee",
        f"{report.label} : {report.messages} messages, {report.conversations} conversations",
    )
    return report


def build_message_events(case: Case, source_id: int) -> int:
    """Un événement de chronologie par conversation (premier/dernier message).

    On n'ajoute pas un événement par message : la chronologie resterait
    illisible sur des exports de plusieurs dizaines de milliers de messages.
    Le detail message par message reste consultable dans la vue Conversations.
    """
    rows = dbmod.query_all(
        case.conn,
        "SELECT id, title, external_id, message_count, first_message_at, last_message_at, "
        "       participants_json, source_files_json "
        "FROM conversations WHERE case_id = ? AND source_id = ?",
        (case.case_id, source_id),
    )
    count = 0
    for row in rows:
        participants = dbmod.json_loads(row["participants_json"], []) or []
        label = row["title"] or row["external_id"] or "Conversation"
        for moment, tag in (
            (row["first_message_at"], "Premier message"),
            (row["last_message_at"], "Dernier message"),
        ):
            if not moment:
                continue
            from ..utils import iso_to_ms

            existing = dbmod.query_one(
                case.conn,
                "SELECT id FROM events WHERE case_id = ? AND kind = 'message' AND ref_id = ? AND title = ?",
                (case.case_id, row["id"], f"{tag} — {label}"),
            )
            if existing:
                continue
            dbmod.insert(
                case.conn,
                "events",
                {
                    "case_id": case.case_id,
                    "occurred_at": moment,
                    "occurred_ms": iso_to_ms(moment),
                    "kind": "message",
                    "title": f"{tag} — {label}",
                    "detail": f"{row['message_count']} message(s) — participants : "
                    f"{truncate(', '.join(str(p) for p in participants), 120)}",
                    "confidence": CONFIDENCE_CONFIRMED,
                    "source_ref": truncate(
                        ", ".join(dbmod.json_loads(row["source_files_json"], []) or []), 300
                    ),
                    "ref_table": "conversations",
                    "ref_id": row["id"],
                },
            )
            count += 1
    case.conn.commit()
    return count


def build_relation_events(case: Case, source_id: int) -> int:
    """Événements pour les relations qui possèdent une VRAIE date source."""
    count = 0
    for table, kind_label in (("followers", "Nouvel abonne"), ("following", "Abonnement")):
        rows = dbmod.query_all(
            case.conn,
            f"SELECT r.id, r.username, r.timestamp_utc, r.timestamp_ms, r.status, r.source_file "
            f"FROM {table} r JOIN relationship_snapshots s ON s.id = r.snapshot_id "
            f"WHERE r.case_id = ? AND s.source_id = ? AND r.timestamp_ms IS NOT NULL",
            (case.case_id, source_id),
        )
        for row in rows:
            existing = dbmod.query_one(
                case.conn,
                "SELECT id FROM events WHERE case_id = ? AND ref_table = ? AND ref_id = ?",
                (case.case_id, table, row["id"]),
            )
            if existing:
                continue
            label = RELATION_LABELS.get(row["status"], kind_label)
            dbmod.insert(
                case.conn,
                "events",
                {
                    "case_id": case.case_id,
                    "occurred_at": row["timestamp_utc"],
                    "occurred_ms": row["timestamp_ms"],
                    "kind": row["status"] or ("follow" if table == "followers" else "following"),
                    "title": f"{label} : @{row['username'] or 'inconnu'}",
                    "detail": "Date présente dans le fichier source.",
                    "confidence": CONFIDENCE_CONFIRMED,
                    "source_ref": row["source_file"],
                    "ref_table": table,
                    "ref_id": row["id"],
                },
            )
            count += 1
    case.conn.commit()
    return count


def import_and_analyze(
    case: Case,
    input_path: Path,
    *,
    kind: str = "instagram_export",
    label: str | None = None,
    declared_origin: str | None = None,
    declared_date: str | None = None,
    observations: str | None = None,
    progress: ProgressFn | None = None,
) -> tuple[ingest_mod.IngestResult, ImportReport]:
    result = ingest_mod.ingest(
        case,
        input_path,
        kind=kind,
        label=label,
        declared_origin=declared_origin,
        declared_date=declared_date,
        observations=observations,
        progress=progress,
    )
    report = analyze_source(case, result.source_id, result.root_path, progress=progress)
    report.warnings.extend(result.skipped[:50])
    case.conn.execute(
        "UPDATE imports SET summary_json = ? WHERE source_id = ? AND status = 'done'",
        (dbmod.json_dumps(report.to_dict()), result.source_id),
    )
    case.conn.commit()
    return result, report
