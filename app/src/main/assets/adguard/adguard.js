/*
 * YellowSpot ad guard for the Spotify web player.
 *
 * Why this exists: hard network-blocking of Spotify's ad requests (what a
 * classic filter-list blocker does to audio ads) wedges the web player.
 * The ad slot starts, its tracking/impression requests fail, the ad can
 * never complete, and playback freezes at 0:00 until the page is reloaded
 * by hand. This script deliberately performs NO network blocking. It lets
 * the ad session run, but silences the ad audio, fast-forwards it to its
 * end (or cuts its source when it is already dead), clicks any Skip
 * button, and nudges the player back to music. A watchdog detects stalls
 * and, as a last resort, reloads the page automatically (rate-limited)
 * instead of leaving a frozen player behind.
 *
 * Every intervention is strictly capped per ad session so this guard can
 * never wedge the player itself:
 *   - max ticks / wall time per ad session, then "mute-only" give-up mode;
 *   - max three source cuts per ad;
 *   - max three play/pause nudges per ad;
 *   - max one automatic reload per ad, and never more than one reload per
 *     three minutes across the whole browsing session (persisted).
 *
 * Ad detection prefers locale-independent signals (Spotify data-testid
 * attributes and /ad links); localized aria-labels are only a weak signal
 * that mutes but never seeks, so real songs can never be fast-forwarded
 * by accident.
 */
(function () {
  'use strict';

  // ---- Tuning ---------------------------------------------------------------
  var TICK_MS = 400;                // main loop cadence
  var DEEP_SCAN_EVERY = 5;           // ticks between shadow-DOM scans
  var AD_STALL_MS = 8000;            // ad frozen this long => recovery
  var CONTENT_STALL_MS = 12000;      // music frozen this long => recovery
  var NUDGE_SPACING_MS = 4000;       // play/pause nudge spacing
  var MAX_TICKS_PER_AD = 75;         // ~30s of ticks per ad session
  var MAX_AD_MS = 35000;             // wall-time budget per ad session
  var MAX_CLEARS_PER_AD = 3;         // source cuts per ad
  var MAX_NUDGES_PER_AD = 3;         // play/pause nudges per ad
  var MAX_RESUME_TRIES = 3;          // play attempts after ad ends
  var MAX_CONTENT_NUDGES = 3;        // restart attempts for stalled music
  var RESUME_SPACING_MS = 1400;      // spacing of the post-ad resume steps
  var RELOAD_COOLDOWN_MS = 180000;   // max one auto-reload per 3 minutes
  var FFWD_RATE = 16;                // fastest allowed HTMLMediaElement rate

  var AD_WORD = /advertis|an[uú]ncio|anuncio|propaganda|publicidad|publicidade|werbung|reklame|reklam|annonce|sponsoris|gesponsert|patrocinado|sponsored/i;
  var SKIPISH = /(skip|pular|saltar|überspringen|passer)/i;
  var ADISH = /(ad\b|ads\b|advertis|an[uú]ncio|anuncio|publicidad|publicidade|werbung|reklame|reklam|annonce|propaganda)/i;

  var RELOAD_KEY = 'yellowspot-last-reload';
  var RESUME_KEY = 'yellowspot-resume-after-reload';

  // ---- State ----------------------------------------------------------------
  var timer = null;
  var tickCount = 0;
  var known = null;            // Set of raw media elements
  var priorAudio = null;        // WeakMap of pre-ad mute/volume state
  var active = null;           // raw element that most recently started playing
  var hookInstalled = false;

  var inAd = false;
  var adTicks = 0;
  var adStart = 0;
  var adClears = 0;
  var adNudges = 0;
  var gaveUp = false;
  var cleanTicks = 0;

  var lastPosition = -1;
  var lastProgressAt = 0;
  var lastNudgeAt = 0;
  var contentNudges = 0;

  function log() {
    try {
      var parts = ['[YellowSpot adguard]'];
      for (var i = 0; i < arguments.length; i++) parts.push(String(arguments[i]));
      console.debug(parts.join(' '));
    } catch (e) { /* logging must never be fatal */ }
  }

  // ---- Media element discovery ----------------------------------------------
  // Spotify creates audio/video elements dynamically and has at times kept
  // them detached from the DOM or inside shadow roots. Discovery is layered:
  //   1. a createElement hook (via wrappedJSObject, a Gecko content-script
  //      privilege) registers elements the instant the page creates them,
  //      even if they never enter the DOM;
  //   2. a periodic DOM scan (including open shadow roots) catches anything
  //      the hook missed, and keeps the guard working if the hook cannot
  //      be installed.
  function raw(el) {
    return (el && el.wrappedJSObject) || el;
  }

  function instrument(el) {
    var r = raw(el);
    if (!r || !known) return;
    var tag = '';
    try { tag = String(r.tagName || '').toUpperCase(); } catch (e) { return; }
    if (tag !== 'AUDIO' && tag !== 'VIDEO') return;
    if (known.has(r)) return;
    try { known.add(r); } catch (e) { return; }
    try {
      r.addEventListener('play', function () { active = r; }, true);
      r.addEventListener('playing', function () { active = r; }, true);
    } catch (e) { /* listeners are best-effort */ }
    if (inAd) silence(r); // late-registered element arriving mid-ad
  }

  function scanMedia(deep) {
    try {
      var els = document.querySelectorAll('audio, video');
      for (var i = 0; i < els.length; i++) instrument(els[i]);
    } catch (e) { /* scan is best-effort */ }
    if (!deep) return;
    try {
      var hosts = document.querySelectorAll('*');
      for (var j = 0; j < hosts.length; j++) {
        var sr = hosts[j].shadowRoot;
        if (!sr) continue;
        var inner = sr.querySelectorAll('audio, video');
        for (var k = 0; k < inner.length; k++) instrument(inner[k]);
      }
    } catch (e) { /* shadow scan is best-effort */ }
    // Deliberately no pruning of the element registry: Spotify keeps only a
    // small pool of media elements, and a paused, detached, src-less element
    // is exactly what a not-yet-started player looks like. Dropping one
    // would silence nothing when the ad finally begins.
  }

  function installCreateHook() {
    if (hookInstalled) return;
    try {
      var pageDoc = window.wrappedJSObject && document.wrappedJSObject;
      if (!pageDoc || typeof pageDoc.createElement !== 'function') return;
      var orig = pageDoc.createElement;
      if (typeof orig !== 'function') return;
      pageDoc.createElement = function () {
        var el = orig.apply(this, arguments);
        try {
          var tag = String((el && el.tagName) || '').toUpperCase();
          if (tag === 'AUDIO' || tag === 'VIDEO') instrument(el);
        } catch (e) { /* the hook must never break page creation */ }
        return el;
      };
      hookInstalled = true;
      log('createElement hook installed');
    } catch (e) {
      log('createElement hook unavailable, relying on DOM scans');
    }
  }

  // ---- Audio muting / restoring ----------------------------------------------
  function silence(r) {
    try {
      if (priorAudio) {
        var prior = priorAudio.get(r);
        if (prior === undefined) priorAudio.set(r, { muted: r.muted, volume: r.volume });
      }
      r.muted = true;
      try { r.volume = 0; } catch (e) { /* volume can be locked in odd cases */ }
    } catch (e) { /* muting is best-effort */ }
  }

  function restore(r) {
    try {
      var prior = priorAudio && priorAudio.get(r);
      if (prior) {
        r.muted = !!prior.muted;
        try { r.volume = prior.volume; } catch (e) { /* best-effort */ }
        if (priorAudio) priorAudio.delete(r);
      }
      try { if (r.playbackRate > 4) r.playbackRate = 1; } catch (e) { /* best-effort */ }
    } catch (e) { /* restoring is best-effort */ }
  }

  function silenceAll() {
    if (!known) return;
    try { known.forEach(silence); } catch (e) { /* best-effort */ }
  }

  function restoreAll() {
    if (!known) return;
    try { known.forEach(restore); } catch (e) { /* best-effort */ }
  }

  // ---- Ad handling -----------------------------------------------------------
  // Fast-forward the ad to its end so the player advances immediately.
  // If the ad has no media data (the classic uBO-blocked ad stuck at 0:00),
  // cut its source so the player takes its "ad finished" branch. The clear
  // is capped: three cuts per ad, never more, or the guard itself would
  // keep interrupting the player (a failure mode seen in older scripts).
  function fastForward(r) {
    if (gaveUp) return;
    try {
      var duration = r.duration;
      if (r.readyState >= 1 && isFinite(duration) && duration > 0.2) {
        try { if (r.playbackRate !== FFWD_RATE) r.playbackRate = FFWD_RATE; } catch (e) { /* rate caps vary */ }
        try { r.currentTime = Math.max(0, duration - 0.05); } catch (e) { /* seek needs metadata, guarded above */ }
        return;
      }
    } catch (e) { /* fall through to the dead-source path */ }
    if (adClears < MAX_CLEARS_PER_AD) {
      adClears++;
      try { r.removeAttribute('src'); r.load(); } catch (e) { /* best-effort */ }
      log('cleared dead ad source (' + adClears + '/' + MAX_CLEARS_PER_AD + ')');
    }
  }

  // Returns 'strong' (locale-independent ad markers), 'weak' (localized
  // text only) or false. Weak signals mute but never seek, so a song whose
  // title happens to match a localized ad word can never be skipped.
  function adSignal() {
    try {
      var bar = document.querySelector('[data-testid="now-playing-bar"]');
      if (bar) {
        var adType = bar.getAttribute('data-testadtype');
        if (typeof adType === 'string' && adType !== '' && adType !== 'ad-type-none') return 'strong';
      }
      if (document.querySelector('[data-testid="context-item-info-ad-subtitle"]')) return 'strong';
      var link = document.querySelector(
        '[data-testid="now-playing-widget"] a[href^="/ad"], ' +
        '[data-testid="now-playing-widget"] a[href^="/ads"], ' +
        '[data-testid="now-playing-widget"] a[href^="/sponsored"]');
      if (link) return 'strong';
      var widget = document.querySelector('[data-testid="now-playing-widget"]');
      if (widget) {
        var labels = widget.querySelectorAll('[aria-label]');
        for (var i = 0; i < labels.length; i++) {
          var label = labels[i].getAttribute('aria-label') || '';
          if (AD_WORD.test(label)) return 'weak';
        }
      }
    } catch (e) {
      log('ad detection error');
    }
    return false;
  }

  // Clicks a visible "Skip ad" button. A skip control must either carry a
  // skip-ish data-testid or mention both a skip word and an ad word in its
  // label, so transport buttons like "Skip to next" are never matched.
  function clickSkip() {
    try {
      var buttons = document.querySelectorAll('button[aria-label], button[data-testid]');
      for (var i = 0; i < buttons.length; i++) {
        var b = buttons[i];
        var label = b.getAttribute('aria-label') || '';
        var testid = b.getAttribute('data-testid') || '';
        var isSkip = (/skip/i.test(testid)) ||
                     (SKIPISH.test(label) && ADISH.test(label));
        if (!isSkip) continue;
        if (!b.offsetWidth && !b.offsetHeight) continue; // invisible
        b.click();
        log('clicked skip button');
        return true;
      }
    } catch (e) { /* best-effort */ }
    return false;
  }

  function playPauseButton() {
    try {
      return document.querySelector('button[data-testid="control-button-playpause"]');
    } catch (e) {
      return null;
    }
  }

  function togglePlay() {
    var b = playPauseButton();
    if (!b) return false;
    try { b.click(); return true; } catch (e) { return false; }
  }

  function playElement(r) {
    try {
      var result = r.play();
      if (result && typeof result.catch === 'function') result.catch(function () {});
    } catch (e) { /* autoplay policies; callers fall back to the button */ }
  }

  // ---- Recovery ---------------------------------------------------------------
  function lastReloadAt() {
    try {
      var v = parseInt(sessionStorage.getItem(RELOAD_KEY) || '0', 10);
      return isFinite(v) ? v : 0;
    } catch (e) {
      return 0;
    }
  }

  function reloadPage() {
    var now = Date.now();
    if (now - lastReloadAt() < RELOAD_COOLDOWN_MS) return false;
    try {
      sessionStorage.setItem(RELOAD_KEY, String(now));
      sessionStorage.setItem(RESUME_KEY, '1');
    } catch (e) { /* resume is best-effort; the reload still helps */ }
    log('player stuck, reloading page');
    try { location.reload(); } catch (e) { /* nothing else to try */ }
    return true;
  }

  // After an ad, if Spotify did not resume playback on its own, try in
  // order: click the play button, start the element directly, click the
  // play button once more. Capped so the guard can never rapid-fire the
  // player (a failure mode that turned older scripts into pause loops).
  function resumeAfterAd() {
    var tries = 0;
    var attempt = function () {
      tries++;
      if (adSignal() !== false) return; // a new ad started; its branch owns playback
      var r = raw(active);
      if (!r) return;
      try {
        if (r.paused && r.readyState >= 2) {
          if (tries === 1) {
            togglePlay();
          } else if (tries === 2) {
            playElement(r);
          } else {
            togglePlay();
          }
          log('post-ad resume attempt ' + tries);
        }
      } catch (e) { /* best-effort */ }
      if (tries < MAX_RESUME_TRIES) setTimeout(attempt, RESUME_SPACING_MS);
    };
    setTimeout(attempt, 800);
  }

  // ---- Main loop ---------------------------------------------------------------
  function tick() {
    tickCount++;
    scanMedia(tickCount % DEEP_SCAN_EVERY === 1);

    var signal = adSignal();
    var now = Date.now();

    if (signal) {
      cleanTicks = 0;
      if (!inAd) {
        inAd = true;
        adTicks = 0;
        adStart = now;
        adClears = 0;
        adNudges = 0;
        gaveUp = false;
        lastPosition = -1;
        lastProgressAt = now;
        lastNudgeAt = now;
        log('ad detected (' + signal + ' signal)');
      }
      adTicks++;

      silenceAll();
      var r = raw(active);
      if (r && signal === 'strong' && !gaveUp) fastForward(r);
      clickSkip();

      // Track whether ad audio is actually progressing.
      if (r) {
        var pos = -1;
        try { pos = r.currentTime || 0; } catch (e) { /* best-effort */ }
        if (pos !== lastPosition) {
          lastPosition = pos;
          lastProgressAt = now;
        }
      }

      // Over budget: stop intervening, keep muting until the ad is over.
      if (!gaveUp && (adTicks > MAX_TICKS_PER_AD || now - adStart > MAX_AD_MS)) {
        gaveUp = true;
        log('ad session over budget, mute-only mode');
      }

      // Stalled ad: nudge play/pause, then reload as a last resort.
      if (!gaveUp && now - lastProgressAt > AD_STALL_MS) {
        if (adNudges < MAX_NUDGES_PER_AD && now - lastNudgeAt > NUDGE_SPACING_MS) {
          if (togglePlay()) {
            adNudges++;
            lastNudgeAt = now;
            log('ad stalled, play/pause nudge ' + adNudges);
          }
        } else if (adNudges >= MAX_NUDGES_PER_AD) {
          reloadPage();
        }
      }
      // Even in mute-only mode, a permanently wedged ad slot gets the same
      // last-resort reload a user would otherwise perform by hand.
      // reloadPage() enforces its own cooldown, so this cannot loop.
      if (gaveUp && now - lastProgressAt > AD_STALL_MS * 3) {
        reloadPage();
      }
      return;
    }

    cleanTicks++;
    if (inAd && cleanTicks >= 2) {
      inAd = false;
      gaveUp = false;
      restoreAll();
      log('ad over, restoring audio');
      resumeAfterAd();
    }

    // Content watchdog: the stream itself can stall (e.g. after a blocked
    // ad limped through, or a dead connect socket). If the element claims
    // to be playing but time has not advanced, restart it a capped number
    // of times; reload as the last resort.
    var el = raw(active);
    if (!el) return;
    try {
      var playing = !el.paused && el.readyState >= 2;
      var position = el.currentTime || 0;
      if (position !== lastPosition) {
        lastPosition = position;
        lastProgressAt = now;
        contentNudges = 0;
      } else if (playing && now - lastProgressAt > CONTENT_STALL_MS) {
        if (contentNudges < MAX_CONTENT_NUDGES && now - lastNudgeAt > NUDGE_SPACING_MS) {
          contentNudges++;
          lastNudgeAt = now;
          playElement(el);
          if (el.paused) togglePlay();
          log('content stalled, nudging playback (' + contentNudges + ')');
        } else if (contentNudges >= MAX_CONTENT_NUDGES) {
          reloadPage();
        }
      }
    } catch (e) { /* watchdog is best-effort */ }
  }

  // ---- Post-reload resume -------------------------------------------------------
  // After our own automatic reload, try to start playback once the player
  // bar appears, so a screen-off session recovers without user action.
  function resumeAfterReload() {
    var flagged = false;
    try {
      flagged = sessionStorage.getItem(RESUME_KEY) === '1';
      sessionStorage.removeItem(RESUME_KEY);
    } catch (e) { return; }
    if (!flagged) return;
    var tries = 0;
    var attempt = function () {
      tries++;
      var b = playPauseButton();
      if (b) {
        try { b.click(); } catch (e) { /* best-effort */ }
        log('resumed playback after reload');
        return;
      }
      if (tries < 20) setTimeout(attempt, 1000);
    };
    setTimeout(attempt, 1500);
  }

  // ---- Lifecycle -----------------------------------------------------------------
  function start() {
    if (timer) return;
    known = new Set();
    priorAudio = new WeakMap();
    installCreateHook();
    lastProgressAt = Date.now();
    timer = setInterval(tick, TICK_MS);
    tick(); // run once immediately so mid-load ads are caught fast
    resumeAfterReload();
  }

  function stop() {
    if (timer) { clearInterval(timer); timer = null; }
    restoreAll(); // never leave the player muted behind
  }

  window.addEventListener('pagehide', stop);
  document.addEventListener('visibilitychange', function () {
    // Re-sync progress tracking when returning to the page so the watchdog
    // does not mistake a background pause for a stall.
    if (document.visibilityState === 'visible') {
      lastProgressAt = Date.now();
      tick();
    }
  });

  start();
})();
