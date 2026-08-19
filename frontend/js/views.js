/* Rendu des différentes vues du tableau de bord. */
(function (global) {
  "use strict";

  const E = UI.esc;
  const T = UI.table;

  function slug() { return global.APP.slug; }

  function head(title, subtitle) {
    return '<h1 class="view-title">' + E(title) + "</h1>" +
      (subtitle ? '<p class="view-sub">' + E(subtitle) + "</p>" : "");
  }

  function sourceRef(text) {
    if (!text) return "";
    return '<span class="src">SOURCE : ' + E(text) + "</span>";
  }

  /* ------------------------------------------------------------ tableau de bord */
  async function dashboard(root) {
    const stats = await API.stats(slug());
    const gaps = await API.gaps(slug(), {});
    const publicInfo = await API.publicData(slug());

    let html = head("Tableau de bord", "Niveau de récupération du dossier @" + global.APP.username);

    html += '<div class="kpis">';
    html += UI.kpi(stats.public_data.count > 0 ? "100 %" : "0 %", "Données publiques collectées",
      stats.public_data.count > 0 ? "good" : "warn");
    html += UI.kpi(stats.export_count > 0 ? stats.export_count : "non importé",
      "Export Instagram", stats.export_count > 0 ? "good" : "warn");
    html += UI.kpi(stats.conversations, "Conversations retrouvées");
    html += UI.kpi(stats.messages, "Messages retrouvés");
    html += UI.kpi(stats.media, "Médias retrouvés");
    html += UI.kpi(stats.relations_total, "Relations retrouvées");
    html += UI.kpi(stats.local_sources, "Sources locales analysées");
    html += UI.kpi(stats.gaps, "Éléments potentiellement manquants", stats.gaps ? "warn" : "");
    html += UI.kpi(stats.files_hashed, "Fichiers avec empreinte SHA-256");
    html += UI.kpi(stats.comparisons, "Comparaisons d'exports");
    html += "</div>";

    if (!stats.export_imported) {
      html += '<div class="note warn"><strong>Aucun export Instagram importé.</strong> ' +
        "Sans archive officielle, aucune conversation, aucun abonné et aucun média du compte " +
        "ne peuvent être récupérés. Va dans <em>Imports</em> pour ajouter une archive ZIP, " +
        "un dossier décompressé, des fichiers JSON ou HTML.</div>";
    }
    if (stats.export_count === 1) {
      html += '<div class="note">Un seul export est présent : importe un second export d\'une autre ' +
        "date pour détecter et borner les changements (onglet <em>Comparaison d'exports</em>).</div>";
    }
    if (stats.evidence_mode) {
      html += '<div class="note ok"><strong>Mode preuve actif.</strong> Les fichiers sources sont ' +
        "en lecture seule et les empreintes SHA-256 sont figées dans manifest.json.</div>";
    }

    html += '<div class="card"><h2 class="card-title">Détail par catégorie de relation</h2>';
    html += T(["Type", "Comptes distincts"],
      (stats.relations_by_kind || []).map(function (r) { return [r.label, r.n]; }),
      "Aucune relation importée.");
    html += "</div>";

    html += '<div class="card"><h2 class="card-title">Sources analysées</h2>';
    html += T(["#", "Libellé", "Type", "Date déclarée", "Fichiers", "Importée le"],
      (stats.sources || []).map(function (s) {
        return [s.id, s.label, s.kind, s.declared_date || "Date inconnue", s.file_count,
          UI.dateLabel(s.imported_at)];
      }), "Aucune source importée pour le moment.");
    html += "</div>";

    const latest = publicInfo.latest;
    html += '<div class="card"><h2 class="card-title">Dernière collecte publique</h2>';
    if (latest) {
      html += T(["Champ", "Valeur"], [
        ["Collectée le", UI.dateLabel(latest.collected_at)],
        ["Méthode", latest.method],
        ["URL", latest.url || "—"],
        ["Code HTTP", latest.http_status === null ? "—" : latest.http_status],
        ["Nom affiché", latest.full_name || "Non disponible"],
        ["Abonnés (public)", latest.followers_count === null ? "Non disponible" : latest.followers_count],
        ["Abonnements (public)", latest.following_count === null ? "Non disponible" : latest.following_count],
        ["Publications (public)", latest.posts_count === null ? "Non disponible" : latest.posts_count],
        ["Remarque", latest.note || "—"]
      ]);
    } else {
      html += '<div class="empty">Aucune collecte publique enregistrée. Onglet <em>Profil</em>.</div>';
    }
    html += "</div>";

    if (gaps.count) {
      html += '<div class="card"><h2 class="card-title">Derniers éléments potentiellement manquants</h2>';
      gaps.items.slice(0, 5).forEach(function (g) {
        html += '<div class="gap-block"><span class="label">ÉLÉMENT POTENTIELLEMENT MANQUANT</span> ' +
          UI.badge(g.confidence) + "<br>" + E(g.description) + "<br>" + sourceRef(g.source_ref) + "</div>";
      });
      html += "</div>";
    }

    root.innerHTML = html;
  }

  /* ------------------------------------------------------------ profil */
  async function profile(root) {
    const info = await API.publicData(slug());
    let html = head("Profil", "Informations publiques du compte @" + global.APP.username);

    html += '<div class="note">' + E(info.avertissement) + "</div>";

    html += '<div class="card"><h2 class="card-title">Collecte publique automatique</h2>' +
      "<p class=\"muted\">Une requête simple, non authentifiée, sur <span class=\"src\">" +
      E(info.url) + "</span>. Aucun identifiant, aucun cookie, aucun contournement.</p>" +
      '<div class="btn-row"><button class="btn btn-primary" id="btn-collect">Rechercher les informations publiques</button>' +
      '<a class="btn" href="' + E(info.url) + '" target="_blank" rel="noopener">Ouvrir la page dans le navigateur</a></div>' +
      "</div>";

    html += '<div class="card"><h2 class="card-title">Saisie manuelle</h2>' +
      "<p class=\"muted\">Si Instagram limite l'accès automatique, ouvre la page publique dans ton " +
      "navigateur, fais un clic droit → « Afficher le code source », copie tout et colle-le ici. " +
      "Tu peux aussi coller un JSON, ou déposer une capture d'écran / un PDF depuis l'onglet Sources.</p>" +
      '<div class="form-grid">' +
      "<div><label>URL d'origine</label><input type=\"text\" id=\"manual-url\" value=\"" + E(info.url) + "\"></div>" +
      "<div><label>Remarque (contexte, date de la capture…)</label><input type=\"text\" id=\"manual-note\" placeholder=\"ex. page consultée le 12/05/2026\"></div>" +
      "</div>" +
      '<textarea id="manual-content" placeholder="Colle ici le HTML ou le JSON de la page publique…"></textarea>' +
      '<div class="btn-row"><button class="btn btn-primary" id="btn-manual">Enregistrer cette collecte</button></div>' +
      "</div>";

    html += '<div class="card"><h2 class="card-title">Historique des collectes</h2>';
    html += T(["Date", "Méthode", "HTTP", "Nom", "Bio", "Abonnés", "Abonnements", "Publications", "Remarque"],
      (info.history || []).map(function (row) {
        return [UI.dateLabel(row.collected_at), row.method, row.http_status === null ? "—" : row.http_status,
          row.full_name || "—", UI.truncate(row.biography, 70) || "—",
          row.followers_count === null ? "—" : row.followers_count,
          row.following_count === null ? "—" : row.following_count,
          row.posts_count === null ? "—" : row.posts_count,
          UI.truncate(row.note, 90) || "—"];
      }), "Aucune collecte enregistrée.");
    html += "</div>";

    html += '<div class="note warn"><strong>Non accessible sans récupérer le compte :</strong> ' +
      "adresse e-mail et numéro associés, historique des connexions et des appareils, " +
      "messages privés, contenus supprimés côté Instagram, valeurs passées du nombre d'abonnés. " +
      "Ces éléments n'existent que dans un export officiel demandé depuis le compte.</div>";

    root.innerHTML = html;

    document.getElementById("btn-collect").onclick = async function () {
      this.disabled = true;
      this.textContent = "Collecte en cours…";
      try {
        const result = await API.publicCollect(slug());
        UI.toast(result.message, result.ok ? "ok" : "bad");
        await profile(root);
      } catch (error) {
        UI.toast(error.message, "bad");
        this.disabled = false;
        this.textContent = "Rechercher les informations publiques";
      }
    };

    document.getElementById("btn-manual").onclick = async function () {
      const content = document.getElementById("manual-content").value.trim();
      if (!content) { UI.toast("Colle d'abord le contenu de la page.", "bad"); return; }
      try {
        await API.publicManual(slug(), {
          content: content,
          url: document.getElementById("manual-url").value.trim(),
          note: document.getElementById("manual-note").value.trim()
        });
        UI.toast("Collecte manuelle enregistrée.", "ok");
        await profile(root);
      } catch (error) { UI.toast(error.message, "bad"); }
    };
  }

  /* ------------------------------------------------------------ conversations */
  let dmState = { groupKey: null, filters: {} };

  async function conversations(root) {
    const list = await API.conversations(slug(), {});
    let html = head("Conversations", "Messages reconstitués à partir des fichiers importés uniquement.");

    if (!list.length) {
      html += '<div class="empty">Aucune conversation. Importe une archive Instagram contenant ' +
        "le dossier <em>messages/inbox</em> depuis l'onglet Imports.</div>";
      root.innerHTML = html;
      return;
    }

    html += '<div class="card"><div class="form-grid">' +
      '<div><label>Mot recherché dans les messages</label><input type="text" id="dm-q" placeholder="mot ou expression"></div>' +
      '<div><label>Expéditeur</label><input type="text" id="dm-sender" placeholder="nom affiché"></div>' +
      '<div><label>À partir du</label><input type="date" id="dm-from"></div>' +
      '<div><label>Jusqu\'au</label><input type="date" id="dm-to"></div>' +
      '<div><label>Type de contenu</label><select id="dm-type">' +
      '<option value="">Tous</option><option value="text">Texte</option><option value="media">Média</option>' +
      '<option value="share">Partage / lien</option><option value="unsent">Message retiré</option>' +
      '<option value="reaction_only">Réaction seule</option><option value="call">Appel</option></select></div>' +
      '<div><label>Sens</label><select id="dm-dir"><option value="">Tous</option>' +
      '<option value="sent">Envoyés</option><option value="received">Reçus</option></select></div>' +
      "</div>" +
      '<div class="btn-row">' +
      '<label class="inline"><input type="checkbox" id="dm-media"> Uniquement les médias</label>' +
      '<label class="inline"><input type="checkbox" id="dm-links"> Uniquement les liens</label>' +
      '<button class="btn btn-primary btn-small" id="dm-apply">Filtrer</button>' +
      '<button class="btn btn-small" id="dm-reset">Réinitialiser</button>' +
      "</div></div>";

    html += '<div class="dm"><div class="dm-list" id="dm-list">';
    list.forEach(function (conversation) {
      html += '<div class="dm-item" data-key="' + E(conversation.group_key) + '">' +
        '<div class="t">' + E(conversation.title || conversation.external_id || "Sans titre") + "</div>" +
        '<div class="m">' + conversation.message_count + " message(s) · dernier : " +
        E(conversation.last_message_at ? UI.dateLabel(conversation.last_message_at) : "Date inconnue") + "</div>" +
        '<div class="m">' + E(UI.truncate((conversation.participants || []).join(", "), 60)) + "</div>" +
        "</div>";
    });
    html += '</div><div class="dm-panel" id="dm-panel"><div class="empty">Sélectionne une conversation.</div></div></div>';

    root.innerHTML = html;

    Array.prototype.forEach.call(root.querySelectorAll(".dm-item"), function (item) {
      item.onclick = function () {
        Array.prototype.forEach.call(root.querySelectorAll(".dm-item"), function (n) { n.classList.remove("active"); });
        item.classList.add("active");
        dmState.groupKey = item.getAttribute("data-key");
        loadThread();
      };
    });
    document.getElementById("dm-apply").onclick = loadThread;
    document.getElementById("dm-reset").onclick = function () {
      ["dm-q", "dm-sender", "dm-from", "dm-to"].forEach(function (id) { document.getElementById(id).value = ""; });
      document.getElementById("dm-type").value = "";
      document.getElementById("dm-dir").value = "";
      document.getElementById("dm-media").checked = false;
      document.getElementById("dm-links").checked = false;
      loadThread();
    };

    if (list.length) {
      root.querySelector(".dm-item").click();
    }
  }

  async function loadThread() {
    if (!dmState.groupKey) return;
    const panel = document.getElementById("dm-panel");
    panel.innerHTML = '<div class="empty">Chargement…</div>';
    const params = {
      group_key: dmState.groupKey,
      q: document.getElementById("dm-q").value.trim(),
      sender: document.getElementById("dm-sender").value.trim(),
      date_from: document.getElementById("dm-from").value,
      date_to: document.getElementById("dm-to").value,
      msg_type: document.getElementById("dm-type").value,
      direction: document.getElementById("dm-dir").value,
      only_media: document.getElementById("dm-media").checked ? "true" : "",
      only_links: document.getElementById("dm-links").checked ? "true" : ""
    };
    if (params.date_from) params.date_from = params.date_from + "T00:00:00+00:00";
    if (params.date_to) params.date_to = params.date_to + "T23:59:59+00:00";

    try {
      const data = await API.messages(slug(), params);
      let html = "<h2 class=\"card-title\">" + E(data.title || "Conversation") + " — " +
        data.count + " message(s) affiché(s)</h2>";
      html += '<div class="note">' + E(data.note) + "</div>";

      if (data.gaps && data.gaps.length) {
        data.gaps.forEach(function (gap) {
          html += '<div class="gap-block"><span class="label">ÉLÉMENT POTENTIELLEMENT MANQUANT</span> ' +
            UI.badge(gap.confidence) + "<br>" + E(gap.description) + "<br>" + sourceRef(gap.source_ref) + "</div>";
        });
      }

      html += '<div class="dm-thread">';
      if (!data.messages.length) {
        html += '<div class="empty">Aucun message ne correspond à ces filtres.</div>';
      }
      data.messages.forEach(function (message) {
        const classes = ["msg"];
        if (message.direction === "sent") classes.push("sent");
        if (message.type === "unsent") classes.push("unsent");
        html += '<div class="' + classes.join(" ") + '">';
        html += '<div class="who">' + E(message.sender || "Expéditeur inconnu") + "</div>";
        if (message.type === "unsent") {
          html += '<div class="body"><em>Message retiré par son auteur — contenu absent de l\'export, ' +
            "non reconstituable.</em></div>";
        } else if (message.content) {
          html += '<div class="body">' + E(message.content) + "</div>";
        } else if (message.media_path) {
          html += '<div class="body"><em>Média :</em> <span class="src">' + E(message.media_path) + "</span></div>";
        } else if (message.share) {
          html += '<div class="body"><em>Partage :</em> <span class="src">' +
            E(JSON.stringify(message.share)) + "</span></div>";
        } else {
          html += '<div class="body muted"><em>Aucun contenu textuel dans la source.</em></div>';
        }
        if (message.reactions && message.reactions.length) {
          html += '<div class="rx">' + message.reactions.map(function (r) {
            return E((r.reaction || "") + " " + (r.actor || ""));
          }).join(" · ") + "</div>";
        }
        html += '<div class="meta"><span>' +
          E(message.timestamp ? UI.dateLabel(message.timestamp) : "Date inconnue") + "</span>" +
          '<span class="src">' + E(UI.truncate(message.source_file, 60)) + "</span>" +
          '<button class="btn-raw" data-raw="' + message.id + '">Afficher la source originale</button></div>';
        html += "</div>";
      });
      html += "</div>";
      panel.innerHTML = html;

      Array.prototype.forEach.call(panel.querySelectorAll("[data-raw]"), function (button) {
        button.onclick = async function () {
          const raw = await API.messageRaw(slug(), button.getAttribute("data-raw"));
          UI.modal("Donnée originale du message",
            "<p class=\"src\">Fichier source : " + E(raw.source_file || raw.file_path || "inconnu") + "</p>" +
            "<p class=\"src\">Empreinte SHA-256 du fichier : " + E(raw.file_sha256 || "—") + "</p>" +
            "<pre>" + E(JSON.stringify(raw.donnee_originale, null, 2)) + "</pre>");
        };
      });
    } catch (error) {
      panel.innerHTML = '<div class="note bad">' + E(error.message) + "</div>";
    }
  }

  /* ------------------------------------------------------------ contacts */
  async function people(root) {
    const rows = await API.people(slug());
    let html = head("Contacts", "Personnes rencontrées dans les conversations et les listes de relations.");
    html += T(["Nom d'utilisateur", "Nom affiché", "Messages", "Profil", "Vu pour la première fois"],
      rows.map(function (p) {
        return [
          (p.username ? "@" + p.username : "—") + (p.is_owner ? " (toi)" : ""),
          p.display_name || "—",
          p.message_count || 0,
          p.profile_url ? '<a href="' + E(p.profile_url) + '" target="_blank" rel="noopener">ouvrir</a>' : "—",
          p.first_seen_at ? UI.dateLabel(p.first_seen_at) : "Date inconnue"
        ];
      }), "Aucun contact identifié.");
    root.innerHTML = html;
  }

  /* ------------------------------------------------------------ relations */
  function relationsView(kind, title) {
    return async function (root) {
      const kinds = await API.relationKinds(slug());
      let html = head(title, "Une date n'est affichée que si elle figure réellement dans le fichier source.");
      html += '<div class="card"><div class="btn-row">' +
        '<label class="inline">Trier : <select id="rel-sort">' +
        '<option value="date_desc">Du plus récent au plus ancien</option>' +
        '<option value="date_asc">Du plus ancien au plus récent</option>' +
        '<option value="alpha">Alphabétique (A→Z)</option>' +
        '<option value="alpha_desc">Alphabétique (Z→A)</option></select></label>' +
        '<label class="inline">Type : <select id="rel-kind">' +
        kinds.map(function (k) {
          return '<option value="' + E(k.status) + '"' + (k.status === kind ? " selected" : "") + ">" +
            E(k.label) + " (" + k.n + ")</option>";
        }).join("") + "</select></label>" +
        '<input type="text" id="rel-q" placeholder="filtrer par nom">' +
        '<button class="btn btn-small btn-primary" id="rel-apply">Appliquer</button>' +
        "</div></div>";
      html += '<div id="rel-result"><div class="empty">Chargement…</div></div>';
      root.innerHTML = html;

      async function load() {
        const target = document.getElementById("rel-result");
        const selected = document.getElementById("rel-kind").value;
        const data = await API.relations(slug(), {
          kind: selected,
          sort: document.getElementById("rel-sort").value,
          q: document.getElementById("rel-q").value.trim()
        });
        let out = '<div class="note">' + E(data.note) + "</div>";
        out += T(["Nom d'utilisateur", "Date dans la source", "Profil", "Export", "Fichier source"],
          data.entries.map(function (entry) {
            return [
              entry.username ? "@" + entry.username : (entry.display_name || "inconnu"),
              entry.has_real_date ? UI.dateLabel(entry.date) : '<span class="muted">Date inconnue</span>',
              entry.profile_url ? '<a href="' + E(entry.profile_url) + '" target="_blank" rel="noopener">ouvrir</a>' : "—",
              entry.source_label || "—",
              '<span class="src">' + E(UI.truncate(entry.source_file, 70)) + "</span>"
            ];
          }), "Aucune entrée pour ce type de relation.");
        target.innerHTML = out;
      }

      document.getElementById("rel-apply").onclick = load;
      document.getElementById("rel-kind").onchange = load;
      document.getElementById("rel-sort").onchange = load;
      await load();
    };
  }

  /* ------------------------------------------------------------ chronologie */
  async function timeline(root) {
    let html = head("Chronologie", "Chaque événement affiche sa source et son niveau de confiance.");
    html += '<div class="card"><div class="form-grid">' +
      '<div><label>À partir du</label><input type="date" id="tl-from"></div>' +
      '<div><label>Jusqu\'au</label><input type="date" id="tl-to"></div>' +
      '<div><label>Niveau de confiance</label><select id="tl-conf"><option value="">Tous</option>' +
      '<option value="CONFIRME">CONFIRMÉ</option><option value="PROBABLE">PROBABLE</option>' +
      '<option value="POSSIBLE">POSSIBLE</option><option value="INCONNU">INCONNU</option></select></div>' +
      '<div><label>Type d\'événement</label><select id="tl-kind"><option value="">Tous</option></select></div>' +
      '</div><div class="btn-row"><button class="btn btn-primary btn-small" id="tl-apply">Filtrer</button></div></div>';
    html += '<div id="tl-result"><div class="empty">Chargement…</div></div>';
    root.innerHTML = html;

    async function load() {
      const target = document.getElementById("tl-result");
      const from = document.getElementById("tl-from").value;
      const to = document.getElementById("tl-to").value;
      const data = await API.timeline(slug(), {
        date_from: from ? from + "T00:00:00+00:00" : "",
        date_to: to ? to + "T23:59:59+00:00" : "",
        confidences: document.getElementById("tl-conf").value,
        kinds: document.getElementById("tl-kind").value
      });

      const kindSelect = document.getElementById("tl-kind");
      if (kindSelect.options.length <= 1 && data.kinds_available) {
        data.kinds_available.forEach(function (k) {
          const option = document.createElement("option");
          option.value = k.kind;
          option.textContent = k.label + " (" + k.n + ")";
          kindSelect.appendChild(option);
        });
      }

      let out = '<div class="note">' + Object.keys(data.legend).map(function (key) {
        return "<strong>" + E(key) + "</strong> : " + E(data.legend[key]);
      }).join(" · ") + "</div>";

      if (!data.days.length && !data.undated.length) {
        out += '<div class="empty">Aucun événement pour ces filtres.</div>';
      }
      data.days.forEach(function (day) {
        out += '<div class="card tl-day"><div class="tl-date">' + E(UI.dayLabel(day.date)) + "</div>";
        day.events.forEach(function (event) {
          out += '<div class="tl-ev"><div class="tl-time">' + E(event.time || "--:--") + "</div><div>" +
            '<div class="tl-title">' + E(event.title) + " " + UI.badge(event.confidence) +
            ' <span class="badge tag">' + E(event.kind_label) + "</span></div>" +
            (event.detail ? '<div class="tl-detail">' + E(event.detail) + "</div>" : "") +
            sourceRef(event.source) + "</div></div>";
        });
        out += "</div>";
      });

      if (data.undated.length) {
        out += '<div class="card"><h2 class="card-title">Événements sans date précise</h2>' +
          '<p class="muted">Ces événements ne sont pas datés dans les sources : ils sont bornés ' +
          "entre deux exports, jamais placés arbitrairement à une date.</p>";
        data.undated.forEach(function (event) {
          out += '<div class="tl-ev"><div class="tl-time">—</div><div>' +
            '<div class="tl-title">' + E(event.title) + " " + UI.badge(event.confidence) +
            ' <span class="badge tag">' + E(event.window_label || "Date inconnue") + "</span></div>" +
            (event.detail ? '<div class="tl-detail">' + E(event.detail) + "</div>" : "") +
            sourceRef(event.source) + "</div></div>";
        });
        out += "</div>";
      }
      target.innerHTML = out;
    }

    document.getElementById("tl-apply").onclick = load;
    await load();
  }

  /* ------------------------------------------------------------ imports */
  async function imports(root) {
    const jobs = await API.jobs(slug());
    let html = head("Imports", "Archive ZIP, dossier décompressé, fichiers JSON ou HTML d'un export Instagram.");

    html += '<div class="card"><h2 class="card-title">Importer mes données Instagram</h2>' +
      '<div class="form-grid">' +
      '<div><label>Libellé de la source</label><input type="text" id="imp-label" placeholder="ex. Export Instagram 2025"></div>' +
      "<div><label>Date de l'export (si connue)</label><input type=\"text\" id=\"imp-date\" placeholder=\"AAAA-MM-JJ\"></div>" +
      '<div><label>Provenance déclarée</label><input type="text" id="imp-origin" placeholder="ex. téléchargé sur instagram.com"></div>' +
      '<div><label>Type</label><select id="imp-kind">' +
      '<option value="instagram_export">Export Instagram / Meta</option>' +
      '<option value="local_files">Autres fichiers (captures, PDF, e-mails…)</option></select></div>' +
      "</div>" +
      '<label class="field-label">Observations</label>' +
      '<textarea id="imp-obs" placeholder="Contexte : où as-tu trouvé ces fichiers, quand, dans quel état…"></textarea>' +
      '<h3 class="sub-title">1. Depuis un chemin sur cet ordinateur (recommandé pour les gros exports)</h3>' +
      '<div class="btn-row"><input type="text" id="imp-path" style="flex:1;min-width:280px" ' +
      'placeholder="C:\\Users\\moi\\Downloads\\instagram-export.zip"> ' +
      '<button class="btn btn-primary" id="btn-import-path">Importer ce chemin</button></div>' +
      '<p class="muted">Aucune copie réseau : le fichier est copié dans le dossier d\'enquête, haché en SHA-256, puis analysé.</p>' +
      '<h3 class="sub-title">2. Ou envoyer un fichier</h3>' +
      '<div class="btn-row"><input type="file" id="imp-file"> ' +
      '<button class="btn btn-primary" id="btn-import-file">Envoyer et analyser</button>' +
      '<span id="upload-progress" class="muted"></span></div>' +
      "</div>";

    html += '<div class="card"><h2 class="card-title">Tâches d\'import</h2><div id="jobs-table">' +
      renderJobs(jobs) + "</div></div>";

    root.innerHTML = html;

    function meta() {
      return {
        label: document.getElementById("imp-label").value.trim(),
        declared_date: document.getElementById("imp-date").value.trim(),
        declared_origin: document.getElementById("imp-origin").value.trim(),
        observations: document.getElementById("imp-obs").value.trim(),
        kind: document.getElementById("imp-kind").value
      };
    }

    document.getElementById("btn-import-path").onclick = async function () {
      const path = document.getElementById("imp-path").value.trim();
      if (!path) { UI.toast("Indique le chemin du fichier ou du dossier.", "bad"); return; }
      try {
        const payload = meta();
        payload.path = path;
        const job = await API.importPath(slug(), payload);
        UI.toast("Import lancé : " + job.label);
        global.APP.watchJob(job.id, function () { imports(root); });
      } catch (error) { UI.toast(error.message, "bad"); }
    };

    document.getElementById("btn-import-file").onclick = async function () {
      const input = document.getElementById("imp-file");
      if (!input.files.length) { UI.toast("Choisis un fichier.", "bad"); return; }
      const progress = document.getElementById("upload-progress");
      try {
        const job = await API.importUpload(slug(), input.files[0], meta(), function (percent) {
          progress.textContent = "Transfert : " + percent + " %";
        });
        progress.textContent = "Analyse en cours…";
        UI.toast("Import lancé : " + job.label);
        global.APP.watchJob(job.id, function () { imports(root); });
      } catch (error) { UI.toast(error.message, "bad"); progress.textContent = ""; }
    };
  }

  function renderJobs(jobs) {
    return T(["Tâche", "État", "Étape", "Avancement", "Démarrée", "Résultat"],
      (jobs || []).map(function (job) {
        let result = "—";
        if (job.status === "erreur") result = '<span class="badge PROBABLE">' + E(job.error) + "</span>";
        else if (job.result && job.result.messages !== undefined) {
          result = job.result.conversations + " conversation(s), " + job.result.messages + " message(s), " +
            (job.result.files_parsed || 0) + " fichier(s) analysé(s)";
        } else if (job.result && job.result.files !== undefined) {
          result = job.result.files + " fichier(s) ajouté(s)";
        } else if (job.result && job.result.fichiers) {
          result = "Rapport généré";
        }
        return [job.label, job.status, job.step || "—",
          job.percent === null ? "—" : job.percent + " %",
          UI.dateLabel(job.started_at), result];
      }), "Aucune tâche pour ce dossier.");
  }

  /* ------------------------------------------------------------ sources */
  async function sources(root) {
    const list = await API.sources(slug());
    const evidence = await API.evidence(slug());
    let html = head("Sources", "Tout élément importé est conservé tel quel, avec son empreinte SHA-256.");

    html += '<div class="card"><h2 class="card-title">Sources importées</h2>';
    html += T(["#", "Libellé", "Type", "Provenance déclarée", "Date déclarée", "Fichiers", "Taille",
      "Conversations", "Messages", "Importée le"],
      list.map(function (source) {
        return [source.id, source.label, source.kind, source.declared_origin || "—",
          source.declared_date || "Date inconnue", source.file_count, source.taille_lisible,
          source.conversations, source.messages, UI.dateLabel(source.imported_at)];
      }), "Aucune source.");
    html += "</div>";

    html += '<div class="card"><h2 class="card-title">Ajouter d\'autres sources</h2>' +
      "<p class=\"muted\">Anciennes captures d'écran, vidéos d'écran, sauvegardes de téléphone, " +
      "fichiers téléchargés, archives Instagram, exports Google Drive, dossiers de téléphone, " +
      "notifications exportées, e-mails Instagram sauvegardés, HTML, PDF, TXT, JSON, CSV. " +
      "Utilise l'onglet <em>Imports</em> en choisissant le type « Autres fichiers ».</p>" +
      '<h3 class="sub-title">Ajouter une observation écrite</h3>' +
      '<div class="form-grid">' +
      '<div><label>Libellé</label><input type="text" id="note-label" placeholder="ex. Souvenir daté"></div>' +
      '<div><label>Date déclarée (si connue)</label><input type="text" id="note-date" placeholder="AAAA-MM-JJ"></div>' +
      '<div><label>Provenance</label><input type="text" id="note-origin" placeholder="ex. déclaration personnelle"></div>' +
      "</div>" +
      '<textarea id="note-text" placeholder="Décris ce que tu sais, en distinguant ce dont tu es sûr de ce dont tu te souviens."></textarea>' +
      '<div class="btn-row"><button class="btn btn-primary" id="btn-note">Enregistrer l\'observation</button></div>' +
      '<p class="muted">Une observation est enregistrée comme déclaration, jamais comme fait établi : ' +
      "elle apparaît dans la chronologie avec un niveau de confiance explicite.</p></div>";

    html += '<div class="card"><h2 class="card-title">Éléments de preuve conservés</h2>';
    html += T(["Libellé", "Type", "Provenance déclarée", "Date déclarée", "SHA-256", "Taille", "Observations"],
      evidence.map(function (item) {
        return [item.label, item.evidence_type, item.declared_origin || "—",
          item.declared_date || "Date inconnue",
          '<span class="src">' + E((item.sha256 || "—").slice(0, 24)) + "</span>",
          UI.humanSize(item.size_bytes), UI.truncate(item.observations, 90) || "—"];
      }), "Aucun élément de preuve annoté.");
    html += "</div>";

    root.innerHTML = html;

    document.getElementById("btn-note").onclick = async function () {
      const label = document.getElementById("note-label").value.trim();
      const text = document.getElementById("note-text").value.trim();
      if (!label || !text) { UI.toast("Le libellé et l'observation sont requis.", "bad"); return; }
      try {
        await API.addNote(slug(), {
          label: label,
          observations: text,
          declared_date: document.getElementById("note-date").value.trim(),
          declared_origin: document.getElementById("note-origin").value.trim()
        });
        UI.toast("Observation enregistrée.", "ok");
        await sources(root);
      } catch (error) { UI.toast(error.message, "bad"); }
    };
  }

  /* ------------------------------------------------------------ éléments manquants */
  async function gaps(root) {
    const data = await API.gaps(slug(), {});
    let html = head("Éléments supprimés / manquants",
      "Indices d'absence relevés dans les données. Aucun contenu n'est reconstitué.");
    html += '<div class="note bad">' + E(data.avertissement) + "</div>";

    html += '<div class="card"><h2 class="card-title">Répartition</h2>';
    html += T(["Type d'indice", "Niveau de confiance", "Nombre"],
      (data.par_type || []).map(function (row) {
        return [row.gap_type, UI.badge(row.confidence), row.n];
      }), "Aucun indice détecté.");
    html += "</div>";

    if (!data.items.length) {
      html += '<div class="empty">Aucun élément manquant détecté dans les sources importées.</div>';
    }
    data.items.forEach(function (gap) {
      let window = "";
      if (gap.window_start || gap.window_end) {
        window = " — fenêtre : " + (gap.window_start || "?") + " → " + (gap.window_end || "?");
      }
      html += '<div class="card"><div class="gap-block">' +
        '<span class="label">ÉLÉMENT POTENTIELLEMENT MANQUANT</span> ' + UI.badge(gap.confidence) +
        ' <span class="badge tag">' + E(gap.gap_type) + "</span><br>" +
        E(gap.description) + "<br>" +
        '<span class="src">Indice : ' + E(gap.indicator || "—") + "</span><br>" +
        sourceRef((gap.source_ref || "") + window) + "</div>";
      if (gap.conversation_title) {
        html += '<p class="muted">Conversation concernée : ' + E(gap.conversation_title) + "</p>";
      }
      html += "</div>";
    });
    root.innerHTML = html;
  }

  /* ------------------------------------------------------------ comparaison */
  async function compare(root) {
    const list = await API.sources(slug());
    const previous = await API.comparisons(slug());
    let html = head("Comparaison d'exports",
      "Comparer deux exports permet de dater les changements sans jamais inventer de date.");

    if (list.length < 2) {
      html += '<div class="note warn">Il faut au moins deux sources importées pour comparer. ' +
        "Importe un second export daté d'une autre période.</div>";
    }

    const options = list.map(function (source) {
      return '<option value="' + source.id + '">#' + source.id + " — " + E(source.label) +
        " (" + E(source.declared_date || "date inconnue") + ")</option>";
    }).join("");

    html += '<div class="card"><div class="btn-row">' +
      '<label class="inline">Export le plus ancien : <select id="cmp-a">' + options + "</select></label>" +
      '<label class="inline">Export le plus récent : <select id="cmp-b">' + options + "</select></label>" +
      '<button class="btn btn-primary btn-small" id="btn-compare">Comparer</button></div></div>';

    html += '<div class="card"><h2 class="card-title">Comparaisons enregistrées</h2>';
    html += T(["#", "Export A", "Export B", "Créée le", ""],
      previous.map(function (row) {
        return [row.id, row.label_a, row.label_b, UI.dateLabel(row.created_at),
          '<button class="btn btn-small" data-cmp="' + row.id + '">Afficher</button>'];
      }), "Aucune comparaison enregistrée.");
    html += "</div>";
    html += '<div id="cmp-result"></div>';
    root.innerHTML = html;

    if (list.length >= 2) {
      document.getElementById("cmp-b").selectedIndex = 0;
      document.getElementById("cmp-a").selectedIndex = Math.min(1, list.length - 1);
    }

    document.getElementById("btn-compare").onclick = async function () {
      const a = parseInt(document.getElementById("cmp-a").value, 10);
      const b = parseInt(document.getElementById("cmp-b").value, 10);
      try {
        const result = await API.compare(slug(), a, b);
        document.getElementById("cmp-result").innerHTML = renderComparison(result);
        UI.toast("Comparaison effectuée.", "ok");
      } catch (error) { UI.toast(error.message, "bad"); }
    };

    Array.prototype.forEach.call(root.querySelectorAll("[data-cmp]"), function (button) {
      button.onclick = async function () {
        const result = await API.comparison(slug(), button.getAttribute("data-cmp"));
        document.getElementById("cmp-result").innerHTML = renderComparison(result);
      };
    });
  }

  function renderComparison(result) {
    let html = '<div class="card"><h2 class="card-title">' + E(result.source_a.label) + " → " +
      E(result.source_b.label) + "</h2>";
    html += '<div class="note">Fenêtre de comparaison : entre <strong>' + E(result.window.start_label) +
      "</strong> et <strong>" + E(result.window.end_label) + "</strong>. Tout changement listé " +
      "ci-dessous est intervenu entre ces deux dates.</div>";
    (result.notes || []).forEach(function (note) {
      html += '<div class="note warn">' + E(note) + "</div>";
    });

    const summary = result.summary;
    html += '<div class="kpis">' +
      UI.kpi(summary.relations_added, "Relations apparues") +
      UI.kpi(summary.relations_removed, "Relations disparues") +
      UI.kpi(summary.conversations_added, "Conversations apparues") +
      UI.kpi(summary.conversations_removed, "Conversations disparues") +
      UI.kpi(summary.messages_added, "Messages ajoutés") +
      UI.kpi(summary.messages_removed, "Messages absents du plus récent") +
      UI.kpi(summary.media_added, "Médias ajoutés") +
      UI.kpi(summary.media_removed, "Médias disparus") +
      "</div>";

    Object.keys(result.relations).forEach(function (kind) {
      const block = result.relations[kind];
      if (!block.added.length && !block.removed.length) return;
      html += '<h3 class="sub-title">' + E(block.label) + " (" + block.count_a + " → " + block.count_b + ")</h3>";
      html += T(["Sens", "Compte", "Date présente dans la source", "Constat"],
        block.added.map(function (e) { return ["Apparu", "@" + e.username, e.date_in_source, e.statement]; })
          .concat(block.removed.map(function (e) { return ["Disparu", "@" + e.username, e.date_in_source, e.statement]; })));
    });

    const conversations = result.conversations;
    if (conversations.added.length || conversations.removed.length || conversations.message_count_changes.length) {
      html += '<h3 class="sub-title">Conversations</h3>';
      html += T(["Sens", "Conversation", "Messages", "Constat"],
        conversations.added.map(function (e) { return ["Apparue", e.title, e.message_count, e.statement]; })
          .concat(conversations.removed.map(function (e) { return ["Disparue", e.title, e.message_count, e.statement]; }))
          .concat(conversations.message_count_changes.map(function (e) {
            return ["Volume modifié", e.title, e.count_a + " → " + e.count_b,
              "Écart de " + (e.delta > 0 ? "+" : "") + e.delta + " message(s) entre les deux exports."];
          })));
    }

    const messages = result.messages;
    if (messages.added.length || messages.removed.length) {
      html += '<h3 class="sub-title">Messages</h3>';
      html += T(["Sens", "Conversation", "Expéditeur", "Date", "Extrait"],
        messages.added.slice(0, 300).map(function (e) {
          return ["Ajouté", e.conversation, e.sender, e.timestamp, e.excerpt];
        }).concat(messages.removed.slice(0, 300).map(function (e) {
          return ["Absent du plus récent", e.conversation, e.sender, e.timestamp, e.excerpt];
        })));
    }

    const media = result.media;
    if (media.added.length || media.removed.length) {
      html += '<h3 class="sub-title">Médias</h3>';
      html += T(["Sens", "Fichier"],
        media.added.slice(0, 300).map(function (e) { return ["Ajouté", e.name]; })
          .concat(media.removed.slice(0, 300).map(function (e) { return ["Disparu", e.name]; })));
    }
    return html + "</div>";
  }

  /* ------------------------------------------------------------ médias */
  async function media(root) {
    const data = await API.media(slug(), { limit: 300 });
    let html = head("Médias", data.total + " média(s) référencé(s) dans les sources importées.");
    if (!data.items.length) {
      root.innerHTML = html + '<div class="empty">Aucun média.</div>';
      return;
    }
    html += '<div class="card"><div class="media-grid">';
    data.items.forEach(function (item) {
      const url = item.file_path ? API.fileUrl(slug(), item.file_path) : null;
      let thumb = '<div style="height:120px;display:flex;align-items:center;justify-content:center;' +
        'background:#eef1f6;color:#626b7a;font-size:12px">fichier absent des sources</div>';
      if (url && item.kind === "image") thumb = '<img loading="lazy" src="' + E(url) + '" alt="">';
      else if (url && item.kind === "video") thumb = '<video preload="metadata" src="' + E(url) + '"></video>';
      else if (url) thumb = '<div style="height:120px;display:flex;align-items:center;justify-content:center;' +
        'background:#eef1f6;color:#626b7a;font-size:12px">' + E(item.kind) + "</div>";
      html += '<div class="media-cell">' + thumb + '<div class="cap">' +
        E(item.date_label) + "<br>" + E(UI.truncate(item.rel_path, 60)) +
        (url ? '<br><a href="' + E(url) + '" target="_blank" rel="noopener">ouvrir</a>' : "") +
        "</div></div>";
    });
    html += "</div></div>";
    root.innerHTML = html;
  }

  /* ------------------------------------------------------------ android */
  async function android(root) {
    const guide = await API.androidGuide();
    let status = null;
    try { status = await API.androidStatus(); } catch (error) { status = { adb_installed: false, message: error.message }; }

    let html = head("Android", "Récupérer, avec ton autorisation, les données encore présentes sur ton appareil.");
    html += '<div class="note">' + E(guide.avertissement) + "</div>";

    html += '<div class="card"><h2 class="card-title">État de la connexion adb</h2>' +
      '<p class="' + (status.adb_installed ? "" : "muted") + '">' + E(status.message || "") + "</p>";
    if (status.devices && status.devices.length) {
      html += T(["Numéro de série", "État", "Modèle"],
        status.devices.map(function (device) { return [device.serial, device.state, device.model || "—"]; }));
      html += '<div class="btn-row"><button class="btn btn-primary btn-small" id="btn-scan">Explorer les dossiers publics</button></div>';
    }
    html += '<div id="android-scan"></div></div>';

    guide.guide.forEach(function (section) {
      html += '<div class="card"><h2 class="card-title">' + E(section.titre) + "</h2><ol>";
      section.etapes.forEach(function (step) { html += "<li>" + E(step) + "</li>"; });
      html += "</ol>" + (section.note ? '<p class="muted">' + E(section.note) + "</p>" : "") + "</div>";
    });

    html += '<div class="card"><h2 class="card-title">Ce que ce module ne peut pas faire</h2><ul>';
    guide.limites.forEach(function (limit) { html += "<li>" + E(limit) + "</li>"; });
    html += "</ul></div>";

    html += '<div class="card"><h2 class="card-title">Récupérer l\'accès au compte (voie officielle)</h2>';
    guide.recuperation_du_compte.forEach(function (item) {
      html += "<p><strong>" + E(item.titre) + "</strong><br>" + E(item.detail) + "</p>";
    });
    html += "</div>";

    root.innerHTML = html;

    const scanButton = document.getElementById("btn-scan");
    if (scanButton) {
      scanButton.onclick = async function () {
        const target = document.getElementById("android-scan");
        target.innerHTML = '<p class="muted">Analyse en cours…</p>';
        try {
          const folders = await API.androidScan({});
          let out = "";
          folders.forEach(function (folder) {
            out += '<h3 class="sub-title">' + E(folder.label) + ' <span class="src">' + E(folder.path) + "</span></h3>";
            if (!folder.exists) {
              out += '<p class="muted">' + E(folder.message || "Dossier absent.") + "</p>";
            } else {
              out += "<p>" + folder.count + " élément(s). " +
                '<button class="btn btn-small" data-pull="' + E(folder.path) + '">Importer ce dossier</button></p>';
            }
          });
          target.innerHTML = out;
          Array.prototype.forEach.call(target.querySelectorAll("[data-pull]"), function (button) {
            button.onclick = async function () {
              try {
                const job = await API.androidPull(slug(), { path: button.getAttribute("data-pull") });
                UI.toast("Import Android lancé.");
                global.APP.watchJob(job.id, function () { UI.toast("Import Android terminé.", "ok"); });
              } catch (error) { UI.toast(error.message, "bad"); }
            };
          });
        } catch (error) { target.innerHTML = '<div class="note bad">' + E(error.message) + "</div>"; }
      };
    }
  }

  /* ------------------------------------------------------------ mode preuve */
  async function evidenceMode(root) {
    const info = await API.getCase(slug());
    const active = info.evidence_mode;
    let html = head("Mode preuve", "Fige l'état des fichiers et démontre qu'ils n'ont pas été modifiés depuis l'import.");

    html += '<div class="card"><h2 class="card-title">État</h2>' +
      '<p>' + (active
        ? '<span class="badge CONFIRME">ACTIF</span> Les fichiers sources sont en lecture seule ; aucun import ni suppression n\'est possible.'
        : '<span class="badge INCONNU">INACTIF</span> Les imports sont possibles. Active le mode preuve une fois tes imports terminés.') + "</p>" +
      '<div class="btn-row">' +
      '<button class="btn ' + (active ? "btn-danger" : "btn-primary") + '" id="btn-mode">' +
      (active ? "Désactiver le mode preuve" : "Activer le mode preuve") + "</button>" +
      '<button class="btn" id="btn-verify">Vérifier l\'intégrité maintenant</button>' +
      '<button class="btn" id="btn-manifest">Régénérer manifest.json</button>' +
      "</div></div>";

    html += '<div id="integrity-result"></div>';
    html += '<div class="card"><h2 class="card-title">Comment ça fonctionne</h2><ul>' +
      "<li>À l'import, chaque fichier est copié tel quel et son empreinte SHA-256 est enregistrée.</li>" +
      "<li>Le fichier <span class=\"src\">manifest.json</span> liste : nom, chemin, SHA-256, taille et date d'import.</li>" +
      "<li>La vérification recalcule les empreintes et signale tout fichier modifié ou disparu.</li>" +
      "<li>En mode preuve, les fichiers sources passent en lecture seule et l'outil refuse tout import.</li>" +
      "</ul></div>";

    root.innerHTML = html;

    document.getElementById("btn-mode").onclick = async function () {
      this.disabled = true;
      try {
        const result = await API.setEvidenceMode(slug(), !active);
        UI.toast(result.message, "ok");
        await global.APP.refreshCase();
        await evidenceMode(root);
      } catch (error) { UI.toast(error.message, "bad"); this.disabled = false; }
    };

    document.getElementById("btn-verify").onclick = async function () {
      const target = document.getElementById("integrity-result");
      target.innerHTML = '<div class="card"><p class="muted">Recalcul des empreintes…</p></div>';
      const result = await API.integrity(slug(), true);
      let out = '<div class="card"><h2 class="card-title">Vérification d\'intégrité</h2>';
      out += '<div class="note ' + (result.integrite_intacte ? "ok" : "bad") + '">' +
        (result.integrite_intacte
          ? "Aucune modification détectée : les " + result.fichiers_verifies +
            " fichier(s) vérifié(s) sont identiques à leur état au moment de l'import."
          : "DIVERGENCE : " + result.fichiers_modifies.length + " fichier(s) modifié(s), " +
            result.fichiers_absents.length + " absent(s).") + "</div>";
      out += T(["Champ", "Valeur"], [
        ["Vérifié le", UI.dateLabel(result.verifie_le)],
        ["Fichiers enregistrés", result.fichiers_enregistres],
        ["Fichiers vérifiés", result.fichiers_verifies],
        ["Profondeur", result.profondeur]
      ]);
      if (result.fichiers_modifies.length) {
        out += '<h3 class="sub-title">Fichiers modifiés depuis l\'import</h3>';
        out += T(["Fichier", "SHA-256 à l'import", "SHA-256 actuel"],
          result.fichiers_modifies.map(function (m) {
            return ['<span class="src">' + E(m.chemin) + "</span>",
              '<span class="src">' + E(m.sha256_import) + "</span>",
              '<span class="src">' + E(m.sha256_actuel) + "</span>"];
          }));
      }
      if (result.fichiers_absents.length) {
        out += '<h3 class="sub-title">Fichiers absents</h3>';
        out += T(["Fichier"], result.fichiers_absents.map(function (p) { return [p]; }));
      }
      target.innerHTML = out + "</div>";
    };

    document.getElementById("btn-manifest").onclick = async function () {
      const manifest = await API.manifest(slug());
      UI.toast("manifest.json régénéré : " + manifest.nombre_de_fichiers + " fichier(s).", "ok");
      UI.modal("manifest.json", "<p>Empreinte du manifeste : <span class=\"src\">" +
        E(manifest.empreinte_du_manifeste) + "</span></p><pre>" +
        E(JSON.stringify(manifest.fichiers.slice(0, 200), null, 2)) + "</pre>");
    };
  }

  /* ------------------------------------------------------------ checklist */
  async function checklist(root) {
    const data = await API.checklist(slug());
    let html = head("Récupérer davantage de données", "Ce qui manque et comment l'obtenir.");
    html += '<div class="card"><h2 class="card-title">Progression : ' + data.progress + " %</h2>" +
      '<div class="progress"><div style="width:' + data.progress + '%"></div></div>' +
      '<p class="muted">' + data.done + " élément(s) sur " + data.total + ".</p>";
    data.items.forEach(function (item) {
      html += '<div class="check-item"><div class="check-mark ' + (item.done ? "ok" : "no") + '">' +
        (item.done ? "✅ OK" : "❌ MANQUANT") + "</div><div><strong>" + E(item.label) + "</strong><br>" +
        '<span class="muted">' + E(item.advice) + "</span></div></div>";
    });
    html += "</div>";

    html += '<div class="card"><h2 class="card-title">Ce qui ne peut pas être récupéré sans reprendre le contrôle du compte</h2><ul>';
    (data.non_recuperable || []).forEach(function (line) { html += "<li>" + E(line) + "</li>"; });
    html += "</ul></div>";
    root.innerHTML = html;
  }

  /* ------------------------------------------------------------ rapport */
  async function report(root) {
    const preview = await API.reportPreview(slug());
    const existing = await API.reports(slug());
    let html = head("Rapport final", "Rapport Instagram Evidence Recovery — HTML, PDF, JSON et CSV.");

    html += '<div class="card"><h2 class="card-title">Résumé</h2>';
    html += T(["Élément", "Valeur"], preview.resume.map(function (line) {
      const parts = line.split(" : ");
      return [parts[0], parts.slice(1).join(" : ")];
    }));
    html += '<div class="btn-row"><button class="btn btn-primary" id="btn-report">Générer le rapport complet</button></div>';
    html += '<p class="muted">Le rapport reprend : compte concerné, période étudiée, sources utilisées, ' +
      "intégrité des fichiers, conversations, chronologie, abonnés, abonnements, changements détectés, " +
      "médias, éléments potentiellement manquants et limites de la récupération.</p></div>";

    html += '<div class="card"><h2 class="card-title">Limites de la récupération</h2><ul>';
    preview.limites.forEach(function (line) { html += "<li>" + E(line) + "</li>"; });
    html += "</ul></div>";

    html += '<div class="card"><h2 class="card-title">Rapports générés</h2><div id="reports-list">' +
      renderReports(existing) + "</div></div>";
    root.innerHTML = html;

    document.getElementById("btn-report").onclick = async function () {
      this.disabled = true;
      this.textContent = "Génération en cours…";
      try {
        const job = await API.generateReport(slug());
        global.APP.watchJob(job.id, async function (finished) {
          if (finished.status === "termine") {
            UI.toast("Rapport généré.", "ok");
            (finished.result.avertissements || []).forEach(function (warning) { UI.toast(warning, "bad"); });
          } else {
            UI.toast("Échec : " + finished.error, "bad");
          }
          await report(root);
        });
      } catch (error) {
        UI.toast(error.message, "bad");
        this.disabled = false;
        this.textContent = "Générer le rapport complet";
      }
    };
  }

  function renderReports(list) {
    return T(["Rapport", "HTML", "PDF", "JSON", "Archive ZIP", "Emplacement"],
      (list || []).map(function (item) {
        function link(rel, label) {
          return rel ? '<a href="' + E(API.fileUrl(slug(), rel)) + '" target="_blank" rel="noopener">' + label + "</a>" : "—";
        }
        return [item.nom, link(item.rel_html, "ouvrir"), link(item.rel_pdf, "ouvrir"),
          link(item.rel_json, "ouvrir"), link(item.rel_zip, "télécharger"),
          '<span class="src">' + E(item.chemin) + "</span>"];
      }), "Aucun rapport généré pour l'instant.");
  }

  /* ------------------------------------------------------------ recherche */
  async function searchView(root, query) {
    let html = head("Recherche globale", 'Résultats pour « ' + (query || "") + " »");
    html += '<div class="card"><div class="form-grid">' +
      '<div><label>Type d\'élément</label><select id="s-kind"><option value="">Tous</option>' +
      '<option value="message">Messages</option><option value="personne">Personnes</option>' +
      '<option value="evenement">Événements</option><option value="preuve">Éléments de preuve</option>' +
      '<option value="fichier">Fichiers</option><option value="media">Médias</option>' +
      '<option value="manquant">Éléments manquants</option><option value="conversation">Conversations</option>' +
      '<option value="public">Collectes publiques</option></select></div>' +
      '<div><label>Personne</label><input type="text" id="s-person"></div>' +
      '<div><label>À partir du</label><input type="date" id="s-from"></div>' +
      '<div><label>Jusqu\'au</label><input type="date" id="s-to"></div>' +
      '<div><label>Fichier / source</label><input type="text" id="s-source" placeholder="nom de fichier"></div>' +
      '</div><div class="btn-row"><button class="btn btn-primary btn-small" id="s-apply">Filtrer</button></div></div>';
    html += '<div id="s-result"><div class="empty">Recherche…</div></div>';
    root.innerHTML = html;

    async function load() {
      const target = document.getElementById("s-result");
      const from = document.getElementById("s-from").value;
      const to = document.getElementById("s-to").value;
      const data = await API.search(slug(), {
        q: query,
        kinds: document.getElementById("s-kind").value,
        person: document.getElementById("s-person").value.trim(),
        date_from: from ? from + "T00:00:00+00:00" : "",
        date_to: to ? to + "T23:59:59+00:00" : "",
        source_ref: document.getElementById("s-source").value.trim()
      });
      let out = '<p class="muted">' + data.count + " résultat(s).</p>";
      out += T(["Type", "Titre", "Extrait", "Date", "Source"],
        data.results.map(function (row) {
          return ['<span class="badge tag">' + E(row.kind) + "</span>", row.title,
            row.excerpt, row.timestamp ? UI.dateLabel(row.timestamp) : "Date inconnue",
            '<span class="src">' + E(UI.truncate(row.source_ref, 60)) + "</span>"];
        }), "Aucun résultat.");
      target.innerHTML = out;
    }

    document.getElementById("s-apply").onclick = load;
    await load();
  }

  global.VIEWS = {
    dashboard: dashboard,
    profile: profile,
    conversations: conversations,
    people: people,
    followers: relationsView("followers", "Abonnés"),
    following: relationsView("following", "Abonnements"),
    timeline: timeline,
    imports: imports,
    sources: sources,
    gaps: gaps,
    compare: compare,
    media: media,
    android: android,
    evidence: evidenceMode,
    checklist: checklist,
    report: report,
    search: searchView
  };
})(window);
