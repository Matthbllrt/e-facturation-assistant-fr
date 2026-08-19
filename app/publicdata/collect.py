"""Collecte des informations PUBLIQUES d'un profil (section 2).

Ce que fait ce module :
  * une requête HTTP simple, non authentifiee, sur l'URL publique du profil,
    exactement comme un visiteur non connecte ;
  * la lecture des balises publiques (Open Graph, JSON-LD) présentes dans la
    réponse ;
  * l'enregistrement horodate de la réponse brute, de l'URL et du code HTTP.

Ce que ce module ne fait JAMAIS :
  * envoyer des identifiants, un cookie ou un jeton de session ;
  * utiliser une API privée de Meta ou une clé d'application ;
  * reessayer en boucle pour contourner une limitation ;
  * pretendre disposer d'une information privée.

Si Instagram repond par un mur de connexion ou une limitation, le blocage est
enregistré tel quel et l'interface propose des voies manuelles (coller le HTML
de la page, déposer une capture d'écran, un PDF ou un JSON).
"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path
from typing import Any

from .. import db as dbmod
from ..cases import Case
from ..utils import sha256_text, utcnow_iso

PROFILE_URL = "https://www.instagram.com/{username}/"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36"
)
REQUEST_TIMEOUT = 20
MIN_SECONDS_BETWEEN_CALLS = 20

_LAST_CALL: dict[str, float] = {}

_OG_RE = re.compile(
    r'<meta[^>]+property=["\']og:([a-z_:]+)["\'][^>]+content=["\'](.*?)["\']', re.I | re.S
)
_OG_REVERSE_RE = re.compile(
    r'<meta[^>]+content=["\'](.*?)["\'][^>]+property=["\']og:([a-z_:]+)["\']', re.I | re.S
)
_JSONLD_RE = re.compile(
    r'<script[^>]+type=["\']application/ld\+json["\'][^>]*>(.*?)</script>', re.I | re.S
)
_COUNT_RE = re.compile(
    r"([\d.,\s]+[KMkm]?)\s*(?:Followers|abonn[ée]s)[^\d]*([\d.,\s]+[KMkm]?)\s*"
    r"(?:Following|abonnements)[^\d]*([\d.,\s]+[KMkm]?)\s*(?:Posts|publications)",
    re.I,
)
_LOGIN_WALL_MARKERS = (
    "loginform",
    "login_and_signup",
    "accounts/login",
    "connectez-vous",
    "log in to instagram",
)


def _parse_count(text: str | None) -> int | None:
    if not text:
        return None
    cleaned = text.strip().replace(" ", "").replace("\xa0", "").replace(" ", "")
    multiplier = 1
    if cleaned[-1:].lower() == "k":
        multiplier, cleaned = 1000, cleaned[:-1]
    elif cleaned[-1:].lower() == "m":
        multiplier, cleaned = 1_000_000, cleaned[:-1]
    if multiplier > 1:
        # Avec un suffixe K/M, la virgule est un separateur decimal (« 1,2 K »).
        cleaned = cleaned.replace(",", ".")
    else:
        # Sans suffixe, virgules et points sont des separateurs de milliers
        # (« 1,234 » en anglais, « 1.234 » en francais valent tous deux 1234).
        cleaned = cleaned.replace(",", "").replace(".", "")
    try:
        return int(float(cleaned) * multiplier)
    except ValueError:
        return None


def extract_public_fields(html: str, username: str | None = None) -> dict[str, Any]:
    """Extrait les champs publics d'une page de profil (HTML ou JSON collé)."""
    fields: dict[str, Any] = {
        "username": username,
        "full_name": None,
        "biography": None,
        "external_url": None,
        "followers_count": None,
        "following_count": None,
        "posts_count": None,
        "profile_pic_url": None,
        "is_private": None,
        "extraction_notes": [],
    }
    if not html:
        return fields

    text = html.strip()
    if text.startswith("{"):
        try:
            data = json.loads(text)
            user = (
                data.get("graphql", {}).get("user")
                or data.get("data", {}).get("user")
                or data.get("user")
                or data
            )
            if isinstance(user, dict):
                fields["username"] = user.get("username") or fields["username"]
                fields["full_name"] = user.get("full_name")
                fields["biography"] = user.get("biography")
                fields["external_url"] = user.get("external_url")
                followers = user.get("edge_followed_by") or {}
                following = user.get("edge_follow") or {}
                posts = user.get("edge_owner_to_timeline_media") or {}
                fields["followers_count"] = (
                    followers.get("count") if isinstance(followers, dict) else None
                ) or user.get("follower_count")
                fields["following_count"] = (
                    following.get("count") if isinstance(following, dict) else None
                ) or user.get("following_count")
                fields["posts_count"] = (
                    posts.get("count") if isinstance(posts, dict) else None
                ) or user.get("media_count")
                fields["profile_pic_url"] = user.get("profile_pic_url_hd") or user.get(
                    "profile_pic_url"
                )
                fields["is_private"] = user.get("is_private")
                fields["extraction_notes"].append("Champs lus dans un JSON fourni manuellement.")
                return fields
        except ValueError:
            fields["extraction_notes"].append("Contenu JSON illisible : lecture en mode HTML.")

    og: dict[str, str] = {}
    for key, value in _OG_RE.findall(html):
        og.setdefault(key.lower(), value)
    for value, key in _OG_REVERSE_RE.findall(html):
        og.setdefault(key.lower(), value)

    if og.get("title"):
        title = og["title"]
        fields["extraction_notes"].append("Balise Open Graph « og:title » lue.")
        match = re.match(r"(.*?)\s*\(@([A-Za-z0-9._]+)\)", title)
        if match:
            fields["full_name"] = match.group(1).strip() or None
            fields["username"] = match.group(2)
        elif "@" in title:
            fields["full_name"] = title.split("@")[0].strip(" •-") or None
    if og.get("image"):
        fields["profile_pic_url"] = og["image"]
        fields["extraction_notes"].append("Photo de profil publique référencée par « og:image ».")
    description = og.get("description")
    if description:
        fields["extraction_notes"].append("Balise Open Graph « og:description » lue.")
        counts = _COUNT_RE.search(description)
        if counts:
            fields["followers_count"] = _parse_count(counts.group(1))
            fields["following_count"] = _parse_count(counts.group(2))
            fields["posts_count"] = _parse_count(counts.group(3))
        bio_part = description.split(" - ", 1)
        if len(bio_part) == 2 and not counts:
            fields["biography"] = bio_part[1].strip() or None
        elif '"' in description:
            quoted = re.search(r'"(.*?)"', description, re.S)
            if quoted:
                fields["biography"] = quoted.group(1).strip() or None

    for block in _JSONLD_RE.findall(html):
        try:
            data = json.loads(block)
        except ValueError:
            continue
        if isinstance(data, dict):
            fields["full_name"] = fields["full_name"] or data.get("name")
            fields["biography"] = fields["biography"] or data.get("description")
            fields["extraction_notes"].append("Bloc JSON-LD public lu.")

    lowered = html.lower()
    if any(marker in lowered for marker in _LOGIN_WALL_MARKERS):
        fields["extraction_notes"].append(
            "La page contient un formulaire de connexion : Instagram limite l'accès "
            "public. Aucune tentative de contournement n'a été effectuée."
        )
    return fields


def _store(
    case: Case,
    method: str,
    url: str | None,
    http_status: int | None,
    fields: dict[str, Any],
    raw_payload: str | None,
    note: str | None,
    file_id: int | None = None,
) -> int:
    return dbmod.insert(
        case.conn,
        "public_profile",
        {
            "case_id": case.case_id,
            "collected_at": utcnow_iso(),
            "method": method,
            "url": url,
            "http_status": http_status,
            "username": fields.get("username"),
            "full_name": fields.get("full_name"),
            "biography": fields.get("biography"),
            "external_url": fields.get("external_url"),
            "followers_count": fields.get("followers_count"),
            "following_count": fields.get("following_count"),
            "posts_count": fields.get("posts_count"),
            "profile_pic_url": fields.get("profile_pic_url"),
            "is_private": 1 if fields.get("is_private") else (0 if fields.get("is_private") is False else None),
            "raw_payload": raw_payload,
            "file_id": file_id,
            "note": note,
        },
    )


def collect_public(case: Case, save_html: bool = True) -> dict[str, Any]:
    """Interroge une seule fois l'URL publique du profil."""
    case.require_writable("collecte publique")
    username = case.username
    url = PROFILE_URL.format(username=username)

    last = _LAST_CALL.get(username, 0.0)
    wait = MIN_SECONDS_BETWEEN_CALLS - (time.time() - last)
    if wait > 0:
        return {
            "ok": False,
            "blocked": False,
            "url": url,
            "message": (
                f"Collecte trop rapprochee. Attends encore {int(wait) + 1} seconde(s) : "
                "l'outil limite volontairement le rythme des requêtes."
            ),
        }

    try:
        import requests
    except ImportError:
        return {
            "ok": False,
            "blocked": False,
            "url": url,
            "message": "Module « requests » absent. Utilise la saisie manuelle du HTML.",
        }

    _LAST_CALL[username] = time.time()
    try:
        response = requests.get(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
                "Accept": "text/html,application/xhtml+xml",
            },
            timeout=REQUEST_TIMEOUT,
            allow_redirects=True,
        )
    except Exception as exc:
        return {
            "ok": False,
            "blocked": False,
            "url": url,
            "error": f"{type(exc).__name__}: {exc}",
            "message": (
                "La requête publique n'a pas abouti (reseau indisponible, hors ligne ou "
                "accès filtre). Tu peux coller manuellement le HTML de la page publique."
            ),
        }

    html = response.text or ""
    fields = extract_public_fields(html, username=username)
    blocked = response.status_code in {401, 403, 429} or any(
        marker in html.lower() for marker in _LOGIN_WALL_MARKERS
    )
    note = None
    if blocked:
        note = (
            f"Instagram a limite l'accès public (code HTTP {response.status_code}). "
            "Aucune tentative de contournement n'a été effectuée. "
            "Les informations publiques peuvent être fournies manuellement."
        )
    elif response.status_code == 404:
        note = (
            "La page publique renvoie « introuvable » (HTTP 404) : le compte peut avoir été "
            "supprimé, désactivé, renommé, ou l'accès public restreint. "
            "Ce constat est enregistré tel quel, sans interprétation."
        )

    file_id = None
    if save_html and html:
        case.public_dir.mkdir(parents=True, exist_ok=True)
        stamp = utcnow_iso().replace(":", "-")
        target = case.public_dir / f"profil_{username}_{stamp}.html"
        target.write_text(html, encoding="utf-8")
        from ..importers.ingest import register_file

        file_id = register_file(case, None, target, category="public_capture", original_path=url)
        case.conn.commit()

    row_id = _store(
        case,
        method="http_public",
        url=url,
        http_status=response.status_code,
        fields=fields,
        raw_payload=html[:400_000],
        note=note,
        file_id=file_id,
    )
    case.conn.commit()
    case.audit("collecte_publique", f"{url} -> HTTP {response.status_code}")

    from ..analysis import search as search_mod

    search_mod.reindex(case)

    return {
        "ok": not blocked,
        "blocked": blocked,
        "id": row_id,
        "url": url,
        "http_status": response.status_code,
        "collected_at": utcnow_iso(),
        "fields": fields,
        "note": note,
        "html_saved": str(target) if save_html and html else None,
        "sha256_reponse": sha256_text(html) if html else None,
        "message": note
        or "Collecte publique enregistrée. Seules des informations publiques ont été lues.",
    }


def add_manual(
    case: Case,
    *,
    content: str | None = None,
    url: str | None = None,
    note: str | None = None,
    file_id: int | None = None,
    method: str = "manual_html",
) -> dict[str, Any]:
    """Enregistre une collecte publique fournie manuellement par l'utilisateur."""
    case.require_writable("ajout d'une collecte publique manuelle")
    fields = extract_public_fields(content or "", username=case.username)
    row_id = _store(
        case,
        method=method,
        url=url,
        http_status=None,
        fields=fields,
        raw_payload=(content or "")[:400_000],
        note=note,
        file_id=file_id,
    )
    case.conn.commit()
    case.audit("collecte_publique_manuelle", url or method)
    from ..analysis import search as search_mod

    search_mod.reindex(case)
    return {"ok": True, "id": row_id, "fields": fields, "method": method, "note": note}


def history(case: Case) -> list[dict]:
    rows = dbmod.query_all(
        case.conn,
        "SELECT id, collected_at, method, url, http_status, username, full_name, biography, "
        "       external_url, followers_count, following_count, posts_count, profile_pic_url, "
        "       is_private, note, file_id FROM public_profile WHERE case_id = ? "
        "ORDER BY collected_at DESC",
        (case.case_id,),
    )
    return rows


def latest(case: Case) -> dict | None:
    rows = history(case)
    return rows[0] if rows else None
