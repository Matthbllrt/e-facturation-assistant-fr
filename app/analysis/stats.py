"""Indicateur « Niveau de récupération » et checklist (sections 1 et 21)."""
from __future__ import annotations

from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..importers.detector import RELATION_LABELS


def _count(case: Case, sql: str, params: tuple = ()) -> int:
    return int(dbmod.scalar(case.conn, sql, (case.case_id,) + params) or 0)


def recovery_stats(case: Case) -> dict[str, Any]:
    case_id = case.case_id
    sources = dbmod.query_all(
        case.conn,
        "SELECT id, label, kind, declared_date, imported_at, file_count, total_bytes "
        "FROM sources WHERE case_id = ? ORDER BY id",
        (case_id,),
    )
    export_sources = [s for s in sources if s["kind"] == "instagram_export"]
    local_sources = [s for s in sources if s["kind"] not in {"instagram_export", "public_capture"}]

    public_rows = _count(case, "SELECT COUNT(*) FROM public_profile WHERE case_id = ?")
    conversations = _count(
        case, "SELECT COUNT(DISTINCT group_key) FROM conversations WHERE case_id = ?"
    )
    messages = _count(case, "SELECT COUNT(*) FROM messages WHERE case_id = ?")
    media = _count(case, "SELECT COUNT(*) FROM media WHERE case_id = ?")
    files = _count(case, "SELECT COUNT(*) FROM files WHERE case_id = ?")
    followers = _count(
        case, "SELECT COUNT(DISTINCT username) FROM followers WHERE case_id = ? AND status = 'followers'"
    )
    following = _count(
        case, "SELECT COUNT(DISTINCT username) FROM following WHERE case_id = ? AND status = 'following'"
    )
    people = _count(case, "SELECT COUNT(*) FROM people WHERE case_id = ?")
    gaps = _count(case, "SELECT COUNT(*) FROM gaps WHERE case_id = ?")
    events = _count(case, "SELECT COUNT(*) FROM events WHERE case_id = ?")
    evidence = _count(case, "SELECT COUNT(*) FROM evidence WHERE case_id = ?")
    comparisons = _count(case, "SELECT COUNT(*) FROM comparisons WHERE case_id = ?")
    hashed = _count(case, "SELECT COUNT(*) FROM files WHERE case_id = ? AND sha256 IS NOT NULL")

    relations_by_kind = dbmod.query_all(
        case.conn,
        "SELECT status, COUNT(DISTINCT username) AS n FROM ("
        "  SELECT status, username FROM followers WHERE case_id = ? "
        "  UNION ALL SELECT status, username FROM following WHERE case_id = ?"
        ") GROUP BY status",
        (case_id, case_id),
    )
    for row in relations_by_kind:
        row["label"] = RELATION_LABELS.get(row["status"], row["status"])

    return {
        "public_data": {
            "collected": public_rows > 0,
            "count": public_rows,
            "label": "Donnees publiques" ,
            "value": "collectees" if public_rows else "non collectees",
        },
        "export_imported": len(export_sources) > 0,
        "export_count": len(export_sources),
        "sources": sources,
        "local_sources": len(local_sources),
        "conversations": conversations,
        "messages": messages,
        "media": media,
        "files": files,
        "files_hashed": hashed,
        "followers": followers,
        "following": following,
        "relations_total": followers + following,
        "relations_by_kind": relations_by_kind,
        "people": people,
        "gaps": gaps,
        "events": events,
        "evidence": evidence,
        "comparisons": comparisons,
        "evidence_mode": case.evidence_mode,
        "can_compare": len(export_sources) >= 2,
    }


def checklist(case: Case) -> list[dict[str, Any]]:
    """Checklist « Récupérer davantage de données » (section 21)."""
    stats = recovery_stats(case)
    items: list[dict[str, Any]] = []

    def add(done: bool, label: str, advice: str, weight: int = 1) -> None:
        items.append({"done": done, "label": label, "advice": advice, "weight": weight})

    add(
        True,
        f"Nom d'utilisateur connu (@{case.username})",
        "Le dossier d'enquête est rattache a ce nom d'utilisateur.",
    )
    add(
        stats["public_data"]["collected"],
        "Profil public archive",
        "Lance une collecte publique depuis l'onglet Profil, ou collé le HTML de la page "
        "publique si Instagram bloqué la collecte automatique. Sans compte, seules les "
        "informations publiques restent accessibles.",
        2,
    )
    add(
        stats["export_imported"],
        "Export Instagram importé",
        "Demande ton archive sur instagram.com/download/request (nécessite l'accès au compte) "
        "ou retrouve une archive ZIP déjà téléchargée dans tes anciens téléphones, "
        "sauvegardes, e-mails ou Google Drive. C'est la source la plus riche.",
        5,
    )
    add(
        stats["can_compare"],
        "Deuxieme export permettant une comparaison",
        "Un second export a une date différente permet de dater les changements "
        "(abonnés apparus/disparus, messages ajoutes/absents) sans rien inventer.",
        3,
    )
    add(
        stats["local_sources"] > 0,
        "Sources locales ajoutees (captures, PDF, e-mails, sauvegardes)",
        "Ajoute d'anciennes captures d'écran, des e-mails Instagram archives, "
        "des PDF ou des sauvegardes de téléphone : ce sont souvent les seules traces "
        "d'éléments supprimés côté Instagram.",
        2,
    )
    add(
        stats["conversations"] > 0,
        "Conversations retrouvees",
        "Les conversations proviennent uniquement des exports importés. "
        "Sans export, aucun message ne peut être recupere legalement.",
        3,
    )
    add(
        stats["media"] > 0,
        "Medias retrouvés",
        "Les photos et videos se trouvent dans le dossier media/ de l'export, "
        "dans la pellicule du téléphone et dans les dossiers de téléchargement.",
        1,
    )
    add(
        stats["relations_total"] > 0,
        "Abonnés / abonnements retrouvés",
        "Presents dans connections/followers_and_following de l'export.",
        2,
    )
    add(
        case.evidence_mode,
        "Mode preuve active (empreintes SHA-256 figees)",
        "Active le mode preuve une fois tes imports terminés : les fichiers deviennent "
        "non modifiables et un manifest.json d'empreintes est généré.",
        2,
    )
    add(
        stats["evidence"] > 0,
        "Éléments de preuve annotes",
        "Documente la provenance de chaque piece (d'ou elle vient, quand, comment) : "
        "c'est ce qui donne sa valeur a une preuve.",
        1,
    )

    total_weight = sum(item["weight"] for item in items)
    done_weight = sum(item["weight"] for item in items if item["done"])
    for item in items:
        item["icon"] = "OK" if item["done"] else "MANQUANT"
    return {
        "items": items,
        "progress": round(100 * done_weight / total_weight) if total_weight else 0,
        "done": sum(1 for i in items if i["done"]),
        "total": len(items),
    }


def not_recoverable_notes() -> list[str]:
    """Ce qui ne peut pas être recupere sans reprendre le controle du compte."""
    return [
        "Messages supprimés côté Instagram et absents de tout export : Meta ne les "
        "restitue pas, ils ne sont pas reconstituables.",
        "Contenu privé d'autres comptes (publications privées, stories, DM auxquels "
        "tu n'es pas partie) : inaccessible et hors du périmètre de cet outil.",
        "Historique complet des connexions, appareils et modifications du compte : "
        "disponible uniquement dans un export Instagram, donc après récupération de l'accès.",
        "Nombre d'abonnés/abonnements a une date passee : Instagram ne publié que la "
        "valeur actuelle ; seules d'anciennes captures ou d'anciens exports permettent "
        "d'en connaitre une valeur passee.",
        "Adresse e-mail et numéro de téléphone associes au compte : lisibles seulement "
        "dans un export Instagram ou dans l'interface du compte, donc après récupération "
        "de l'acces.",
        "Contenus supprimés il y a plus de 30 jours : au-delà, Instagram indique ne plus "
        "les conserver, même pour le propriétaire du compte.",
    ]
