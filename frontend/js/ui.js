/* Aides d'affichage partagées. */
(function (global) {
  "use strict";

  function esc(value) {
    if (value === null || value === undefined) return "";
    return String(value)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function truncate(text, length) {
    if (!text) return "";
    const clean = String(text).replace(/\s+/g, " ").trim();
    return clean.length <= length ? clean : clean.slice(0, length - 1) + "…";
  }

  /* Une date absente doit rester visible comme absente. */
  function dateLabel(iso) {
    if (!iso) return "Date inconnue";
    const parsed = new Date(iso);
    if (isNaN(parsed.getTime())) return String(iso);
    return parsed.toLocaleString("fr-FR", {
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit"
    });
  }

  function dayLabel(iso) {
    if (!iso) return "Date inconnue";
    const parsed = new Date(iso + (iso.length === 10 ? "T00:00:00Z" : ""));
    if (isNaN(parsed.getTime())) return String(iso);
    return parsed.toLocaleDateString("fr-FR", {
      weekday: "long", year: "numeric", month: "long", day: "numeric"
    });
  }

  /* La valeur stockée reste en ASCII (stable en base et comme classe CSS) ;
     seul l'affichage est accentué. */
  const CONFIDENCE_LABELS = {
    CONFIRME: "CONFIRMÉ", PROBABLE: "PROBABLE", POSSIBLE: "POSSIBLE", INCONNU: "INCONNU"
  };

  function badge(confidence) {
    const label = CONFIDENCE_LABELS[confidence] || confidence;
    return '<span class="badge ' + esc(confidence) + '">' + esc(label) + "</span>";
  }

  function table(headers, rows, emptyText) {
    if (!rows || !rows.length) {
      return '<div class="empty">' + esc(emptyText || "Aucune donnée.") + "</div>";
    }
    let html = '<div class="table-wrap"><table><thead><tr>';
    headers.forEach(function (h) { html += "<th>" + esc(h) + "</th>"; });
    html += "</tr></thead><tbody>";
    rows.forEach(function (row) {
      html += "<tr>";
      row.forEach(function (cell) {
        const value = (cell === null || cell === undefined) ? "" : String(cell);
        html += "<td>" + (value.indexOf("<") === 0 ? value : esc(value)) + "</td>";
      });
      html += "</tr>";
    });
    return html + "</tbody></table></div>";
  }

  function kpi(value, label, tone) {
    return '<div class="kpi ' + (tone || "") + '"><b>' + esc(value) + "</b><span>" + esc(label) + "</span></div>";
  }

  let toastTimer = null;
  function toast(message, tone) {
    const node = document.getElementById("toast");
    node.textContent = message;
    node.className = "toast " + (tone || "");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { node.className = "toast hidden"; }, 5200);
  }

  function modal(title, bodyHtml) {
    document.getElementById("modal-title").textContent = title;
    document.getElementById("modal-body").innerHTML = bodyHtml;
    document.getElementById("modal").classList.remove("hidden");
  }

  function closeModal() {
    document.getElementById("modal").classList.add("hidden");
  }

  function humanSize(bytes) {
    if (!bytes) return "0 o";
    const units = ["o", "Ko", "Mo", "Go", "To"];
    let value = Number(bytes);
    let index = 0;
    while (value >= 1024 && index < units.length - 1) { value /= 1024; index += 1; }
    return (index === 0 ? value.toFixed(0) : value.toFixed(1)) + " " + units[index];
  }

  global.UI = {
    esc: esc, truncate: truncate, dateLabel: dateLabel, dayLabel: dayLabel,
    badge: badge, table: table, kpi: kpi, toast: toast, modal: modal,
    confidenceLabels: CONFIDENCE_LABELS,
    closeModal: closeModal, humanSize: humanSize
  };
})(window);
