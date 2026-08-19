"""Mini-DOM tolerant pour les exports Instagram au format HTML.

Meta change frequemment les noms de classes CSS de ses exports HTML. On ne
s'appuie donc sur aucune classe : on reconstruit un arbre simple puis on
identifié les blocs par leur *forme* (un bloc message contient un expéditeur,
un contenu et une date analysable).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from typing import Iterator

from ..utils import parse_datetime

VOID_TAGS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
}

# Formats de date presents dans les exports HTML Meta (EN et FR)
_HTML_DATE_RE = re.compile(
    r"("
    r"[A-Z][a-z]{2,8}\s+\d{1,2},?\s+\d{4}[,\s]+\d{1,2}:\d{2}(:\d{2})?\s*(AM|PM|am|pm)?"
    r"|\d{1,2}\s+[a-zA-Zéûôa-z]{3,10}\.?\s+\d{4}[,\s]+\d{1,2}:\d{2}(:\d{2})?"
    r"|\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}(:\d{2})?"
    r"|\d{1,2}/\d{1,2}/\d{4}[,\s]+\d{1,2}:\d{2}(:\d{2})?\s*(AM|PM)?"
    r")"
)

_FR_MONTHS = {
    "janvier": "January", "janv": "January", "fevrier": "February", "février": "February",
    "fevr": "February", "mars": "March", "avril": "April", "avr": "April", "mai": "May",
    "juin": "June", "juillet": "July", "juil": "July", "aout": "August", "août": "August",
    "septembre": "September", "sept": "September", "octobre": "October", "oct": "October",
    "novembre": "November", "nov": "November", "decembre": "December",
    "décembre": "December", "dec": "December",
}


@dataclass
class Node:
    tag: str
    attrs: dict[str, str] = field(default_factory=dict)
    children: list["Node"] = field(default_factory=list)
    text: str = ""
    parent: "Node | None" = field(default=None, repr=False, compare=False)

    def iter_all(self) -> Iterator["Node"]:
        yield self
        for child in self.children:
            yield from child.iter_all()

    def all_text(self) -> str:
        parts: list[str] = []
        if self.text.strip():
            parts.append(self.text.strip())
        for child in self.children:
            sub = child.all_text()
            if sub:
                parts.append(sub)
        return " ".join(parts)

    def text_lines(self) -> list[str]:
        """Textes feuille, dans l'ordre du document."""
        out: list[str] = []
        if self.text.strip():
            out.append(self.text.strip())
        for child in self.children:
            out.extend(child.text_lines())
        return out

    def find_all(self, tag: str) -> list["Node"]:
        return [n for n in self.iter_all() if n.tag == tag]


class _TreeBuilder(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = Node("#root")
        self._stack: list[Node] = [self.root]

    def handle_starttag(self, tag, attrs):
        node = Node(tag, {k: (v or "") for k, v in attrs}, parent=self._stack[-1])
        self._stack[-1].children.append(node)
        if tag not in VOID_TAGS:
            self._stack.append(node)

    def handle_startendtag(self, tag, attrs):
        node = Node(tag, {k: (v or "") for k, v in attrs}, parent=self._stack[-1])
        self._stack[-1].children.append(node)

    def handle_endtag(self, tag):
        for index in range(len(self._stack) - 1, 0, -1):
            if self._stack[index].tag == tag:
                del self._stack[index:]
                return

    def handle_data(self, data):
        if data.strip():
            self._stack[-1].children.append(Node("#text", text=data, parent=self._stack[-1]))


def parse_html(html: str) -> Node:
    builder = _TreeBuilder()
    try:
        builder.feed(html)
        builder.close()
    except Exception:  # HTMLParser peut lever sur du HTML tres degrade
        pass
    return builder.root


def normalize_french_date(text: str) -> str:
    lowered = text.lower()
    for fr, en in _FR_MONTHS.items():
        if fr in lowered:
            lowered = lowered.replace(fr, en.lower())
            break
    return lowered


def find_date_in_text(text: str) -> tuple[str, str] | None:
    """Retourne (texte brut de la date, ISO) si une date est reconnue."""
    if not text:
        return None
    match = _HTML_DATE_RE.search(text)
    candidates: list[str] = []
    if match:
        candidates.append(match.group(1))
    candidates.append(text.strip())
    for raw in candidates:
        iso = parse_datetime(raw)
        if iso:
            return raw, iso
        normalized = normalize_french_date(raw)
        # "12 may 2025, 14:37" -> "may 12, 2025 14:37"
        alt = re.sub(
            r"(\d{1,2})\s+([a-z]+)\.?\s+(\d{4})",
            lambda m: f"{m.group(2).capitalize()} {m.group(1)}, {m.group(3)}",
            normalized,
        )
        alt = alt.replace(",", ", ").replace("  ", " ").strip()
        iso = parse_datetime(alt.title().replace("Am", "AM").replace("Pm", "PM"))
        if iso:
            return raw, iso
    return None


def looks_like_date(text: str) -> bool:
    return find_date_in_text(text) is not None


def collect_links(node: Node) -> list[str]:
    return [
        a.attrs.get("href", "")
        for a in node.find_all("a")
        if a.attrs.get("href")
    ]


def collect_media(node: Node) -> list[str]:
    out: list[str] = []
    for tag in ("img", "video", "audio", "source"):
        for element in node.find_all(tag):
            src = element.attrs.get("src") or element.attrs.get("href")
            if src:
                out.append(src)
    return out


INSTAGRAM_PROFILE_RE = re.compile(
    r"https?://(?:www\.)?instagram\.com/(?:_u/)?([A-Za-z0-9._]{1,30})/?", re.I
)

_NON_PROFILE_PATHS = {
    "p", "reel", "reels", "stories", "explore", "tv", "accounts", "direct",
    "s", "web", "about", "developer", "legal", "privacy",
}


def extract_instagram_username(url: str) -> str | None:
    match = INSTAGRAM_PROFILE_RE.search(url or "")
    if not match:
        return None
    candidate = match.group(1).lower()
    if candidate in _NON_PROFILE_PATHS:
        return None
    return candidate
