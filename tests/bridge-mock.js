'use strict';
/**
 * Recording mock of window.AndroidBridge — lets the app's real Android
 * integration layer (andInit observer, notification state machine, alarm sync,
 * pending-event drain, backup mirror) run under test, with every bridge call
 * recorded instead of executed.
 *
 * storage/flags objects are shared across page reloads by the caller so a
 * "WebView reload" test behaves like the real device.
 */
function makeBridge(win, opts = {}) {
  const calls = [];
  const storage = opts.storage || {};
  const flags = opts.flags || {};
  const stringFlags = opts.stringFlags || {};
  const rec = (name, args) => calls.push([name, ...args]);
  const now = () => Date.now();

  // Display scale (v4.12): mirrors native JsBridge exactly. The store is
  // { value, scheme }, shared across page reloads by the caller
  // (opts.displayScale), so a "WebView reload" test behaves like the device.
  // A store WITHOUT scheme === '2' holds a v4.11 1–10 level (or a 4-size
  // name) and is migrated once at read (+2, clamp 9) — same as native.
  const DISPLAY_SCALE_LEVELS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
  const DISPLAY_SCALE_DEFAULT = '5';
  const DISPLAY_SCALE_SCHEME = '2';
  const DISPLAY_SCALE_LEGACY = { xsmall: '4', small: '6', medium: '7', large: '8' };
  const displayScale = opts.displayScale || { value: opts.initialDisplayScale || DISPLAY_SCALE_DEFAULT, scheme: DISPLAY_SCALE_SCHEME };
  const normalizeLevel = (v) => {
    const s = String(v == null ? '' : v).trim();
    return DISPLAY_SCALE_LEVELS.includes(s) ? s : null;
  };
  const migrateDisplayScale = () => {
    if (displayScale.scheme === DISPLAY_SCALE_SCHEME) return;
    const stored = String(displayScale.value == null ? '' : displayScale.value).trim();
    let raw = stored.toLowerCase();
    if (DISPLAY_SCALE_LEGACY[raw]) raw = DISPLAY_SCALE_LEGACY[raw];
    const n = parseInt(raw, 10);
    const ok = String(n) === raw && n >= 1 && n <= 10;
    if (stored !== '') displayScale.value = ok ? String(Math.min(9, n + 2)) : DISPLAY_SCALE_DEFAULT;
    displayScale.scheme = DISPLAY_SCALE_SCHEME;
  };

  const bridge = {
    __calls: calls,
    __flags: flags,
    __stringFlags: stringFlags,
    __storage: storage,

    getShellVersion: () => '4.12',
    getFlag: (k, d) => (Object.prototype.hasOwnProperty.call(flags, k) ? flags[k] : d),
    setFlag: (k, v) => { flags[k] = v; rec('setFlag', [k, v]); return true; },
    getAllFlags: () => JSON.stringify(flags),
    // String channel (v4.13): reminder times must not be coerced to Boolean.
    getStringFlag: (k, d) => (Object.prototype.hasOwnProperty.call(stringFlags, k) ? stringFlags[k] : d),
    setStringFlag: (k, v) => { stringFlags[k] = String(v); rec('setStringFlag', [k, String(v)]); return true; },

    // Mirrors native JsBridge: level whitelist in, default out, unknown rejected.
    getDisplayScale: () => { migrateDisplayScale(); return normalizeLevel(displayScale.value) || DISPLAY_SCALE_DEFAULT; },
    setDisplayScale: (v) => {
      const s = normalizeLevel(v);
      if (s) { displayScale.value = s; displayScale.scheme = DISPLAY_SCALE_SCHEME; rec('setDisplayScale', [s]); }
      else rec('setDisplayScale.rejected', [String(v)]);
    },

    requestNotificationPermission: () => rec('requestNotificationPermission', []),

    showNotification: (id, title, text) => rec('showNotification', [id, title, text]),
    openExternalUrl: (url) => {
      rec('openExternalUrl', [url]);
      return true;
    },
    showPersistentNotification: (id, title, text) => rec('showPersistentNotification', [id, title, text]),
    showReviewNotification: (id, title, text) => rec('showReviewNotification', [id, title, text]),
    cancelNotification: (id) => rec('cancelNotification', [id]),

    scheduleAlarm: (id, triggerAtMillis, title, text) => rec('scheduleAlarm', [id, triggerAtMillis, title, text]),
    scheduleAlarmWithSound: (id, triggerAtMillis, title, text, soundSlot, durationMs) =>
      rec('scheduleAlarmWithSound', [id, triggerAtMillis, title, text, soundSlot, durationMs]),
    cancelAlarm: (id) => rec('cancelAlarm', [id]),
    cancelAllAlarms: () => rec('cancelAllAlarms', []),

    startTimer: (title, text) => rec('startTimer', [title, text]),
    updateTimer: (title, text) => rec('updateTimer', [title, text]),
    pauseTimer: (title, text) => rec('pauseTimer', [title, text]),
    stopTimer: () => rec('stopTimer', []),
    setTimerState: (json) => rec('setTimerState', [json]),

    // Time safety: pair wall+mono so monotonicNow() anchors exactly like on-device.
    currentTimeInfo: () => JSON.stringify({ wallMs: now(), monoMs: win.performance.now(), bootId: 'test-boot' }),
    elapsedRealtime: () => win.performance.now(),

    getPendingEvents: () => '[]',
    saveData: (key, json) => { storage[key] = json; rec('saveData', [key]); return true; },
    loadData: (key) => (Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : ''),
    deleteData: (key) => { delete storage[key]; return true; },
    listDataKeys: () => JSON.stringify(Object.keys(storage)),

    // v5.0: both action effects record so the suite can assert WHICH sound
    // fired (completed vs overdue) and how many times.
    playCompletionSound: () => rec('playCompletionSound', []),
    playOverdueSound: () => rec('playOverdueSound', []),
    stopCompletionSound: () => true,
    playNamedSound: () => true,
    playNamedSoundForDuration: () => true,
    stopAlarmPlayback: () => true,
    stopSound: () => true,

    showToast: (m) => rec('showToast', [m]),
    showToastLong: (m) => rec('showToastLong', [m]),

    getErrorLog: () => '[]',
    clearErrorLog: () => true,
    getDiagnostics: () => JSON.stringify({ shellVersion: '4.12', notificationsAllowed: true, exactAlarmsAllowed: true, displayScale: (migrateDisplayScale(), normalizeLevel(displayScale.value) || DISPLAY_SCALE_DEFAULT), useWideViewPort: true, loadWithOverviewMode: true, textZoom: 100, fontScale: 1 }),

    exportBackup: () => rec('exportBackup', []),
    importBackup: () => rec('importBackup', []),
    // v4.11 App & Data menu (moved out of the removed top-bar menu)
    refreshApp: () => rec('refreshApp', []),
    importHtmlFile: () => rec('importHtmlFile', []),
    revertHtmlFile: () => rec('revertHtmlFile', []),
    exportCurrentHtml: () => rec('exportCurrentHtml', []),
    factoryResetUserData: () => rec('factoryResetUserData', []),
    systemCheck: () => rec('systemCheck', []),
  };
  return bridge;
}

function findBy(calls, name, predicate) {
  return calls.filter((c) => c[0] === name && (!predicate || predicate(c)));
}
function countBy(calls, name, predicate) {
  return findBy(calls, name, predicate).length;
}
function lastBy(calls, name) {
  const rows = calls.filter((c) => c[0] === name);
  return rows.length ? rows[rows.length - 1] : null;
}

module.exports = { makeBridge, findBy, countBy, lastBy };
