"""Détection de la nature d'un fichier d'export Instagram/Meta.

Règle de conception : ne jamais dependre d'un seul nom de fichier. La
détection combine quatre signaux, dans cet ordre de fiabilite :
  1. la structure JSON (clés reellement présentes) ;
  2. le chemin complet dans l'archive ;
  3. le nom du fichier ;
  4. le contenu HTML (titres, liens, marqueurs).
Meta renommé regulierement ses dossiers ; c'est pourquoi la détection par
clés JSON prime sur toute détection par nom.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..utils import read_text_file

# ---------------------------------------------------------------------------
# Categories normalisees
# ---------------------------------------------------------------------------
CAT_MESSAGES = "messages"
CAT_FOLLOWERS = "followers"
CAT_FOLLOWING = "following"
CAT_PENDING_SENT = "pending_sent"
CAT_PENDING_RECEIVED = "pending_received"
CAT_BLOCKED = "blocked"
CAT_RECENTLY_UNFOLLOWED = "recently_unfollowed"
CAT_RECENTLY_FOLLOWED = "recently_followed"
CAT_CLOSE_FRIENDS = "close_friends"
CAT_REMOVED_SUGGESTIONS = "removed_suggestions"
CAT_CONTACTS = "contacts"
CAT_PROFILE = "profile"
CAT_ACCOUNT_ACTIVITY = "account_activity"
CAT_LOGIN_ACTIVITY = "login_activity"
CAT_DEVICES = "devices"
CAT_LIKES = "likes"
CAT_COMMENTS = "comments"
CAT_SEARCHES = "searches"
CAT_POSTS = "posts"
CAT_STORIES = "stories"
CAT_ARCHIVED = "archived"
CAT_MEDIA_FILE = "media_file"
CAT_ACCOUNT_HISTORY = "account_history"
CAT_ARCHIVE = "archive"
CAT_UNKNOWN = "unknown"

# Cles JSON caracteristiques -> categorie. Source de verite principale.
JSON_KEY_MAP: dict[str, str] = {
    "relationships_followers": CAT_FOLLOWERS,
    "relationships_following": CAT_FOLLOWING,
    "relationships_follow_requests_sent": CAT_PENDING_SENT,
    "relationships_follow_requests_received": CAT_PENDING_RECEIVED,
    "relationships_permanent_follow_requests": CAT_PENDING_SENT,
    "relationships_blocked_users": CAT_BLOCKED,
    "relationships_unfollowed_users": CAT_RECENTLY_UNFOLLOWED,
    "relationships_dismissed_suggested_users": CAT_REMOVED_SUGGESTIONS,
    "relationships_close_friends": CAT_CLOSE_FRIENDS,
    "relationships_hide_stories_from": CAT_REMOVED_SUGGESTIONS,
    "relationships_feed_aware_accounts": CAT_RECENTLY_FOLLOWED,
    "relationships_syncing_contacts": CAT_CONTACTS,
    "contacts_contact_info": CAT_CONTACTS,
    "profile_user": CAT_PROFILE,
    "profile_profile_change": CAT_PROFILE,
    "profile_account_insights": CAT_PROFILE,
    "account_history_login_history": CAT_LOGIN_ACTIVITY,
    "account_history_logout_history": CAT_LOGIN_ACTIVITY,
    "account_history_registration_info": CAT_ACCOUNT_HISTORY,
    "account_history_account_active_status": CAT_ACCOUNT_HISTORY,
    "devices_devices": CAT_DEVICES,
    "devices_cameras": CAT_DEVICES,
    "likes_media_likes": CAT_LIKES,
    "likes_comment_likes": CAT_LIKES,
    "comments_media_comments": CAT_COMMENTS,
    "comments_reels_comments": CAT_COMMENTS,
    "searches_keyword": CAT_SEARCHES,
    "searches_user": CAT_SEARCHES,
    "ig_search_history": CAT_SEARCHES,
    "ig_archived_post_media": CAT_ARCHIVED,
    "ig_stories": CAT_STORIES,
    "ig_reels_media": CAT_POSTS,
    "ig_profile_picture": CAT_PROFILE,
    "ig_other_media": CAT_MEDIA_FILE,
    "impressions_history_posts_seen": CAT_ACCOUNT_ACTIVITY,
    "checkout_saved_information": CAT_ACCOUNT_ACTIVITY,
}

# Fragments de chemin -> categorie (signal secondaire)
PATH_HINTS: tuple[tuple[str, str], ...] = (
    ("messages/inbox", CAT_MESSAGES),
    ("messages\\inbox", CAT_MESSAGES),
    ("message_requests", CAT_MESSAGES),
    ("archived_threads", CAT_MESSAGES),
    ("filtered_threads", CAT_MESSAGES),
    ("secret_conversations", CAT_MESSAGES),
    ("inbox", CAT_MESSAGES),
    ("pending_follow_requests", CAT_PENDING_SENT),
    ("follow_requests_you", CAT_PENDING_RECEIVED),
    ("recently_unfollowed", CAT_RECENTLY_UNFOLLOWED),
    ("recently_followed", CAT_RECENTLY_FOLLOWED),
    ("close_friends", CAT_CLOSE_FRIENDS),
    ("blocked_accounts", CAT_BLOCKED),
    ("blocked_profiles", CAT_BLOCKED),
    ("followers_and_following", CAT_UNKNOWN),
    ("login_activity", CAT_LOGIN_ACTIVITY),
    ("login_and_profile_creation", CAT_LOGIN_ACTIVITY),
    ("logins", CAT_LOGIN_ACTIVITY),
    ("devices", CAT_DEVICES),
    ("recent_searches", CAT_SEARCHES),
    ("personal_information", CAT_PROFILE),
    ("account_information", CAT_PROFILE),
    ("archived_posts", CAT_ARCHIVED),
    ("stories", CAT_STORIES),
    ("posts", CAT_POSTS),
    ("liked_posts", CAT_LIKES),
    ("liked_comments", CAT_LIKES),
    ("comments", CAT_COMMENTS),
    ("contacts", CAT_CONTACTS),
    ("account_activity", CAT_ACCOUNT_ACTIVITY),
    ("your_activity_off_meta", CAT_ACCOUNT_ACTIVITY),
)

# Noms de fichier -> categorie (signal tertiaire)
NAME_HINTS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"^message_\d+\.(json|html)$"), CAT_MESSAGES),
    (re.compile(r"^follower(s)?(_\d+)?\.(json|html)$"), CAT_FOLLOWERS),
    (re.compile(r"^following(_\d+)?\.(json|html)$"), CAT_FOLLOWING),
    (re.compile(r"^followers_and_following\.(json|html)$"), CAT_FOLLOWERS),
    (re.compile(r"^blocked_"), CAT_BLOCKED),
    (re.compile(r"^close_friends"), CAT_CLOSE_FRIENDS),
    (re.compile(r"^pending_"), CAT_PENDING_SENT),
    (re.compile(r"^posts_\d+\."), CAT_POSTS),
    (re.compile(r"^stories\."), CAT_STORIES),
    (re.compile(r"^archived_posts\."), CAT_ARCHIVED),
    (re.compile(r"^profile_photos\."), CAT_PROFILE),
    (re.compile(r"^personal_information\."), CAT_PROFILE),
    (re.compile(r"^account_information\."), CAT_PROFILE),
    (re.compile(r"^login_activity\."), CAT_LOGIN_ACTIVITY),
    (re.compile(r"^logins?\."), CAT_LOGIN_ACTIVITY),
    (re.compile(r"^devices\."), CAT_DEVICES),
    (re.compile(r"^liked_"), CAT_LIKES),
    (re.compile(r"comments"), CAT_COMMENTS),
    (re.compile(r"searches"), CAT_SEARCHES),
    (re.compile(r"^contacts"), CAT_CONTACTS),
)

# Marqueurs HTML (titre de page ou en-tete) -> categorie
HTML_HINTS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"followers", re.I), CAT_FOLLOWERS),
    (re.compile(r"abonn[ée]s", re.I), CAT_FOLLOWERS),
    (re.compile(r"following", re.I), CAT_FOLLOWING),
    (re.compile(r"abonnements", re.I), CAT_FOLLOWING),
    (re.compile(r"blocked", re.I), CAT_BLOCKED),
    (re.compile(r"login activity|activit[ée] de connexion", re.I), CAT_LOGIN_ACTIVITY),
    (re.compile(r"personal information|informations personnelles", re.I), CAT_PROFILE),
    (re.compile(r"liked posts|j.aime", re.I), CAT_LIKES),
    (re.compile(r"comments|commentaires", re.I), CAT_COMMENTS),
)

MESSAGE_JSON_MARKERS = ("participants", "messages")


@dataclass
class Detection:
    category: str
    confidence: str          # forte | moyenne | faible
    signal: str              # d'ou vient la detection
    payload: Any = None      # JSON deja charge (evite une double lecture)
    note: str | None = None


def _json_categories(data: Any) -> list[tuple[str, str]]:
    """Retourne [(catégorie, clé declenchante)] a partir des clés racine."""
    found: list[tuple[str, str]] = []
    if isinstance(data, dict):
        for key in data:
            if key in JSON_KEY_MAP:
                found.append((JSON_KEY_MAP[key], key))
        if not found:
            # certaines versions imbriquent sous une cle unique
            for key, value in data.items():
                if isinstance(value, dict):
                    for subkey in value:
                        if subkey in JSON_KEY_MAP:
                            found.append((JSON_KEY_MAP[subkey], f"{key}.{subkey}"))
    return found


def _looks_like_message_json(data: Any) -> bool:
    if not isinstance(data, dict):
        return False
    if not all(k in data for k in MESSAGE_JSON_MARKERS):
        return False
    msgs = data.get("messages")
    return isinstance(msgs, list)


def _looks_like_relationship_list(data: Any) -> bool:
    """Liste brute de {"string_list_data": [...]} : forme des exports abonnés."""
    if not isinstance(data, list) or not data:
        return False
    sample = data[0]
    return isinstance(sample, dict) and "string_list_data" in sample


def _path_category(path_lower: str) -> tuple[str, str] | None:
    for fragment, category in PATH_HINTS:
        if fragment in path_lower and category != CAT_UNKNOWN:
            return category, fragment
    return None


def _name_category(name_lower: str) -> tuple[str, str] | None:
    for pattern, category in NAME_HINTS:
        if pattern.search(name_lower):
            return category, pattern.pattern
    return None


def detect_file(path: Path, root: Path | None = None) -> Detection:
    """Determine la catégorie d'un fichier de l'export."""
    suffix = path.suffix.lower()
    rel = path.as_posix()
    if root is not None:
        try:
            rel = path.resolve().relative_to(root.resolve()).as_posix()
        except ValueError:
            rel = path.as_posix()
    rel_lower = rel.lower()
    name_lower = path.name.lower()

    from ..utils import media_kind

    if media_kind(path):
        return Detection(CAT_MEDIA_FILE, "forte", "extension media")

    if suffix in {".zip", ".7z", ".rar", ".tar", ".gz"}:
        return Detection(CAT_ARCHIVE, "forte", "archive fournie par l'utilisateur")

    if suffix == ".json":
        try:
            data = json.loads(read_text_file(path))
        except (ValueError, OSError) as exc:
            return Detection(CAT_UNKNOWN, "faible", "json illisible", note=str(exc))

        if _looks_like_message_json(data):
            return Detection(CAT_MESSAGES, "forte", "cles JSON participants/messages", data)

        json_cats = _json_categories(data)
        if json_cats:
            category, key = json_cats[0]
            return Detection(category, "forte", f"clé JSON « {key} »", data)

        if _looks_like_relationship_list(data):
            # Liste anonyme : le chemin/nom tranche entre followers et following
            guess = _path_category(rel_lower) or _name_category(name_lower)
            if guess:
                return Detection(
                    guess[0], "moyenne", f"liste string_list_data + chemin ({guess[1]})", data
                )
            return Detection(
                CAT_FOLLOWERS,
                "faible",
                "liste string_list_data sans indice de chemin",
                data,
                note="Type de relation incertain : vérifié manuellement.",
            )

        guess = _path_category(rel_lower) or _name_category(name_lower)
        if guess:
            return Detection(guess[0], "moyenne", f"chemin/nom ({guess[1]})", data)
        return Detection(CAT_UNKNOWN, "faible", "structure JSON non reconnue", data)

    if suffix in {".html", ".htm"}:
        head = read_text_file(path, limit=200_000)
        guess = _path_category(rel_lower) or _name_category(name_lower)
        if guess:
            return Detection(guess[0], "moyenne", f"chemin/nom ({guess[1]})")
        for pattern, category in HTML_HINTS:
            if pattern.search(head[:20000]):
                return Detection(category, "faible", f"marqueur HTML « {pattern.pattern} »")
        return Detection(CAT_UNKNOWN, "faible", "HTML non reconnu")

    guess = _path_category(rel_lower) or _name_category(name_lower)
    if guess:
        return Detection(guess[0], "faible", f"chemin/nom ({guess[1]})")
    return Detection(CAT_UNKNOWN, "faible", "aucun indice")


RELATION_CATEGORIES = {
    CAT_FOLLOWERS,
    CAT_FOLLOWING,
    CAT_PENDING_SENT,
    CAT_PENDING_RECEIVED,
    CAT_BLOCKED,
    CAT_RECENTLY_UNFOLLOWED,
    CAT_RECENTLY_FOLLOWED,
    CAT_CLOSE_FRIENDS,
    CAT_REMOVED_SUGGESTIONS,
}

RELATION_LABELS = {
    CAT_FOLLOWERS: "Abonnés",
    CAT_FOLLOWING: "Abonnements",
    CAT_PENDING_SENT: "Demandes envoyées en attente",
    CAT_PENDING_RECEIVED: "Demandes reçues en attente",
    CAT_BLOCKED: "Comptes bloqués",
    CAT_RECENTLY_UNFOLLOWED: "Désabonnements récents",
    CAT_RECENTLY_FOLLOWED: "Abonnements récents",
    CAT_CLOSE_FRIENDS: "Amis proches",
    CAT_REMOVED_SUGGESTIONS: "Suggestions masquées",
    CAT_CONTACTS: "Contacts synchronisés",
}

CATEGORY_LABELS = dict(RELATION_LABELS)
CATEGORY_LABELS.update(
    {
        CAT_MESSAGES: "Conversations",
        CAT_PROFILE: "Profil",
        CAT_ACCOUNT_ACTIVITY: "Activité du compte",
        CAT_LOGIN_ACTIVITY: "Connexions",
        CAT_DEVICES: "Appareils",
        CAT_LIKES: "J'aime",
        CAT_COMMENTS: "Commentaires",
        CAT_SEARCHES: "Recherches",
        CAT_POSTS: "Publications",
        CAT_STORIES: "Stories",
        CAT_ARCHIVED: "Contenu archivé",
        CAT_MEDIA_FILE: "Média",
        CAT_ACCOUNT_HISTORY: "Historique du compte",
        CAT_ARCHIVE: "Archive fournie",
        CAT_UNKNOWN: "Non classé",
    }
)
