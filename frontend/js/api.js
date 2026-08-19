/* Client de l'API locale. Toutes les requêtes restent sur 127.0.0.1. */
(function (global) {
  "use strict";

  async function request(method, path, body, isForm) {
    const options = { method: method, headers: {} };
    if (body !== undefined && body !== null) {
      if (isForm) {
        options.body = body;
      } else {
        options.headers["Content-Type"] = "application/json";
        options.body = JSON.stringify(body);
      }
    }
    const response = await fetch(path, options);
    const text = await response.text();
    let payload = null;
    if (text) {
      try { payload = JSON.parse(text); } catch (e) { payload = text; }
    }
    if (!response.ok) {
      const detail = (payload && payload.detail) ? payload.detail : ("Erreur HTTP " + response.status);
      const error = new Error(typeof detail === "string" ? detail : JSON.stringify(detail));
      error.status = response.status;
      error.payload = payload;
      throw error;
    }
    return payload;
  }

  function qs(params) {
    const parts = [];
    Object.keys(params || {}).forEach(function (key) {
      const value = params[key];
      if (value === undefined || value === null || value === "") return;
      parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(value));
    });
    return parts.length ? "?" + parts.join("&") : "";
  }

  const API = {
    health: () => request("GET", "/api/health"),

    listCases: () => request("GET", "/api/cases"),
    createCase: (username, notes) => request("POST", "/api/cases", { username: username, notes: notes }),
    getCase: (slug) => request("GET", "/api/cases/" + slug),
    deleteCase: (slug) => request("DELETE", "/api/cases/" + slug),
    stats: (slug) => request("GET", "/api/cases/" + slug + "/stats"),
    checklist: (slug) => request("GET", "/api/cases/" + slug + "/checklist"),
    audit: (slug) => request("GET", "/api/cases/" + slug + "/audit"),

    importPath: (slug, payload) => request("POST", "/api/cases/" + slug + "/import/path", payload),
    importUpload: function (slug, file, meta, onProgress) {
      return new Promise(function (resolve, reject) {
        const form = new FormData();
        form.append("file", file);
        const url = "/api/cases/" + slug + "/import/upload" + qs(meta || {});
        const xhr = new XMLHttpRequest();
        xhr.open("POST", url);
        if (onProgress && xhr.upload) {
          xhr.upload.onprogress = function (event) {
            if (event.lengthComputable) onProgress(Math.round(100 * event.loaded / event.total));
          };
        }
        xhr.onload = function () {
          let payload = null;
          try { payload = JSON.parse(xhr.responseText); } catch (e) { payload = xhr.responseText; }
          if (xhr.status >= 200 && xhr.status < 300) resolve(payload);
          else reject(new Error((payload && payload.detail) || ("Erreur HTTP " + xhr.status)));
        };
        xhr.onerror = function () { reject(new Error("Échec du transfert du fichier.")); };
        xhr.send(form);
      });
    },
    jobs: (slug) => request("GET", "/api/jobs" + qs({ case: slug })),
    job: (id) => request("GET", "/api/jobs/" + id),

    sources: (slug) => request("GET", "/api/cases/" + slug + "/sources"),
    files: (slug, params) => request("GET", "/api/cases/" + slug + "/files" + qs(params)),

    conversations: (slug, params) => request("GET", "/api/cases/" + slug + "/conversations" + qs(params)),
    messages: (slug, params) => request("GET", "/api/cases/" + slug + "/conversations/messages" + qs(params)),
    messageRaw: (slug, id) => request("GET", "/api/cases/" + slug + "/messages/" + id + "/raw"),

    relations: (slug, params) => request("GET", "/api/cases/" + slug + "/relations" + qs(params)),
    relationKinds: (slug) => request("GET", "/api/cases/" + slug + "/relation-kinds"),
    people: (slug, q) => request("GET", "/api/cases/" + slug + "/people" + qs({ q: q })),

    timeline: (slug, params) => request("GET", "/api/cases/" + slug + "/timeline" + qs(params)),
    gaps: (slug, params) => request("GET", "/api/cases/" + slug + "/gaps" + qs(params)),
    search: (slug, params) => request("GET", "/api/cases/" + slug + "/search" + qs(params)),
    reindex: (slug) => request("POST", "/api/cases/" + slug + "/reindex"),

    compare: (slug, a, b) => request("POST", "/api/cases/" + slug + "/compare", { source_a: a, source_b: b }),
    comparisons: (slug) => request("GET", "/api/cases/" + slug + "/comparisons"),
    comparison: (slug, id) => request("GET", "/api/cases/" + slug + "/comparisons/" + id),

    publicData: (slug) => request("GET", "/api/cases/" + slug + "/public"),
    publicCollect: (slug) => request("POST", "/api/cases/" + slug + "/public/collect"),
    publicManual: (slug, payload) => request("POST", "/api/cases/" + slug + "/public/manual", payload),

    evidence: (slug, type) => request("GET", "/api/cases/" + slug + "/evidence" + qs({ evidence_type: type })),
    addNote: (slug, payload) => request("POST", "/api/cases/" + slug + "/evidence/note", payload),
    updateEvidence: (slug, id, payload) => request("PATCH", "/api/cases/" + slug + "/evidence/" + id, payload),

    media: (slug, params) => request("GET", "/api/cases/" + slug + "/media" + qs(params)),
    resolveMedia: (slug, path) => request("GET", "/api/cases/" + slug + "/resolve-media" + qs({ media_path: path })),
    fileUrl: (slug, relPath) => "/api/cases/" + slug + "/file" + qs({ rel_path: relPath }),

    setEvidenceMode: (slug, enabled) => request("POST", "/api/cases/" + slug + "/evidence-mode", { enabled: enabled }),
    integrity: (slug, deep) => request("GET", "/api/cases/" + slug + "/integrity" + qs({ deep: deep })),
    manifest: (slug) => request("GET", "/api/cases/" + slug + "/manifest"),

    reportPreview: (slug) => request("GET", "/api/cases/" + slug + "/report/preview"),
    generateReport: (slug) => request("POST", "/api/cases/" + slug + "/report"),
    reports: (slug) => request("GET", "/api/cases/" + slug + "/reports"),

    androidGuide: () => request("GET", "/api/android/guide"),
    androidStatus: () => request("GET", "/api/android/status"),
    androidScan: (params) => request("GET", "/api/android/scan" + qs(params)),
    androidPull: (slug, payload) => request("POST", "/api/cases/" + slug + "/android/pull", payload)
  };

  global.API = API;
})(window);
