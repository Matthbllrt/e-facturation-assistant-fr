"""Generateur d'exports Instagram factices, fideles aux structures reelles.

Sert aux tests automatises : deux exports a des dates differentes, au format
JSON et au format HTML, avec medias, message retire, reaction orpheline,
media orphelin et serie de fichiers incomplete.
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

OWNER = "mon_compte"
OWNER_NAME = "Mon Compte"

# Horodatage commun a tous les exports pour l'historique des conversations.
MESSAGE_BASE_TS = 1_700_000_000

# Texte volontairement mal encode (mojibake Meta : UTF-8 relu en latin-1).
MOJIBAKE_TEXT = "Tu as les photos de la soirÃ©e ?"
MOJIBAKE_REACTION = "â¤"


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")


def _slist(username: str, timestamp: int | None, value: str | None = None) -> dict:
    item: dict = {"href": f"https://www.instagram.com/{username}", "value": value or username}
    if timestamp is not None:
        item["timestamp"] = timestamp
    return {"title": "", "media_list_data": [], "string_list_data": [item]}


def build_export_json(
    root: Path,
    *,
    year: int,
    followers: list[str],
    following: list[str],
    extra_messages: bool = False,
) -> Path:
    """Cree un export Instagram au format JSON dans `root`."""
    root.mkdir(parents=True, exist_ok=True)
    base_ts = {2024: 1_700_000_000, 2025: 1_730_000_000}.get(year, 1_700_000_000)
    # L'historique des conversations est le meme d'un export a l'autre : seuls
    # les messages recents s'ajoutent. C'est le comportement reel d'Instagram
    # et c'est ce qui rend la comparaison d'exports significative.
    msg_ts = MESSAGE_BASE_TS

    # --- profil ---------------------------------------------------------
    _write_json(
        root / "personal_information" / "personal_information" / "personal_information.json",
        {
            "profile_user": [
                {
                    "media_map_data": {
                        "Profile Photo": {
                            "uri": "media/profile/photo.jpg",
                            "creation_timestamp": base_ts - 100000,
                        }
                    },
                    "string_map_data": {
                        "Name": {"value": OWNER_NAME},
                        "Username": {"value": OWNER},
                        "Email": {"value": "ancienne.adresse@example.com"},
                        "Bio": {"value": "Photographe amateur"},
                        "Website": {"value": "https://example.com"},
                        "Private Account": {"value": "False"},
                    },
                }
            ]
        },
    )

    # --- relations ------------------------------------------------------
    conn = root / "connections" / "followers_and_following"
    _write_json(
        conn / "followers_1.json",
        [_slist(u, base_ts - i * 86400) for i, u in enumerate(followers)],
    )
    _write_json(
        conn / "following.json",
        {"relationships_following": [_slist(u, base_ts - i * 43200) for i, u in enumerate(following)]},
    )
    # Demandes en attente : sans timestamp reel (doit rester « Date inconnue »)
    _write_json(
        conn / "pending_follow_requests.json",
        {"relationships_follow_requests_sent": [_slist("compte_prive_x", None)]},
    )
    _write_json(
        conn / "blocked_accounts.json",
        {"relationships_blocked_users": [_slist("harceleur_y", base_ts - 500000)]},
    )

    # --- conversations --------------------------------------------------
    inbox = root / "your_instagram_activity" / "messages" / "inbox"
    thread1 = inbox / "amie_x_17841400000000001"
    messages1 = [
        {
            "sender_name": "Amie X",
            "timestamp_ms": (msg_ts + 3600) * 1000,
            "content": MOJIBAKE_TEXT,
            "reactions": [{"reaction": MOJIBAKE_REACTION, "actor": OWNER_NAME}],
        },
        {
            "sender_name": OWNER_NAME,
            "timestamp_ms": (msg_ts + 3000) * 1000,
            "content": "Oui je te les envoie",
        },
        {
            "sender_name": OWNER_NAME,
            "timestamp_ms": (msg_ts + 2400) * 1000,
            "photos": [
                {
                    "uri": "your_instagram_activity/messages/inbox/amie_x_17841400000000001/photos/photo_1.jpg",
                    "creation_timestamp": msg_ts + 2400,
                }
            ],
        },
        {
            "sender_name": "Amie X",
            "timestamp_ms": (msg_ts + 1800) * 1000,
            "content": "",
            "is_unsent": True,
        },
        {
            "sender_name": "Amie X",
            "timestamp_ms": (msg_ts + 1200) * 1000,
            "content": "",
            "reactions": [{"reaction": "\U0001f44d", "actor": OWNER_NAME}],
        },
        {
            "sender_name": OWNER_NAME,
            "timestamp_ms": (msg_ts + 600) * 1000,
            "share": {"link": "https://www.instagram.com/p/ABC123/", "share_text": "Regarde ca"},
        },
        {
            "sender_name": "Amie X",
            "timestamp_ms": (msg_ts + 60) * 1000,
            "content": "Salut !",
        },
    ]
    if extra_messages:
        # Un ancien message n'est plus present dans l'export recent : c'est
        # exactement le cas que la comparaison doit savoir signaler.
        messages1 = [m for m in messages1 if m.get("content") != "Oui je te les envoie"]
        messages1.insert(
            0,
            {
                "sender_name": "Amie X",
                "timestamp_ms": (msg_ts + 90000) * 1000,
                "content": "Message ajoute dans l'export le plus recent",
            },
        )
    _write_json(
        thread1 / "message_1.json",
        {
            "participants": [{"name": "Amie X"}, {"name": OWNER_NAME}],
            "messages": messages1,
            "title": "Amie X",
            "is_still_participant": True,
            "thread_path": "inbox/amie_x_17841400000000001",
            "magic_words": [],
        },
    )
    photo = thread1 / "photos" / "photo_1.jpg"
    photo.parent.mkdir(parents=True, exist_ok=True)
    photo.write_bytes(b"\xff\xd8\xff\xe0FAKEJPEG-1" + b"\x00" * 64)
    # media orphelin : present dans le dossier mais reference par aucun message
    (thread1 / "photos" / "photo_orpheline.jpg").write_bytes(
        b"\xff\xd8\xff\xe0FAKEJPEG-2" + b"\x00" * 64
    )

    # Fil de groupe, serie incomplete : message_1 absent, 2 et 3 presents
    thread2 = inbox / "groupe_photo_17841400000000002"
    for index, offset in ((2, 7200), (3, 10800)):
        _write_json(
            thread2 / f"message_{index}.json",
            {
                "participants": [{"name": "Amie X"}, {"name": "Bob Y"}, {"name": OWNER_NAME}],
                "messages": [
                    {
                        "sender_name": "Bob Y",
                        "timestamp_ms": (msg_ts + offset) * 1000,
                        "content": f"Message de groupe {index}",
                    }
                ],
                "title": "Groupe photo",
                "thread_path": "inbox/groupe_photo_17841400000000002",
            },
        )

    if extra_messages:
        thread3 = inbox / "nouveau_contact_17841400000000003"
        _write_json(
            thread3 / "message_1.json",
            {
                "participants": [{"name": "Nouveau Contact"}, {"name": OWNER_NAME}],
                "messages": [
                    {
                        "sender_name": "Nouveau Contact",
                        "timestamp_ms": (msg_ts + 200000) * 1000,
                        "content": "Bonjour, on s'est croises hier",
                    }
                ],
                "title": "Nouveau Contact",
                "thread_path": "inbox/nouveau_contact_17841400000000003",
            },
        )

    # --- activite -------------------------------------------------------
    _write_json(
        root / "your_instagram_activity" / "likes" / "liked_posts.json",
        {
            "likes_media_likes": [
                {
                    "title": "amie_x",
                    "string_list_data": [
                        {
                            "href": "https://www.instagram.com/p/XYZ789/",
                            "value": "\U0001f44d",
                            "timestamp": base_ts - 20000,
                        }
                    ],
                }
            ]
        },
    )
    _write_json(
        root / "your_instagram_activity" / "comments" / "post_comments_1.json",
        [
            {
                "string_map_data": {
                    "Comment": {"value": "Superbe photo"},
                    "Media Owner": {"value": "amie_x"},
                    "Time": {"timestamp": base_ts - 15000},
                }
            }
        ],
    )
    _write_json(
        root / "security_and_login_information" / "login_and_profile_creation" / "login_activity.json",
        {
            "account_history_login_history": [
                {
                    "title": "Connexion",
                    "string_map_data": {
                        "Time": {"timestamp": base_ts - 5000},
                        "IP Address": {"value": "203.0.113.10"},
                        "User Agent": {"value": "Instagram Android"},
                    },
                }
            ]
        },
    )
    _write_json(
        root / "logged_information" / "recent_searches" / "word_or_phrase_searches.json",
        {
            "searches_keyword": [
                {
                    "string_map_data": {
                        "Search": {"value": "photographie"},
                        "Time": {"timestamp": base_ts - 9000},
                    }
                }
            ]
        },
    )
    _write_json(
        root / "your_instagram_activity" / "content" / "posts_1.json",
        [
            {
                "media": [
                    {
                        "uri": "media/posts/202401/photo_post.jpg",
                        "creation_timestamp": base_ts - 300000,
                        "title": "Coucher de soleil",
                    }
                ],
                "title": "Coucher de soleil",
                "creation_timestamp": base_ts - 300000,
            }
        ],
    )
    post_media = root / "media" / "posts" / "202401" / "photo_post.jpg"
    post_media.parent.mkdir(parents=True, exist_ok=True)
    post_media.write_bytes(b"\xff\xd8\xff\xe0POSTJPEG" + b"\x00" * 64)
    profile_media = root / "media" / "profile" / "photo.jpg"
    profile_media.parent.mkdir(parents=True, exist_ok=True)
    profile_media.write_bytes(b"\xff\xd8\xff\xe0PROFILE" + b"\x00" * 64)
    return root


HTML_THREAD = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>Amie X</title></head>
<body><div class="_a706"><div class="_a705"><div class="pam _3-95 _2ph- _a6-g uiBoxWhite noborder">
<div class="_3-95 _2pim _a6-h _a6-i">Amie X</div>
<div class="_3-95 _a6-p"><div><div></div><div>Salut ! Comment tu vas ?</div><div></div></div></div>
<div class="_3-94 _a6-o">May 12, 2025 2:37:00 PM</div></div>
<div class="pam _3-95 _2ph- _a6-g uiBoxWhite noborder">
<div class="_3-95 _2pim _a6-h _a6-i">Mon Compte</div>
<div class="_3-95 _a6-p"><div><div></div><div>Ca va bien, merci !</div><div></div></div></div>
<div class="_3-94 _a6-o">May 12, 2025 2:40:00 PM</div></div>
<div class="pam _3-95 _2ph- _a6-g uiBoxWhite noborder">
<div class="_3-95 _2pim _a6-h _a6-i">Amie X</div>
<div class="_3-95 _a6-p"><div><div></div><div><img src="photos/img_html.jpg" class="_a6_o"></div></div></div>
<div class="_3-94 _a6-o">May 12, 2025 2:45:00 PM</div></div>
</div></div></body></html>"""

HTML_FOLLOWERS = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>Followers</title></head>
<body><div class="_a706"><div class="pam _3-95 _2ph- _a6-g uiBoxWhite noborder">
<div class="_2pin"><a href="https://www.instagram.com/amie_x" target="_blank">amie_x</a></div>
<div class="_a72d">Mar 1, 2021, 12:00 PM</div></div>
<div class="pam _3-95 _2ph- _a6-g uiBoxWhite noborder">
<div class="_2pin"><a href="https://www.instagram.com/bob_y" target="_blank">bob_y</a></div>
<div class="_a72d">Apr 3, 2022, 9:15 AM</div></div>
</div></body></html>"""


def build_export_html(root: Path) -> Path:
    """Cree un export Instagram au format HTML."""
    thread = root / "messages" / "inbox" / "amie_x_17841400000000001"
    thread.mkdir(parents=True, exist_ok=True)
    (thread / "message_1.html").write_text(HTML_THREAD, encoding="utf-8")
    (thread / "photos").mkdir(exist_ok=True)
    (thread / "photos" / "img_html.jpg").write_bytes(b"\xff\xd8\xff\xe0HTMLJPEG" + b"\x00" * 32)
    followers = root / "connections" / "followers_and_following"
    followers.mkdir(parents=True, exist_ok=True)
    (followers / "followers_1.html").write_text(HTML_FOLLOWERS, encoding="utf-8")
    return root


def zip_export(source_dir: Path, zip_path: Path) -> Path:
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source_dir.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source_dir).as_posix())
    return zip_path


FOLLOWERS_2024 = ["amie_x", "bob_y", "carla_z", "compte_disparu"]
FOLLOWERS_2025 = ["amie_x", "bob_y", "carla_z", "nouveau_venu", "autre_nouveau"]
FOLLOWING_2024 = ["amie_x", "photographe_pro", "compte_desabonne"]
FOLLOWING_2025 = ["amie_x", "photographe_pro", "nouveau_suivi"]


def build_two_exports(base: Path) -> tuple[Path, Path]:
    export_a = build_export_json(
        base / "export_2024", year=2024, followers=FOLLOWERS_2024, following=FOLLOWING_2024
    )
    export_b = build_export_json(
        base / "export_2025",
        year=2025,
        followers=FOLLOWERS_2025,
        following=FOLLOWING_2025,
        extra_messages=True,
    )
    return export_a, export_b
