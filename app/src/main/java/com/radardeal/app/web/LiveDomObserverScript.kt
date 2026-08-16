package com.radardeal.app.web

/**
 * The page-side half of the live detection channel.
 *
 * It is injected at document start on the Vinted results page and does three things:
 *
 *  1. keeps a `Set` of the item ids it has already reported, so a re-render of the same grid
 *     costs one hash lookup per card and nothing else;
 *  2. installs a `MutationObserver` on the results container and inspects **only the nodes that
 *     were actually added** — never the whole grid;
 *  3. posts a small structured object per genuinely new card. The DOM is never transferred.
 *
 * Written as conservative ES5-ish JavaScript so that older WebView builds parse it, and wrapped
 * so a throw inside the page can never take the channel down silently.
 */
internal object LiveDomObserverScript {

    const val BRIDGE = "radarDealLive"

    /** Message types the Kotlin side understands. Anything else is ignored. */
    const val TYPE_NEW_ITEM = "NEW_ITEM"
    const val TYPE_READY = "READY"
    const val TYPE_MUTATION = "MUTATION"
    const val TYPE_CHALLENGE = "CHALLENGE"

    val SOURCE: String = """
(function () {
  if (window.__radarDealLiveInstalled) { return; }
  window.__radarDealLiveInstalled = true;

  var seen = new Set();
  var pending = [];
  var flushHandle = null;
  var mutationCount = 0;

  function post(payload) {
    try {
      if (window.$BRIDGE && window.$BRIDGE.postMessage) {
        window.$BRIDGE.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* the channel is best-effort; never break the page */ }
  }

  function idFromHref(href) {
    if (!href) { return null; }
    var m = href.match(/\/items\/(\d+)/);
    return m ? m[1] : null;
  }

  function textOf(node, selector) {
    try {
      var el = node.querySelector(selector);
      return el ? (el.textContent || '').trim() : null;
    } catch (e) { return null; }
  }

  /** Pulls the smallest useful description of a card. Missing fields stay null. */
  function extract(anchor) {
    var id = idFromHref(anchor.getAttribute('href'));
    if (!id || seen.has(id)) { return null; }

    var card = anchor.closest('[data-testid], article, li, div') || anchor;
    var img = null;
    try {
      var imgEl = card.querySelector('img');
      img = imgEl ? (imgEl.getAttribute('src') || imgEl.getAttribute('data-src')) : null;
    } catch (e) { img = null; }

    var priceText =
      textOf(card, '[data-testid*="price"]') ||
      textOf(card, '[class*="price"]');

    var title =
      anchor.getAttribute('title') ||
      textOf(card, '[data-testid*="title"]') ||
      textOf(card, 'h3') ||
      null;

    return {
      type: '$TYPE_NEW_ITEM',
      id: id,
      title: title,
      priceText: priceText,
      imageUrl: img,
      itemUrl: anchor.href || null,
      observedAt: Date.now()
    };
  }

  /** Batches within one animation frame: a grid render is one message, not fifty. */
  function enqueue(payload) {
    seen.add(payload.id);
    pending.push(payload);
    if (flushHandle !== null) { return; }
    flushHandle = setTimeout(function () {
      flushHandle = null;
      var batch = pending;
      pending = [];
      for (var i = 0; i < batch.length; i++) { post(batch[i]); }
    }, 16);
  }

  function scanNode(node) {
    if (!node || node.nodeType !== 1) { return; }
    try {
      if (node.matches && node.matches('a[href*="/items/"]')) {
        var direct = extract(node);
        if (direct) { enqueue(direct); }
      }
      var anchors = node.querySelectorAll ? node.querySelectorAll('a[href*="/items/"]') : [];
      for (var i = 0; i < anchors.length; i++) {
        var payload = extract(anchors[i]);
        if (payload) { enqueue(payload); }
      }
    } catch (e) { /* malformed node, skip it */ }
  }

  /**
   * The initial page load is a baseline: everything already on screen is recorded as seen but
   * never reported, exactly like the first scan of a veille.
   */
  function baseline() {
    try {
      var anchors = document.querySelectorAll('a[href*="/items/"]');
      for (var i = 0; i < anchors.length; i++) {
        var id = idFromHref(anchors[i].getAttribute('href'));
        if (id) { seen.add(id); }
      }
      post({ type: '$TYPE_READY', count: seen.size, observedAt: Date.now() });
    } catch (e) {
      post({ type: '$TYPE_READY', count: 0, observedAt: Date.now() });
    }
  }

  function looksLikeChallenge() {
    try {
      var body = (document.body && document.body.innerText || '').slice(0, 2000).toLowerCase();
      return body.indexOf('captcha') >= 0 ||
             body.indexOf('verify you are human') >= 0 ||
             body.indexOf('vérification') >= 0 ||
             document.querySelector('#px-captcha, .g-recaptcha, iframe[src*="captcha"]') !== null;
    } catch (e) { return false; }
  }

  function install() {
    if (looksLikeChallenge()) {
      post({ type: '$TYPE_CHALLENGE', observedAt: Date.now() });
      return;
    }

    baseline();

    try {
      var observer = new MutationObserver(function (mutations) {
        mutationCount += mutations.length;
        for (var i = 0; i < mutations.length; i++) {
          var added = mutations[i].addedNodes;
          for (var j = 0; j < added.length; j++) { scanNode(added[j]); }
        }
        if (mutationCount % 25 === 0) {
          post({ type: '$TYPE_MUTATION', count: mutationCount, observedAt: Date.now() });
        }
      });

      observer.observe(document.body || document.documentElement, {
        childList: true,
        subtree: true
      });
      window.__radarDealObserver = observer;
    } catch (e) { /* no observer: the polling engine still covers this watch */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install);
  } else {
    install();
  }
})();
""".trimIndent()
}
