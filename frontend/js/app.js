/* Amorçage, navigation et suivi des tâches. */
(function (global) {
  "use strict";

  const APP = {
    slug: null,
    username: null,
    evidenceMode: false,
    currentView: "dashboard"
  };
  global.APP = APP;

  const viewRoot = () => document.getElementById("view");

  /* ------------------------------------------------------------- démarrage */
  async function boot() {
    bindStatic();
    try {
      const cases = await API.listCases();
      if (cases.length) renderCaseList(cases);
      const remembered = localStorage.getItem("ier_case");
      if (remembered && cases.some(function (c) { return c.slug === remembered; })) {
        await openCase(remembered);
        return;
      }
    } catch (error) {
      UI.toast("Impossible de contacter le service local : " + error.message, "bad");
    }
    document.getElementById("new-username").focus();
  }

  function renderCaseList(cases) {
    const box = document.getElementById("existing-cases");
    const list = document.getElementById("cases-list");
    list.innerHTML = "";
    cases.forEach(function (item) {
      const row = document.createElement("div");
      row.className = "case-row";
      row.innerHTML = '<div><div class="name">@' + UI.esc(item.username) + "</div>" +
        '<div class="meta">créé le ' + UI.esc(UI.dateLabel(item.created_at)) +
        (item.evidence_mode ? " · mode preuve actif" : "") + "</div></div>" +
        '<span class="btn btn-small">Ouvrir</span>';
      row.onclick = function () { openCase(item.slug); };
      list.appendChild(row);
    });
    box.classList.remove("hidden");
  }

  function bindStatic() {
    document.getElementById("btn-create-case").onclick = createCase;
    document.getElementById("new-username").addEventListener("keydown", function (event) {
      if (event.key === "Enter") createCase();
    });
    document.getElementById("modal-close").onclick = UI.closeModal;
    document.getElementById("modal").addEventListener("click", function (event) {
      if (event.target.id === "modal") UI.closeModal();
    });
    document.getElementById("btn-switch-case").onclick = function () {
      localStorage.removeItem("ier_case");
      location.reload();
    };
    document.getElementById("btn-search").onclick = runSearch;
    document.getElementById("global-search").addEventListener("keydown", function (event) {
      if (event.key === "Enter") runSearch();
    });
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (button) {
      button.onclick = function () { show(button.getAttribute("data-view")); };
    });
  }

  async function createCase() {
    const input = document.getElementById("new-username");
    const errorBox = document.getElementById("create-error");
    errorBox.classList.add("hidden");
    const username = input.value.trim();
    if (!username) {
      errorBox.textContent = "Saisis le nom d'utilisateur du compte concerné.";
      errorBox.classList.remove("hidden");
      return;
    }
    try {
      const created = await API.createCase(username);
      await openCase(created.slug);
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.classList.remove("hidden");
    }
  }

  async function openCase(slug) {
    const info = await API.getCase(slug);
    APP.slug = info.slug;
    APP.username = info.username;
    APP.evidenceMode = info.evidence_mode;
    localStorage.setItem("ier_case", info.slug);

    document.getElementById("screen-home").classList.add("hidden");
    document.getElementById("screen-app").classList.remove("hidden");
    document.getElementById("sidebar-case").textContent = "@" + info.username;
    updateEvidenceFlag();

    /* Premier lancement : orienter vers l'import si le dossier est vide. */
    const stats = info.stats;
    const empty = !stats.export_imported && !stats.public_data.collected && !stats.local_sources;
    await show(empty ? "imports" : "dashboard");
    if (empty) {
      UI.toast("Dossier créé. Commence par importer une archive Instagram ou lancer une collecte publique.");
    }
  }

  async function refreshCase() {
    if (!APP.slug) return;
    const info = await API.getCase(APP.slug);
    APP.evidenceMode = info.evidence_mode;
    updateEvidenceFlag();
  }

  function updateEvidenceFlag() {
    const flag = document.getElementById("evidence-flag");
    flag.classList.toggle("hidden", !APP.evidenceMode);
  }

  async function show(name) {
    APP.currentView = name;
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (button) {
      button.classList.toggle("active", button.getAttribute("data-view") === name);
    });
    const root = viewRoot();
    root.innerHTML = '<div class="empty">Chargement…</div>';
    const view = VIEWS[name];
    if (!view) { root.innerHTML = '<div class="empty">Vue inconnue.</div>'; return; }
    try {
      await view(root);
    } catch (error) {
      root.innerHTML = '<div class="note bad"><strong>Erreur d\'affichage.</strong><br>' +
        UI.esc(error.message) + "</div>";
    }
    window.scrollTo(0, 0);
  }

  async function runSearch() {
    const query = document.getElementById("global-search").value.trim();
    if (!query) { UI.toast("Saisis un terme à rechercher.", "bad"); return; }
    APP.currentView = "search";
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (button) {
      button.classList.remove("active");
    });
    const root = viewRoot();
    root.innerHTML = '<div class="empty">Recherche…</div>';
    try {
      await VIEWS.search(root, query);
    } catch (error) {
      root.innerHTML = '<div class="note bad">' + UI.esc(error.message) + "</div>";
    }
  }

  /* Suivi d'une tâche de fond (import, rapport, adb). */
  function watchJob(jobId, onDone) {
    const indicator = document.getElementById("job-indicator");
    indicator.classList.remove("hidden");
    const timer = setInterval(async function () {
      try {
        const job = await API.job(jobId);
        indicator.textContent = job.label + " — " + job.step +
          (job.percent !== null ? " (" + job.percent + " %)" : "");
        if (job.status === "termine" || job.status === "erreur") {
          clearInterval(timer);
          indicator.classList.add("hidden");
          if (job.status === "erreur") UI.toast("Échec : " + job.error, "bad");
          if (onDone) onDone(job);
        }
      } catch (error) {
        clearInterval(timer);
        indicator.classList.add("hidden");
      }
    }, 1200);
  }

  APP.show = show;
  APP.watchJob = watchJob;
  APP.refreshCase = refreshCase;
  APP.openCase = openCase;

  document.addEventListener("DOMContentLoaded", boot);
})(window);
