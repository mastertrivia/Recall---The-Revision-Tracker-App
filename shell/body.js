/* ===========================================================================
   Recall × Android shell integration
   ---------------------------------------------------------------------------
   Everything here is additive: it never changes the revision logic, the
   scheduling rules or the stored data model of the Recall app. It adds the
   wrapper's capabilities (native backup/restore, HTML install, display size,
   notifications, sounds) and keeps the wrapper's native data store in sync
   with the HTML's own localStorage so a native export really contains the
   revision data.

   Division of labour:
     HTML  -> owns topics, subjects, notes, schedules, settings (localStorage)
     Native-> owns the backup files, HTML install/revert, alarms, notifications
     Sync  -> HTML mirrors its state into the native store on every change and
              pulls it back when a backup has been imported natively.
   =========================================================================== */
(function () {
  'use strict';

  var A = (typeof window !== 'undefined' && window.AndroidBridge) || null;
  var available = !!A;

  function call(fn) {
    if (!available) return null;
    try {
      var f = A[fn];
      if (typeof f !== 'function') { console.warn('[shell] missing bridge method: ' + fn); return null; }
      return f.apply(A, Array.prototype.slice.call(arguments, 1));
    } catch (e) {
      console.warn('[shell] ' + fn + ' failed', e);
      return null;
    }
  }

  // Native data keys <-> the HTML's localStorage keys.
  var NATIVE = { state: 'recall_state', shadow: 'recall_state_shadow', rescue: 'recall_state_rescue' };
  var LOCAL = {
    state: 'recall_topic_revision_studio_v1',
    shadow: 'recall_topic_revision_studio_v1_backup',
    rescue: 'recall_topic_revision_studio_v1_rescue'
  };
  // Feature flags live in the wrapper (survive an HTML update, cleared by a
  // factory reset - exactly like the rest of the user's data).
  var FLAG = {
    daily: 'recall_dailySummary',
    dailyTime: 'recall_dailySummaryTime',
    overdue: 'recall_overdueReminder',
    overdueTime: 'recall_overdueReminderTime',
    sound: 'recall_soundEnabled'
  };
  var ALARM_DAILY = 9101;      // distinct from every id the wrapper uses itself
  var ALARM_OVERDUE = 9102;

  function readLS(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function writeLS(key, value) { try { localStorage.setItem(key, value); return true; } catch (e) { return false; } }

  function flag(key, def) {
    var v = call('getFlag', key, def);
    return (v === null || v === undefined) ? def : !!v;
  }
  function setFlag(key, value) { call('setFlag', key, !!value); }
  // Times are strings ("06:00"): the boolean flag channel would coerce them
  // to true/false and the chosen hour would silently never take effect.
  function setTimeFlag(key, value) {
    if (typeof value !== 'string' || !/^\d{2}:\d{2}$/.test(value)) return false;
    return call('setStringFlag', key, value) === false ? false : true;
  }
  function flagTime(key, def) {
    var v = call('getStringFlag', key, '');
    return (typeof v === 'string' && /^\d{2}:\d{2}$/.test(v)) ? v : def;
  }

  /* ---------------------------------------------------------------- sync -- */

  // Push the HTML's own storage into the wrapper's data store so that the
  // native Export / Import / Factory-reset flows really cover revision data.
  // Never pushes an empty payload: a failed read must not erase good data.
  function mirrorNow() {
    if (!available) return false;
    var primary = readLS(LOCAL.state);
    if (!primary) return false;
    try {
      if (call('saveData', NATIVE.state, primary) === false) return false;
      var shadow = readLS(LOCAL.shadow);
      if (shadow) call('saveData', NATIVE.shadow, shadow);
      else call('deleteData', NATIVE.shadow);
      call('deleteData', NATIVE.rescue);
      var el = document.getElementById('shellBackupStatus');
      if (el) el.textContent = 'Mirrored to the Android wrapper ' + new Date().toLocaleTimeString() + '.';
      return true;
    } catch (e) { console.warn('[shell] mirror failed', e); return false; }
  }

  var mirrorTimer = null;
  function scheduleMirror(delay) {
    clearTimeout(mirrorTimer);
    mirrorTimer = setTimeout(function () { mirrorTimer = null; mirrorNow(); }, delay == null ? 1200 : delay);
  }

  // Pull the wrapper's store into localStorage after a native backup import.
  // The incoming payload is validated first; on any doubt the existing local
  // data is left exactly as it was.
  function restoreFromNative() {
    if (!available) return false;
    var remote = call('loadData', NATIVE.state);
    if (typeof remote !== 'string' || !remote) return false;
    var parsed;
    try { parsed = JSON.parse(remote); } catch (e) { return false; }
    if (!parsed || !Array.isArray(parsed.topics) || !Array.isArray(parsed.subjects)) return false;
    // A payload without a settings block would silently reset every setting
    // (exam dates, daily hours) back to its default - never adopt it.
    if (!parsed.settings || typeof parsed.settings !== 'object' || Array.isArray(parsed.settings)) return false;
    if (readLS(LOCAL.state) === remote) return false;
    try {
      if (!writeLS(LOCAL.state, remote)) return false;
      var shadow = call('loadData', NATIVE.shadow);
      if (typeof shadow === 'string' && shadow) writeLS(LOCAL.shadow, shadow);
      else { try { localStorage.removeItem(LOCAL.shadow); } catch (e) { } }
      try { localStorage.removeItem(LOCAL.rescue); } catch (e) { }
      return true;
    } catch (e) { return false; }
  }

  /* -------------------------------------------------------- notifications -- */

  function uniqueTopics(items) {
    var seen = new Map();
    items.forEach(function (x) {
      if (!x || !x.topic || x.topic.blocked) return;
      if (!seen.has(String(x.topic.id))) seen.set(String(x.topic.id), x.topic);
    });
    // Array.from: slice.call() on a Map iterator yields [] (no length), which
    // would make every reminder report zero topics.
    return Array.from(seen.values());
  }

  // Topics that need a revision today = everything pending up to and including
  // today, exactly as the app itself counts it (missed revisions are included
  // once per topic, so a topic that is both overdue and due today is never
  // double-counted). Blocked topics are archived and excluded.
  function topicsDueToday() {
    try { return uniqueTopics(getAllDueItems(todayISO())); } catch (e) { return []; }
  }

  // Missed revisions only - due strictly before today, still pending.
  function topicsOverdue() {
    var today = todayISO();
    try {
      return uniqueTopics(getAllDueItems(today).filter(function (x) { return x.revision.dueDate < today; }));
    } catch (e) { return []; }
  }

  function durationOf(list) {
    return list.reduce(function (s, t) { return s + (Math.max(0, Number(t.duration)) || 0); }, 0);
  }

  function nextAt(hhmm) {
    var parts = String(hhmm || '06:00').split(':');
    var h = Math.max(0, Math.min(23, parseInt(parts[0], 10) || 0));
    var m = Math.max(0, Math.min(59, parseInt(parts[1], 10) || 0));
    var d = new Date();
    d.setHours(h, m, 0, 0);
    if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1);
    return d.getTime();
  }

  var alarmTimer = null;
  function scheduleAlarmsNow() {
    if (!available) return;
    var due = topicsDueToday();
    if (flag(FLAG.daily, true) && due.length) {
      var mins = Math.round(durationOf(due));
      var text = 'You have ' + due.length + (due.length === 1 ? ' topic' : ' topics') +
        ' to revise today. Estimated revision time: ' + minsLong(mins) + '.';
      var over = topicsOverdue().length;
      if (over) text += ' ' + over + (over === 1 ? ' is' : ' are') + ' overdue from earlier.';
      call('scheduleAlarm', ALARM_DAILY, nextAt(flagTime(FLAG.dailyTime, '06:00')), 'Today\u2019s revisions', text);
    } else {
      call('cancelAlarm', ALARM_DAILY);
    }

    var overdue = topicsOverdue();
    if (flag(FLAG.overdue, false) && overdue.length) {
      call('scheduleAlarm', ALARM_OVERDUE, nextAt(flagTime(FLAG.overdueTime, '20:00')),
        'Overdue revisions',
        overdue.length + (overdue.length === 1 ? ' topic is' : ' topics are') +
        ' past their revision date: ' + overdue.slice(0, 5).map(function (t) { return t.name; }).join(', ') +
        (overdue.length > 5 ? ' \u2026' : ''));
    } else {
      call('cancelAlarm', ALARM_OVERDUE);
    }
  }

  function scheduleAlarms(delay) {
    clearTimeout(alarmTimer);
    alarmTimer = setTimeout(function () { alarmTimer = null; scheduleAlarmsNow(); }, delay == null ? 800 : delay);
  }

  /* ----------------------------------------------------------- completion -- */

  function completedCount() {
    var n = 0;
    try {
      state.topics.forEach(function (t) {
        (t.revisions || []).forEach(function (r) { if (r.completedAt) n++; });
      });
    } catch (e) { }
    return n;
  }

  function soundOn() { return flag(FLAG.sound, true); }

  // Wrap the app's existing completion actions without touching their bodies:
  // the sound only plays when a revision actually became completed.
  function hookCompletions() {
    ['completeOpenTopic', 'completeRevisionDirect', 'completeOverdueRevision'].forEach(function (name) {
      var original = window[name];
      if (typeof original !== 'function') return;
      window[name] = function () {
        var before = completedCount();
        var out = original.apply(this, arguments);
        try {
          if (soundOn() && completedCount() > before) call('playCompletionSound');
        } catch (e) { }
        return out;
      };
    });
  }

  // Every save refreshes the native mirror and the scheduled reminders.
  function hookPersist() {
    var original = window.persist;
    if (typeof original !== 'function') return;
    window.persist = function () {
      var out = original.apply(this, arguments);
      try { scheduleMirror(); scheduleAlarms(2500); } catch (e) { }
      return out;
    };
  }

  /* --------------------------------------------------- native event entry -- */

  window.onAndroidEvent = function (name, payload) {
    try {
      if (name === 'backupRestored') {
        // A native import just replaced the wrapper's store: adopt it.
        if (restoreFromNative()) location.reload();
        return;
      }
      if (name === 'notificationPermission') { renderNotificationStatus(); return; }
    } catch (e) { console.warn('[shell] event ' + name + ' failed', e); }
  };

  function drainPending() {
    var raw = call('getPendingEvents');
    if (typeof raw !== 'string' || !raw) return [];
    try { var list = JSON.parse(raw); return Array.isArray(list) ? list : []; } catch (e) { return []; }
  }

  /* --------------------------------------------------------- settings UI -- */

  function populateTimeSelect(id, selected) {
    var el = document.getElementById(id);
    if (!el) return;
    if (!el.options.length) {
      var html = '';
      for (var h = 0; h < 24; h++) {
        html += '<option value="' + String(h).padStart(2, '0') + ':00">' +
          String(h).padStart(2, '0') + ':00</option>';
      }
      el.innerHTML = html;
    }
    el.value = selected;
  }

  function renderDisplayLevels() {
    var host = document.getElementById('displaySizeLevels');
    if (!host || !window.recallDisplaySize) return;
    var current = window.recallDisplaySize.get();
    var html = '';
    for (var i = 1; i <= 9; i++) {
      html += '<button type="button" data-level="' + i + '" class="' +
        (String(i) === current ? 'is-active ' : '') + (i === 5 ? 'is-base' : '') +
        '" aria-pressed="' + (String(i) === current) + '"' +
        (i === 5 ? ' title="Normal size (100%)"' : '') + '>' + i + '</button>';
    }
    host.innerHTML = html;
    host.querySelectorAll('button').forEach(function (b) {
      b.addEventListener('click', function () {
        window.recallDisplaySize.set(b.getAttribute('data-level'));
        renderDisplayLevels();
        renderDisplayHint();
      });
    });
    renderDisplayHint();
  }

  function renderDisplayHint() {
    var el = document.getElementById('displaySizeHint');
    if (!el || !window.recallDisplaySize) return;
    var level = window.recallDisplaySize.get();
    var pct = Math.round(window.recallDisplaySize.scaleOf(level) * 100);
    var label = level === '5' ? 'Normal size \u2014 exactly the original Recall interface'
      : (parseInt(level, 10) < 5 ? 'Smaller than normal' : 'Larger than normal');
    el.textContent = 'Level ' + level + ': ' + label + ' (' + pct + '% of normal).';
  }

  function setSwitch(id, on) {
    var el = document.getElementById(id);
    if (el) el.classList.toggle('on', !!on);
  }

  function renderNotificationStatus() {
    var el = document.getElementById('notificationPermissionStatus');
    if (!el) return;
    if (!available) { el.textContent = 'Android wrapper not detected \u2014 notifications are unavailable in a plain browser.'; return; }
    var d = call('getDiagnostics');
    var info = null;
    try { info = typeof d === 'string' ? JSON.parse(d) : d; } catch (e) { }
    if (!info) { el.textContent = 'Notification permission: status unavailable.'; return; }
    el.textContent = 'Permission: ' + (info.notificationsAllowed ? 'granted' : 'NOT granted \u2014 tap any notification control to allow it') +
      ' \u00b7 Exact alarms: ' + (info.exactAlarmsAllowed ? 'allowed' : 'system limited (reminders may be delayed)') + '.';
  }

  function renderBridgeStatus() {
    var el = document.getElementById('shellBridgeStatus');
    if (!el) return;
    if (!available) { el.textContent = 'Running in a browser \u2014 Android features are disabled.'; return; }
    var d = call('getDiagnostics');
    var info = null;
    try { info = typeof d === 'string' ? JSON.parse(d) : d; } catch (e) { }
    el.textContent = info
      ? 'Wrapper connected \u00b7 shell ' + (info.shellVersion || '?') + ' \u00b7 Android ' + (info.androidRelease || '?')
      : 'Wrapper connected.';
  }

  function renderSettingsState() {
    populateTimeSelect('dailySummaryTime', flagTime(FLAG.dailyTime, '06:00'));
    populateTimeSelect('overdueReminderTime', flagTime(FLAG.overdueTime, '20:00'));
    setSwitch('dailySummarySwitch', flag(FLAG.daily, true));
    setSwitch('overdueReminderSwitch', flag(FLAG.overdue, false));
    setSwitch('soundSwitch', flag(FLAG.sound, true));
    renderDisplayLevels();
    renderBridgeStatus();
    renderNotificationStatus();
  }

  /* Public surface used by the injected Settings markup. */
  window.shell = {
    available: available,

    refreshApp: function () { call('refreshApp'); },
    installHtml: function () { call('importHtmlFile'); },
    saveHtml: function () { call('exportCurrentHtml'); },
    revertHtml: function () { call('revertHtmlFile'); },
    factoryReset: function () { call('factoryResetUserData'); },
    systemCheck: function () { call('systemCheck'); },

    exportData: function () {
      // Mirror first so the file the user is about to save contains the very
      // latest revision data, then hand over to the native export flow.
      if (!mirrorNow()) { toast('Nothing to export yet \u2014 use the app once, then export.', 'error'); return; }
      call('exportBackup');
    },
    importData: function () { call('importBackup'); },

    toggleDailySummary: function () {
      var next = !flag(FLAG.daily, true);
      setFlag(FLAG.daily, next);
      setSwitch('dailySummarySwitch', next);
      scheduleAlarms(50);
    },
    setDailySummaryTime: function (v) { setTimeFlag(FLAG.dailyTime, v); scheduleAlarms(50); },
    toggleOverdueReminder: function () {
      var next = !flag(FLAG.overdue, false);
      setFlag(FLAG.overdue, next);
      setSwitch('overdueReminderSwitch', next);
      scheduleAlarms(50);
    },
    setOverdueReminderTime: function (v) { setTimeFlag(FLAG.overdueTime, v); scheduleAlarms(50); },
    toggleSound: function () {
      var next = !flag(FLAG.sound, true);
      setFlag(FLAG.sound, next);
      setSwitch('soundSwitch', next);
      if (next) call('playCompletionSound');
    },

    // Introspection used by the diagnostics screen and by tests.
    mirror: mirrorNow,
    restore: restoreFromNative,
    reschedule: scheduleAlarmsNow,
    dueToday: topicsDueToday,
    overdue: topicsOverdue
  };

  /* ------------------------------------------------------------- startup -- */

  function boot() {
    renderSettingsState();
    hookPersist();
    hookCompletions();

    if (!available) return;

    // Ask once (Android 13+); the answer arrives as a native event.
    call('requestNotificationPermission');

    // A native backup import wins over whatever is in localStorage right now;
    // otherwise this session's state is pushed out to the native store.
    var events = drainPending();
    var restored = events.some(function (e) { return e && e.name === 'backupRestored'; });
    if (restored && restoreFromNative()) {
      location.reload();
      return;
    }
    mirrorNow();
    scheduleAlarms(400);

    events.forEach(function (e) {
      try {
        window.onAndroidEvent(e.name, typeof e.payload === 'string' ? JSON.parse(e.payload) : e.payload);
      } catch (err) { }
    });

    // Coming back to the app is the natural moment to refresh today's summary.
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') { mirrorNow(); scheduleAlarms(400); }
    });
    window.addEventListener('pagehide', function () { mirrorNow(); });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();