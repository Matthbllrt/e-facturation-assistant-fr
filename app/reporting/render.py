"""Rendu du rapport en HTML, PDF, JSON et CSV (section 14)."""
from __future__ import annotations

import csv
import html
import json
import zipfile
from pathlib import Path
from typing import Any

from ..cases import Case
from ..config import APP_NAME, APP_VERSION, CONFIDENCE_LABELS, UNKNOWN_DATE_LABEL
from ..utils import human_size, truncate, utcnow_iso
from .builder import summary_lines

CSS = """
:root { --ink:#14161a; --muted:#5b6270; --line:#dfe3ea; --bg:#ffffff;
        --accent:#2f5bd0; --warn:#8a5a00; --bad:#a11d2b; --ok:#1c6b45; }
* { box-sizing:border-box; }
body { font-family:"Segoe UI",Roboto,Helvetica,Arial,sans-serif; color:var(--ink);
       background:var(--bg); margin:0; padding:32px; line-height:1.55; font-size:14px; }
h1 { font-size:26px; margin:0 0 4px; }
h2 { font-size:19px; margin:36px 0 10px; padding-bottom:6px; border-bottom:2px solid var(--line); }
h3 { font-size:15px; margin:22px 0 8px; }
.sub { color:var(--muted); margin:0 0 24px; }
table { border-collapse:collapse; width:100%; margin:10px 0 18px; font-size:13px; }
th,td { border:1px solid var(--line); padding:6px 9px; text-align:left; vertical-align:top; }
th { background:#f4f6fa; font-weight:600; }
tr:nth-child(even) td { background:#fbfcfe; }
.badge { display:inline-block; padding:1px 8px; border-radius:10px; font-size:11px;
         font-weight:600; letter-spacing:.02em; }
.CONFIRME { background:#e3f4ea; color:var(--ok); }
.PROBABLE { background:#fdf0d8; color:var(--warn); }
.POSSIBLE { background:#f0eefb; color:#4b3fa0; }
.INCONNU  { background:#eef0f4; color:var(--muted); }
.missing { background:#fdeaec; border-left:4px solid var(--bad); padding:10px 14px; margin:8px 0; }
.missing .label { font-weight:700; color:var(--bad); letter-spacing:.03em; }
.note { background:#f4f6fa; border-left:4px solid var(--accent); padding:10px 14px; margin:12px 0; }
.src { color:var(--muted); font-size:11.5px; font-family:Consolas,monospace; word-break:break-all; }
.kpis { display:flex; flex-wrap:wrap; gap:10px; margin:16px 0 6px; }
.kpi { border:1px solid var(--line); border-radius:8px; padding:10px 14px; min-width:150px; }
.kpi b { display:block; font-size:20px; }
.kpi span { color:var(--muted); font-size:12px; }
.day { font-weight:700; margin-top:14px; }
.ev { margin-left:14px; padding:3px 0; border-bottom:1px dotted var(--line); }
ul { margin:6px 0 6px 18px; padding:0; }
li { margin:3px 0; }
footer { margin-top:40px; color:var(--muted); font-size:12px; border-top:1px solid var(--line); padding-top:12px; }
@media print { body { padding:0; font-size:11px; } h2 { page-break-after:avoid; } table { page-break-inside:auto; } }
"""


def _esc(value: Any) -> str:
    if value is None:
        return ""
    return html.escape(str(value))


def _table(headers: list[str], rows: list[list[Any]], empty: str = "Aucune donnee.") -> str:
    if not rows:
        return f"<p class='sub'>{_esc(empty)}</p>"
    head = "".join(f"<th>{_esc(h)}</th>" for h in headers)
    body = "".join(
        "<tr>" + "".join(f"<td>{cell if isinstance(cell, str) and cell.startswith('<') else _esc(cell)}</td>" for cell in row) + "</tr>"
        for row in rows
    )
    return f"<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


def _badge(confidence: str) -> str:
    label = CONFIDENCE_LABELS.get(confidence, confidence)
    return f"<span class='badge {_esc(confidence)}'>{_esc(label)}</span>"


def render_html(report: dict[str, Any]) -> str:
    account = report["compte"]
    stats = report["statistiques"]
    parts: list[str] = []
    parts.append(
        f"<!DOCTYPE html><html lang='fr'><head><meta charset='utf-8'>"
        f"<title>Rapport {APP_NAME} — @{_esc(account['username'])}</title>"
        f"<style>{CSS}</style></head><body>"
    )
    parts.append(f"<h1>Rapport {_esc(APP_NAME)}</h1>")
    parts.append(
        f"<p class='sub'>Compte @{_esc(account['username'])} — généré le "
        f"{_esc(report['meta']['genere_le'])} — version {_esc(APP_VERSION)} — "
        f"mode preuve : {'actif' if report['meta']['mode_preuve'] else 'inactif'}</p>"
    )

    parts.append("<div class='kpis'>")
    for label, value in (
        ("Conversations", stats["conversations"]),
        ("Messages", stats["messages"]),
        ("Abonnes", stats["followers"]),
        ("Abonnements", stats["following"]),
        ("Médias", stats["media"]),
        ("Fichiers haches", stats["files_hashed"]),
        ("Elements manquants", stats["gaps"]),
    ):
        parts.append(f"<div class='kpi'><b>{value}</b><span>{_esc(label)}</span></div>")
    parts.append("</div>")

    # 1. compte
    parts.append("<h2>1. Compte concerné</h2>")
    parts.append(
        _table(
            ["Champ", "Valeur"],
            [
                ["Nom d'utilisateur", f"@{account['username']}"],
                ["Nom affiché (issu des sources)", account.get("display_name") or "Non renseigne"],
                ["Dossier d'enquête", account["dossier"]],
                ["Emplacement local", account["chemin"]],
                ["Créé le", account["cree_le"]],
                ["Notes", account.get("notes") or ""],
            ],
        )
    )
    public_rows = report.get("profil_public") or []
    if public_rows:
        parts.append("<h3>Collectes publiques</h3>")
        parts.append(
            _table(
                ["Date de collecte", "Methode", "URL", "HTTP", "Nom", "Abonnes", "Abonnements", "Publications", "Remarque"],
                [
                    [
                        row["collected_at"], row["method"], row["url"] or "", row["http_status"] or "",
                        row["full_name"] or "", row["followers_count"] if row["followers_count"] is not None else "-",
                        row["following_count"] if row["following_count"] is not None else "-",
                        row["posts_count"] if row["posts_count"] is not None else "-",
                        truncate(row["note"], 160) or "",
                    ]
                    for row in public_rows
                ],
            )
        )
    else:
        parts.append(
            "<p class='sub'>Aucune collecte publique enregistrée pour ce dossier.</p>"
        )

    # 2. periode
    period = report["periode"]
    parts.append("<h2>2. Période étudiée</h2>")
    parts.append(
        _table(
            ["Champ", "Valeur"],
            [
                ["Debut des données datees", period["debut"] or UNKNOWN_DATE_LABEL],
                ["Fin des données datees", period["fin"] or UNKNOWN_DATE_LABEL],
                ["Premier message daté", period["messages_debut"] or UNKNOWN_DATE_LABEL],
                ["Dernier message daté", period["messages_fin"] or UNKNOWN_DATE_LABEL],
            ],
        )
    )
    parts.append(f"<div class='note'>{_esc(period['note'])}</div>")

    # 3. sources
    parts.append("<h2>3. Sources utilisées</h2>")
    parts.append(
        _table(
            ["#", "Libellé", "Type", "Provenance déclarée", "Date déclarée", "Fichiers", "Taille", "Importée le", "Observations"],
            [
                [
                    source["id"], source["label"], source["kind"],
                    source["declared_origin"] or "-", source["declared_date"] or UNKNOWN_DATE_LABEL,
                    source["file_count"], source["taille_lisible"], source["imported_at"],
                    truncate(source["observations"], 200) or "-",
                ]
                for source in report["sources"]
            ],
            "Aucune source importée.",
        )
    )

    # 4. integrite
    integrity = report["integrite"]
    verification = integrity["verification"]
    parts.append("<h2>4. Intégrité des fichiers</h2>")
    parts.append(
        _table(
            ["Champ", "Valeur"],
            [
                ["Algorithme", integrity["manifest"]["algorithme"]],
                ["Fichiers enregistrés", integrity["manifest"]["nombre_de_fichiers"]],
                ["Volume total", integrity["manifest"]["taille_lisible"]],
                ["Empreinte du manifeste", integrity["manifest"]["empreinte_du_manifeste"]],
                ["Vérifié le", verification["verifie_le"]],
                ["Fichiers vérifiés", verification["fichiers_verifies"]],
                ["Fichiers absents", len(verification["fichiers_absents"])],
                ["Fichiers modifiés après import", len(verification["fichiers_modifies"])],
                [
                    "Conclusion",
                    "Aucune modification détectée depuis l'import."
                    if verification["integrite_intacte"]
                    else "DIVERGENCE : au moins un fichier a change ou disparu depuis l'import.",
                ],
            ],
        )
    )
    if verification["fichiers_modifies"]:
        parts.append(
            _table(
                ["Fichier", "SHA-256 à l'import", "SHA-256 actuel"],
                [
                    [m["chemin"], m["sha256_import"], m["sha256_actuel"]]
                    for m in verification["fichiers_modifies"][:200]
                ],
            )
        )
    parts.append("<h3>Empreintes des fichiers (extrait)</h3>")
    parts.append(
        _table(
            ["Fichier", "SHA-256", "Taille", "Date du fichier", "Importé le"],
            [
                [
                    f"<span class='src'>{_esc(f['chemin'])}</span>",
                    f"<span class='src'>{_esc(f['sha256'])}</span>",
                    human_size(f["taille_octets"]),
                    f["date_du_fichier"] or UNKNOWN_DATE_LABEL,
                    f["date_import"],
                ]
                for f in integrity["fichiers"][:500]
            ],
        )
    )
    if len(integrity["fichiers"]) > 500:
        parts.append(
            f"<p class='sub'>{len(integrity['fichiers']) - 500} fichier(s) supplementaire(s) "
            "detailles dans manifest.json et dans l'export CSV.</p>"
        )

    # 5. conversations
    parts.append("<h2>5. Conversations retrouvées</h2>")
    parts.append(
        _table(
            ["Conversation", "Participants", "Messages", "Envoyés", "Reçus", "Médias", "Du", "Au", "Source"],
            [
                [
                    conv["title"] or conv["external_id"] or "Sans titre",
                    truncate(", ".join(str(p) for p in conv["participants"]), 90),
                    conv["message_count"], conv["envoyes"], conv["recus"], conv["medias"],
                    conv["first_message_at"] or UNKNOWN_DATE_LABEL,
                    conv["last_message_at"] or UNKNOWN_DATE_LABEL,
                    f"<span class='src'>{_esc(truncate(', '.join(conv['source_files']), 90))}</span>",
                ]
                for conv in report["conversations"]
            ],
            "Aucune conversation : aucun export contenant des messages n'a été importé.",
        )
    )

    # 6. chronologie
    timeline = report["chronologie"]
    parts.append("<h2>6. Chronologie</h2>")
    parts.append(
        "<div class='note'>Chaque événement indique sa source et son niveau de confiance. "
        + " · ".join(f"<b>{_esc(k)}</b> : {_esc(v)}" for k, v in timeline["legend"].items())
        + "</div>"
    )
    for day in timeline["days"]:
        parts.append(f"<div class='day'>{_esc(day['date'])}</div>")
        for event in day["events"]:
            parts.append(
                f"<div class='ev'>{_esc(event.get('time') or '--:--')} — "
                f"<b>{_esc(event['title'])}</b> {_badge(event['confidence'])}<br>"
                f"{_esc(truncate(event.get('detail'), 300))}<br>"
                f"<span class='src'>SOURCE : {_esc(event.get('source'))}</span></div>"
            )
    if timeline["undated"]:
        parts.append("<h3>Événements sans date précise</h3>")
        for event in timeline["undated"]:
            parts.append(
                f"<div class='ev'><b>{_esc(event['title'])}</b> {_badge(event['confidence'])} "
                f"<i>({_esc(event.get('window_label') or UNKNOWN_DATE_LABEL)})</i><br>"
                f"{_esc(truncate(event.get('detail'), 300))}<br>"
                f"<span class='src'>SOURCE : {_esc(event.get('source'))}</span></div>"
            )

    # 7 & 8. relations
    for heading, key in (("7. Abonnés", "abonnes"), ("8. Abonnements", "abonnements")):
        parts.append(f"<h2>{heading}</h2>")
        parts.append(
            _table(
                ["Nom d'utilisateur", "Profil", "Date dans la source", "Export", "Fichier source"],
                [
                    [
                        f"@{row['username']}" if row["username"] else (row["display_name"] or "inconnu"),
                        row["profile_url"] or "",
                        row["date_affichee"],
                        row["source_label"],
                        f"<span class='src'>{_esc(row['source_file'])}</span>",
                    ]
                    for row in report[key]
                ],
                "Aucune donnée de relation importée.",
            )
        )
    if report["autres_relations"]:
        parts.append("<h3>Autres relations (demandes, blocages, amis proches)</h3>")
        parts.append(
            _table(
                ["Type", "Nom d'utilisateur", "Date dans la source", "Fichier source"],
                [
                    [
                        row["status"], f"@{row['username']}" if row["username"] else "inconnu",
                        row["timestamp_utc"] or UNKNOWN_DATE_LABEL,
                        f"<span class='src'>{_esc(row['source_file'])}</span>",
                    ]
                    for row in report["autres_relations"]
                ],
            )
        )

    # 9. changements
    parts.append("<h2>9. Changements détectés entre exports</h2>")
    if not report["changements"]:
        parts.append(
            "<p class='sub'>Aucune comparaison enregistrée. Deux exports de dates "
            "différentes sont nécessaires pour dater les changements.</p>"
        )
    for comparison in report["changements"]:
        result = comparison["result"]
        parts.append(
            f"<h3>{_esc(result['source_a']['label'])} → {_esc(result['source_b']['label'])}</h3>"
        )
        window = result["window"]
        parts.append(
            f"<div class='note'>Fenetre de comparaison : entre {_esc(window['start_label'])} "
            f"et {_esc(window['end_label'])}. Tout changement liste ci-dessous est intervenu "
            "entre ces deux dates ; l'outil n'attribue jamais de date précise a un changement.</div>"
        )
        for kind, block in result["relations"].items():
            if not (block["added"] or block["removed"]):
                continue
            parts.append(f"<h3>{_esc(block['label'])}</h3>")
            parts.append(
                _table(
                    ["Sens", "Nom d'utilisateur", "Date présente dans la source", "Constat"],
                    [["Apparu", f"@{e['username']}", e["date_in_source"], e["statement"]] for e in block["added"]]
                    + [["Disparu", f"@{e['username']}", e["date_in_source"], e["statement"]] for e in block["removed"]],
                )
            )
        conversations_block = result["conversations"]
        if conversations_block["added"] or conversations_block["removed"]:
            parts.append("<h3>Conversations</h3>")
            parts.append(
                _table(
                    ["Sens", "Conversation", "Messages", "Constat"],
                    [["Apparue", e["title"], e["message_count"], e["statement"]] for e in conversations_block["added"]]
                    + [["Disparue", e["title"], e["message_count"], e["statement"]] for e in conversations_block["removed"]],
                )
            )
        messages_block = result["messages"]
        if messages_block["added"] or messages_block["removed"]:
            parts.append("<h3>Messages</h3>")
            parts.append(
                _table(
                    ["Sens", "Conversation", "Expéditeur", "Date", "Extrait"],
                    [["Ajoute", e["conversation"], e["sender"], e["timestamp"], e["excerpt"]] for e in messages_block["added"][:300]]
                    + [["Absent du plus récent", e["conversation"], e["sender"], e["timestamp"], e["excerpt"]] for e in messages_block["removed"][:300]],
                )
            )

    # 10. medias
    parts.append("<h2>10. Médias</h2>")
    parts.append(
        _table(
            ["Chemin", "Type", "Date", "Contexte", "SHA-256", "Taille"],
            [
                [
                    f"<span class='src'>{_esc(row['rel_path'])}</span>",
                    row["kind"], row["taken_at"] or UNKNOWN_DATE_LABEL, row["context"] or "",
                    f"<span class='src'>{_esc((row['sha256'] or '')[:32])}</span>",
                    human_size(row["size_bytes"]),
                ]
                for row in report["medias"][:800]
            ],
            "Aucun media importé.",
        )
    )

    # 11. elements manquants
    parts.append("<h2>11. Éléments potentiellement manquants</h2>")
    parts.append(
        "<div class='note'>Ces éléments ne sont PAS des contenus retrouvés. Ce sont des "
        "indices d'absence relevés dans les données, avec leur niveau de confiance. "
        "Aucun contenu manquant n'a été reconstitué.</div>"
    )
    if not report["elements_manquants"]:
        parts.append("<p class='sub'>Aucun indice d'élément manquant détecté.</p>")
    for gap in report["elements_manquants"]:
        window = ""
        if gap.get("window_start") or gap.get("window_end"):
            window = f" — fenêtre : {gap.get('window_start') or '?'} → {gap.get('window_end') or '?'}"
        parts.append(
            f"<div class='missing'><span class='label'>ÉLÉMENT POTENTIELLEMENT MANQUANT</span> "
            f"{_badge(gap['confidence'])}<br>{_esc(gap['description'])}<br>"
            f"<span class='src'>Indice : {_esc(gap.get('indicator'))} — SOURCE : "
            f"{_esc(gap.get('source_ref'))}{_esc(window)}</span></div>"
        )

    if report["preuves"]:
        parts.append("<h2>Annexe. Éléments de preuve fournis</h2>")
        parts.append(
            _table(
                ["Libellé", "Type", "Provenance déclarée", "Date déclarée", "SHA-256", "Observations"],
                [
                    [
                        row["label"], row["evidence_type"], row["declared_origin"] or "-",
                        row["declared_date"] or UNKNOWN_DATE_LABEL,
                        f"<span class='src'>{_esc((row['sha256'] or '')[:32])}</span>",
                        truncate(row["observations"], 200) or "-",
                    ]
                    for row in report["preuves"]
                ],
            )
        )

    # 12. limites
    parts.append("<h2>12. Limites de la récupération</h2>")
    parts.append("<ul>" + "".join(f"<li>{_esc(line)}</li>" for line in report["limites"]) + "</ul>")

    checklist = report["checklist"]
    parts.append("<h3>Ce qui ameliorerait encore la récupération</h3>")
    parts.append(
        _table(
            ["État", "Élément", "Comment l'obtenir"],
            [[item["icon"], item["label"], item["advice"]] for item in checklist["items"]],
        )
    )

    parts.append(
        f"<footer>{_esc(APP_NAME)} {_esc(APP_VERSION)} — traitement 100 % local, aucune donnée "
        "transmise a un service tiers. Ce rapport reprend uniquement des informations "
        "présentes dans les sources fournies ou publiquement accessibles.</footer>"
    )
    parts.append("</body></html>")
    return "".join(parts)


def render_pdf(report: dict[str, Any], target: Path) -> Path:
    """Génère un PDF. Necessite reportlab ; sinon leve ImportError."""
    from reportlab.lib import colors
    from reportlab.lib.enums import TA_LEFT
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
    from reportlab.lib.units import mm
    from reportlab.platypus import (
        PageBreak,
        Paragraph,
        SimpleDocTemplate,
        Spacer,
        Table,
        TableStyle,
    )

    styles = getSampleStyleSheet()
    body = ParagraphStyle("corps", parent=styles["Normal"], fontSize=8.5, leading=11, alignment=TA_LEFT)
    small = ParagraphStyle("petit", parent=body, fontSize=7, textColor=colors.HexColor("#5b6270"))
    heading = ParagraphStyle("titre2", parent=styles["Heading2"], fontSize=13, spaceBefore=12, spaceAfter=6)

    document = SimpleDocTemplate(
        str(target), pagesize=A4,
        leftMargin=14 * mm, rightMargin=14 * mm, topMargin=14 * mm, bottomMargin=14 * mm,
        title=f"Rapport {APP_NAME} - @{report['compte']['username']}",
        author=APP_NAME,
    )
    story: list[Any] = []

    def table(headers: list[str], rows: list[list[Any]], widths: list[float] | None = None) -> None:
        if not rows:
            story.append(Paragraph("Aucune donnee.", small))
            return
        data = [[Paragraph(f"<b>{html.escape(str(h))}</b>", small) for h in headers]]
        for row in rows:
            data.append([Paragraph(html.escape(truncate(str(c if c is not None else ""), 300)), small) for c in row])
        element = Table(data, repeatRows=1, colWidths=widths)
        element.setStyle(
            TableStyle(
                [
                    ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#c9cfda")),
                    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eef1f7")),
                    ("VALIGN", (0, 0), (-1, -1), "TOP"),
                    ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#fafbfd")]),
                    ("LEFTPADDING", (0, 0), (-1, -1), 3),
                    ("RIGHTPADDING", (0, 0), (-1, -1), 3),
                ]
            )
        )
        story.append(element)
        story.append(Spacer(1, 6))

    account = report["compte"]
    story.append(Paragraph(f"Rapport {APP_NAME}", styles["Title"]))
    story.append(Paragraph(f"Compte @{html.escape(account['username'])}", styles["Heading3"]))
    story.append(Paragraph(f"Génère le {report['meta']['genere_le']} — version {APP_VERSION}", small))
    story.append(Spacer(1, 8))
    for line in summary_lines(report):
        story.append(Paragraph(html.escape(line), body))
    story.append(Spacer(1, 8))

    story.append(Paragraph("1. Compte concerné", heading))
    table(
        ["Champ", "Valeur"],
        [
            ["Nom d'utilisateur", f"@{account['username']}"],
            ["Nom affiché", account.get("display_name") or "Non renseigne"],
            ["Dossier", account["chemin"]],
            ["Créé le", account["cree_le"]],
        ],
    )

    period = report["periode"]
    story.append(Paragraph("2. Période étudiée", heading))
    table(
        ["Champ", "Valeur"],
        [
            ["Debut", period["debut"] or UNKNOWN_DATE_LABEL],
            ["Fin", period["fin"] or UNKNOWN_DATE_LABEL],
        ],
    )
    story.append(Paragraph(html.escape(period["note"]), small))

    story.append(Paragraph("3. Sources utilisées", heading))
    table(
        ["Libellé", "Type", "Provenance", "Date déclarée", "Fichiers", "Importée le"],
        [
            [s["label"], s["kind"], s["declared_origin"] or "-", s["declared_date"] or UNKNOWN_DATE_LABEL,
             s["file_count"], s["imported_at"]]
            for s in report["sources"]
        ],
    )

    verification = report["integrite"]["verification"]
    story.append(Paragraph("4. Intégrité des fichiers", heading))
    table(
        ["Champ", "Valeur"],
        [
            ["Algorithme", "SHA-256"],
            ["Fichiers enregistrés", report["integrite"]["manifest"]["nombre_de_fichiers"]],
            ["Empreinte du manifeste", report["integrite"]["manifest"]["empreinte_du_manifeste"]],
            ["Fichiers modifiés depuis l'import", len(verification["fichiers_modifies"])],
            ["Fichiers absents", len(verification["fichiers_absents"])],
            ["Conclusion", "Integrite intacte" if verification["integrite_intacte"] else "DIVERGENCE DETECTEE"],
        ],
    )

    story.append(Paragraph("5. Conversations retrouvées", heading))
    table(
        ["Conversation", "Participants", "Messages", "Du", "Au"],
        [
            [c["title"] or "Sans titre", truncate(", ".join(str(p) for p in c["participants"]), 60),
             c["message_count"], c["first_message_at"] or UNKNOWN_DATE_LABEL,
             c["last_message_at"] or UNKNOWN_DATE_LABEL]
            for c in report["conversations"][:400]
        ],
    )

    story.append(PageBreak())
    story.append(Paragraph("6. Chronologie", heading))
    for day in report["chronologie"]["days"][:400]:
        story.append(Paragraph(f"<b>{html.escape(day['date'])}</b>", body))
        for event in day["events"][:80]:
            story.append(
                Paragraph(
                    f"{html.escape(event.get('time') or '--:--')} — "
                    f"{html.escape(event['title'])} [{html.escape(CONFIDENCE_LABELS.get(event['confidence'], event['confidence']))}]<br/>"
                    f"<font size=6.5 color='#5b6270'>SOURCE : "
                    f"{html.escape(truncate(event.get('source'), 150))}</font>",
                    small,
                )
            )
    for event in report["chronologie"]["undated"][:200]:
        story.append(
            Paragraph(
                f"{html.escape(event['title'])} [{html.escape(CONFIDENCE_LABELS.get(event['confidence'], event['confidence']))}] "
                f"({html.escape(event.get('window_label') or UNKNOWN_DATE_LABEL)})",
                small,
            )
        )

    story.append(PageBreak())
    story.append(Paragraph("7. Abonnés", heading))
    table(
        ["Nom d'utilisateur", "Date dans la source", "Export"],
        [[f"@{r['username']}", r["date_affichee"], r["source_label"]] for r in report["abonnes"][:1500]],
    )
    story.append(Paragraph("8. Abonnements", heading))
    table(
        ["Nom d'utilisateur", "Date dans la source", "Export"],
        [[f"@{r['username']}", r["date_affichee"], r["source_label"]] for r in report["abonnements"][:1500]],
    )

    story.append(Paragraph("9. Changements détectés entre exports", heading))
    if not report["changements"]:
        story.append(Paragraph("Aucune comparaison enregistrée.", small))
    for comparison in report["changements"]:
        result = comparison["result"]
        story.append(
            Paragraph(
                f"<b>{html.escape(result['source_a']['label'])} → "
                f"{html.escape(result['source_b']['label'])}</b>", body
            )
        )
        rows = []
        for block in result["relations"].values():
            rows += [["Apparu", block["label"], f"@{e['username']}", e["statement"]] for e in block["added"]]
            rows += [["Disparu", block["label"], f"@{e['username']}", e["statement"]] for e in block["removed"]]
        table(["Sens", "Type", "Compte", "Constat"], rows[:600])

    story.append(Paragraph("10. Médias", heading))
    table(
        ["Chemin", "Type", "Date"],
        [[r["rel_path"], r["kind"], r["taken_at"] or UNKNOWN_DATE_LABEL] for r in report["medias"][:600]],
    )

    story.append(Paragraph("11. Éléments potentiellement manquants", heading))
    story.append(
        Paragraph(
            "Indices d'absence releves dans les données. Aucun contenu manquant n'a été "
            "reconstitué, devine ou complète.",
            small,
        )
    )
    table(
        ["Type", "Confiance", "Description", "Source"],
        [
            [g["gap_type"], CONFIDENCE_LABELS.get(g["confidence"], g["confidence"]), g["description"], g.get("source_ref") or ""]
            for g in report["elements_manquants"][:400]
        ],
    )

    story.append(Paragraph("12. Limites de la récupération", heading))
    for line in report["limites"]:
        story.append(Paragraph("• " + html.escape(line), small))

    document.build(story)
    return target


CSV_EXPORTS = (
    (
        "conversations.csv",
        ["titre", "participants", "messages", "envoyes", "recus", "medias", "premier_message",
         "dernier_message", "export", "fichiers_source"],
        lambda report: [
            [
                c["title"] or "", ", ".join(str(p) for p in c["participants"]), c["message_count"],
                c["envoyes"], c["recus"], c["medias"], c["first_message_at"] or UNKNOWN_DATE_LABEL,
                c["last_message_at"] or UNKNOWN_DATE_LABEL, c["source_label"] or "",
                ", ".join(c["source_files"]),
            ]
            for c in report["conversations"]
        ],
    ),
    (
        "abonnes.csv",
        ["username", "profil", "date_dans_la_source", "export", "fichier_source"],
        lambda report: [
            [r["username"] or "", r["profile_url"] or "", r["date_affichee"], r["source_label"] or "", r["source_file"] or ""]
            for r in report["abonnes"]
        ],
    ),
    (
        "abonnements.csv",
        ["username", "profil", "date_dans_la_source", "export", "fichier_source"],
        lambda report: [
            [r["username"] or "", r["profile_url"] or "", r["date_affichee"], r["source_label"] or "", r["source_file"] or ""]
            for r in report["abonnements"]
        ],
    ),
    (
        "autres_relations.csv",
        ["type", "username", "date_dans_la_source", "fichier_source"],
        lambda report: [
            [r["status"], r["username"] or "", r["timestamp_utc"] or UNKNOWN_DATE_LABEL, r["source_file"] or ""]
            for r in report["autres_relations"]
        ],
    ),
    (
        "chronologie.csv",
        ["date", "heure", "type", "titre", "detail", "confiance", "source", "fenetre_debut", "fenetre_fin"],
        lambda report: [
            [
                event.get("date") or "", event.get("time") or "", event["kind_label"], event["title"],
                event.get("detail") or "", event["confidence"], event.get("source") or "",
                event.get("period_start") or "", event.get("period_end") or "",
            ]
            for day in report["chronologie"]["days"] for event in day["events"]
        ]
        + [
            [
                "", "", event["kind_label"], event["title"], event.get("detail") or "",
                event["confidence"], event.get("source") or "",
                event.get("period_start") or "", event.get("period_end") or "",
            ]
            for event in report["chronologie"]["undated"]
        ],
    ),
    (
        "elements_manquants.csv",
        ["type", "confiance", "description", "indice", "fenetre_debut", "fenetre_fin", "source"],
        lambda report: [
            [
                g["gap_type"], g["confidence"], g["description"], g.get("indicator") or "",
                g.get("window_start") or "", g.get("window_end") or "", g.get("source_ref") or "",
            ]
            for g in report["elements_manquants"]
        ],
    ),
    (
        "medias.csv",
        ["chemin", "type", "date", "contexte", "sha256", "taille_octets", "export"],
        lambda report: [
            [
                r["rel_path"], r["kind"], r["taken_at"] or UNKNOWN_DATE_LABEL, r["context"] or "",
                r["sha256"] or "", r["size_bytes"] or "", r["source_label"] or "",
            ]
            for r in report["medias"]
        ],
    ),
    (
        "fichiers_empreintes.csv",
        ["chemin", "sha256", "taille_octets", "date_du_fichier", "date_import", "categorie", "source"],
        lambda report: [
            [
                f["chemin"], f["sha256"], f["taille_octets"], f["date_du_fichier"] or "",
                f["date_import"], f["categorie"] or "", f["source"] or "",
            ]
            for f in report["integrite"]["fichiers"]
        ],
    ),
    (
        "sources.csv",
        ["id", "libelle", "type", "provenance_declaree", "date_declaree", "fichiers", "octets", "importee_le", "observations"],
        lambda report: [
            [
                s["id"], s["label"], s["kind"], s["declared_origin"] or "", s["declared_date"] or "",
                s["file_count"], s["total_bytes"], s["imported_at"], s["observations"] or "",
            ]
            for s in report["sources"]
        ],
    ),
)


def render_csv_files(report: dict[str, Any], target_dir: Path) -> list[Path]:
    target_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for name, headers, extractor in CSV_EXPORTS:
        path = target_dir / name
        with open(path, "w", newline="", encoding="utf-8-sig") as handle:
            writer = csv.writer(handle, delimiter=";")
            writer.writerow(headers)
            writer.writerows(extractor(report))
        written.append(path)
    return written


def render_messages_csv(case: Case, target: Path) -> Path:
    """Export CSV complet des messages, avec renvoi au fichier source."""
    from .. import db as dbmod

    rows = dbmod.query_all(
        case.conn,
        "SELECT c.title AS conversation, m.sender, m.direction, m.timestamp_utc, m.msg_type, "
        "       m.content, m.media_path, m.reactions_json, m.source_file, s.label AS export "
        "FROM messages m JOIN conversations c ON c.id = m.conversation_id "
        "LEFT JOIN sources s ON s.id = m.source_id "
        "WHERE m.case_id = ? ORDER BY c.title, m.timestamp_ms",
        (case.case_id,),
    )
    with open(target, "w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.writer(handle, delimiter=";")
        writer.writerow(
            ["conversation", "expediteur", "sens", "date_utc", "type", "contenu",
             "media", "reactions", "fichier_source", "export"]
        )
        for row in rows:
            writer.writerow(
                [
                    row["conversation"] or "", row["sender"] or "", row["direction"] or "",
                    row["timestamp_utc"] or UNKNOWN_DATE_LABEL, row["msg_type"] or "",
                    row["content"] or "", row["media_path"] or "", row["reactions_json"] or "",
                    row["source_file"] or "", row["export"] or "",
                ]
            )
    return target


def generate_all(case: Case, report: dict[str, Any]) -> dict[str, Any]:
    """Génère tous les formats et un ZIP regroupant l'ensemble."""
    stamp = utcnow_iso().replace(":", "-").replace("+00-00", "Z")
    folder = case.reports_dir / f"rapport_{stamp}"
    folder.mkdir(parents=True, exist_ok=True)

    produced: dict[str, Any] = {"dossier": str(folder), "fichiers": {}, "avertissements": []}

    html_path = folder / "rapport.html"
    html_path.write_text(render_html(report), encoding="utf-8")
    produced["fichiers"]["html"] = str(html_path)

    json_path = folder / "rapport.json"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    produced["fichiers"]["json"] = str(json_path)

    csv_dir = folder / "csv"
    csv_files = render_csv_files(report, csv_dir)
    csv_files.append(render_messages_csv(case, csv_dir / "messages.csv"))
    produced["fichiers"]["csv"] = [str(p) for p in csv_files]

    try:
        pdf_path = render_pdf(report, folder / "rapport.pdf")
        produced["fichiers"]["pdf"] = str(pdf_path)
    except ImportError:
        produced["avertissements"].append(
            "PDF non généré : le module reportlab n'est pas installé. "
            "Le rapport HTML peut être imprime en PDF depuis le navigateur."
        )
    except Exception as exc:
        produced["avertissements"].append(f"PDF non généré ({type(exc).__name__}: {exc}).")

    manifest_copy = folder / "manifest.json"
    manifest_copy.write_text(
        json.dumps(
            {
                "empreinte_du_manifeste": report["integrite"]["manifest"]["empreinte_du_manifeste"],
                "fichiers": report["integrite"]["fichiers"],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    produced["fichiers"]["manifest"] = str(manifest_copy)

    zip_path = folder.with_suffix(".zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(folder.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(folder.parent).as_posix())
    produced["fichiers"]["zip"] = str(zip_path)

    case.audit("rapport_genere", str(folder))
    return produced
