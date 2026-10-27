/* ===== Recall Display Size engine — app-owned visual scale ================
   Nine levels, Level 5 = TRUE BASELINE (renders exactly the stock viewport
   meta, byte-for-byte, i.e. 100% of the original Recall HTML), levels 1-4
   progressively smaller, levels 6-9 progressively larger, 8% per step:

       1     2     3     4     5      6     7     8     9
     0.735 0.794 0.857 0.926 1.000  1.080 1.166 1.260 1.360

   Why a viewport rewrite instead of WebView zoom: Android WebView seeds
   textZoom from the system font-size setting and renders CSS px through the
   system display density, so the app would otherwise be restyled by Android's
   own settings. The wrapper pins textZoom=100 and this engine rewrites the
   viewport meta BEFORE first paint: the layout viewport becomes
   round(deviceWidth / scale) CSS px with initial-scale = scale, so the whole
   UI renders `scale` times larger while the responsive layout reflows to the
   narrower logical width - exactly like browser zoom, with no horizontal
   overflow and working media queries.

   The chosen level is persisted by the Android wrapper (getDisplayScale /
   setDisplayScale) so it survives HTML reloads and app restarts; a plain
   browser (no bridge) falls back to localStorage. */
(function () {
  var RECALL_DEFAULT_LEVEL = '5';   // the true normal size
  var RECALL_LEVEL_STEP = 1.08;     // visual distance between two neighbours
  var RECALL_BASE_LEVEL = 5;        // level whose scale is exactly 1.0
  var META_DEFAULT = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';
  var LS_KEY = 'recall_displaySize';
  var current = RECALL_DEFAULT_LEVEL;

  function normalizeLevel(v) {
    v = String(v == null ? '' : v).trim();
    var n = parseInt(v, 10);
    return (String(n) === v && n >= 1 && n <= 9) ? String(n) : null;
  }

  function scaleOf(level) {
    var n = parseInt(level, 10);
    if (!isFinite(n)) n = RECALL_BASE_LEVEL;
    return Math.pow(RECALL_LEVEL_STEP, n - RECALL_BASE_LEVEL);
  }

  function bridgeLevel() {
    try {
      var a = window.AndroidBridge;
      if (a && typeof a.getDisplayScale === 'function') return normalizeLevel(a.getDisplayScale());
    } catch (e) { }
    return null;
  }

  function localLevel() {
    try { return normalizeLevel(localStorage.getItem(LS_KEY)); } catch (e) { return null; }
  }

  function readLevel() { return bridgeLevel() || localLevel() || RECALL_DEFAULT_LEVEL; }

  // Real screen width in CSS px at the CURRENT orientation + system density.
  function deviceCssWidth() {
    try { if (window.screen && window.screen.width) return window.screen.width; } catch (e) { }
    try {
      if (window.visualViewport && window.visualViewport.width) {
        return window.visualViewport.width * (window.visualViewport.scale || 1);
      }
    } catch (e) { }
    return window.innerWidth || 360;
  }

  function apply(level) {
    level = normalizeLevel(level) || RECALL_DEFAULT_LEVEL;
    current = level;
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) return;
    var s = scaleOf(level);
    var content = s === 1 ? META_DEFAULT :
      'width=' + Math.max(280, Math.round(deviceCssWidth() / s)) +
      ', initial-scale=' + s + ', maximum-scale=' + s +
      ', user-scalable=no, viewport-fit=cover';
    if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
  }

  window.recallDisplaySize = {
    levels: 9,
    defaultLevel: RECALL_DEFAULT_LEVEL,
    baseLevel: RECALL_BASE_LEVEL,
    get: function () { return current; },
    scaleOf: scaleOf,
    isNative: function () {
      try { var a = window.AndroidBridge; return !!(a && typeof a.getDisplayScale === 'function'); } catch (e) { return false; }
    },
    set: function (level) {
      level = normalizeLevel(level) || RECALL_DEFAULT_LEVEL;
      try { localStorage.setItem(LS_KEY, level); } catch (e) { }
      try {
        var a = window.AndroidBridge;
        if (a && typeof a.setDisplayScale === 'function') a.setDisplayScale(level);
      } catch (e) { }
      apply(level);
      return level;
    }
  };

  apply(readLevel());
  // Rotation changes the real device width; recompute the layout width for the
  // current level so the biggest sizes never overflow in landscape either.
  var rzT = null;
  window.addEventListener('resize', function () {
    clearTimeout(rzT);
    rzT = setTimeout(function () { apply(current); }, 150);
  });
})();