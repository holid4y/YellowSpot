/*
 * YellowSpot UI clean — cosmetic comfort layer (v0.3.0).
 *
 * Community-inspired, client-side only. Ideas adopted from the open
 * Spotify-userscript community (Button-Remover / SpotiVibe / SpotiwebJS
 * lineage): keep the app header free of upsell clutter such as the
 * "Install app" button, which is useless inside a dedicated Android app.
 *
 * Safety rules, same philosophy as adguard.js:
 *  - display-only: never clicks, never removes DOM nodes (React stays
 *    reconciliation-stable), never touches network or playback;
 *  - scoped: only inspects buttons/links inside the page <header> area,
 *    so ordinary content (playlists, songs, podcasts) can never match;
 *  - conservative matching: requires an action word AND an app word, so
 *    header buttons like Home, Search or the profile avatar never match;
 *  - capped and lazy: debounced scans, idle disconnect after a while.
 */
(function () {
  'use strict';

  if (window.__yellowspotUiClean) { return; }
  window.__yellowspotUiClean = true;

  // Must contain an action word …
  var ACTION_RE = /(install|instalar|baix|descarg|download|hent|telep|pobierz|zainstaluj|installa|indir|get)/i;
  // … and an application word, so "Download" links for other things or
  // plain "Get" buttons are not hidden by accident.
  var APP_RE = /\b(app|aplicativo|aplica|appli|app$)/i;

  var HIDDEN_ATTR = 'data-ys-hide-install';
  var MAX_IDLE_PASSES = 240; // after ~2 min without a DOM change, stop scanning
  var idlePasses = 0;
  var scanQueued = false;
  var observer = null;

  function isHeaderControl(el) {
    // Only ever look at controls inside the global top header.
    if (!el || el.nodeType !== 1) { return false; }
    var header = el.closest ? el.closest('header') : null;
    return !!header;
  }

  function hideEl(el) {
    try {
      el.setAttribute(HIDDEN_ATTR, '1');
      el.style.setProperty('display', 'none', 'important');
    } catch (e) { /* detached or protected node — ignore */ }
  }

  function scan() {
    scanQueued = false;
    try {
      var nodes = document.querySelectorAll('header a, header button, header [role="button"]');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.hasAttribute(HIDDEN_ATTR)) { continue; }
        if (!isHeaderControl(el)) { continue; }
        // data URI / class signals first (locale independent), then text.
        var label = (el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '');
        label = label.trim();
        if (label.length === 0 || label.length > 80) { continue; }
        if (ACTION_RE.test(label) && APP_RE.test(label)) {
          hideEl(el);
        }
      }
    } catch (e) { /* DOM not ready or mid-teardown — ignore */ }
  }

  function queueScan() {
    idlePasses = 0;
    if (scanQueued) { return; }
    scanQueued = true;
    setTimeout(scan, 400);
  }

  function start() {
    try {
      if (!document.body) { return; }
      observer = new MutationObserver(function () {
        idlePasses++;
        if (idlePasses > MAX_IDLE_PASSES) {
          observer.disconnect();
          return;
        }
        queueScan();
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
      queueScan();
    } catch (e) { /* environment without MutationObserver — cosmetic only */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();
