# HTML_INTEGRATION_GUIDE.md — Connecting Your Existing HTML App

> Goal: wire your existing Goal Tracker HTML into the Android shell **without
> redesigning your HTML**. You add small calls at existing event points.
> Every API named here is verified against the Kotlin source (see BRIDGE_API.md).

---

## 0. The one honest caveat before you start

There is **no `GoalTracker` object and no `updateActivity()` / `getCapabilities()`
method** in this shell. The real global is `window.AndroidBridge`. This guide
uses a thin helper you paste once, so your call sites stay short and readable.

---

## 1. Paste this helper once (near the top of your app JS)

This wrapper is optional sugar over the real `window.AndroidBridge`. It makes
every call safe in a normal browser (where the bridge is absent) and gives you
tidy call sites. **It only calls verified bridge methods.**

```js
// ---- Goal Tracker shell helper (safe in browser AND in the app) ----
const Shell = (function () {
  const A = (typeof window !== 'undefined' && window.AndroidBridge) || null;
  const has = !!A;
  const call = (fn, ...args) => { try { return has ? A[fn](...args) : null; } catch (e) { return null; } };
  return {
    available: has,

    // timer notification
    startTimer: (title, text) => call('startTimer', title, text),
    updateTimer: (title, text) => call('updateTimer', title, text),
    pauseTimer: (title, text) => call('pauseTimer', title, text),
    stopTimer: () => call('stopTimer'),

    // one-off + completion notifications
    notify: (id, title, text) => call('showNotification', id, title, text),
    requestNotifPermission: () => call('requestNotificationPermission'),

    // alarms
    scheduleAlarm: (id, atMs, title, text) => call('scheduleAlarm', id, atMs, title, text),
    cancelAlarm: (id) => call('cancelAlarm', id),

    // storage (source of truth)
    save: (key, obj) => call('saveData', key, JSON.stringify(obj)),
    load: (key, fallback) => { const s = call('loadData', key); return s ? JSON.parse(s) : fallback; },

    // toast
    toast: (m) => call('showToast', m),

    // sound
    playDone: () => call('playCompletionSound'),

    // time safety
    timeInfo: () => { const s = call('currentTimeInfo'); return s ? JSON.parse(s) : null; },

    // diagnostics / capabilities
    diagnostics: () => { const s = call('getDiagnostics'); return s ? JSON.parse(s) : null; },

    // pending events
    drainPending: () => { const s = call('getPendingEvents'); return s ? JSON.parse(s) : []; },
  };
})();
```

---

## 2. Where to connect (existing event → what to add)

Keep your existing functions exactly as they are. Add ONE line at each point.

### Timer START event
```js
function startTimer() {
  // ... your existing code ...
+ Shell.startTimer(currentActivityName, formatRemaining(secondsLeft));
}
```

### Timer TICK/update (wherever you already update the countdown each second)
```js
function onTick() {
  // ... your existing countdown update ...
+ Shell.updateTimer(currentActivityName, formatRemaining(secondsLeft));
}
```

### Timer PAUSE event
```js
function pauseTimer() {
  // ... your existing code ...
+ Shell.pauseTimer(currentActivityName, "Paused at " + formatRemaining(secondsLeft));
}
```

### Timer RESUME event
```js
function resumeTimer() {
  // ... your existing code ...
+ Shell.updateTimer(currentActivityName, formatRemaining(secondsLeft)); // Resume just resumes normal updates
}
```

### Timer STOP event
```js
function stopTimer() {
  // ... your existing code ...
+ Shell.stopTimer();
}
```

### Activity CHANGE event (there is no updateActivity() — use updateTimer + save)
"Activity" is an HTML concept. Reflect it in the notification and persist it:
```js
function setActivity(name) {
  currentActivityName = name;
  // ... your existing code ...
+ if (timerRunning) Shell.updateTimer(name, formatRemaining(secondsLeft));
+ Shell.save("currentActivity", { name, at: Date.now() });
}
```

### Subject CHANGE event
Subject is also HTML-side. Put it in the notification text and persist it:
```js
function setSubject(subject) {
  currentSubject = subject;
  // ... your existing code ...
+ if (timerRunning) Shell.updateTimer(currentActivityName, subject + " • " + formatRemaining(secondsLeft));
+ Shell.save("currentSubject", { subject, at: Date.now() });
}
```

### Task COMPLETION event
```js
function completeTask(task) {
  // ... your existing code ...
+ Shell.playDone();                                   // optional custom sound (falls back silently)
+ Shell.notify(1, "Task complete", task.title);       // notification
+ Shell.toast("Task complete");
}
```

### Goal COMPLETION event
```js
function completeGoal(goal) {
  // ... your existing code ...
+ Shell.playDone();
+ Shell.notify(2, "Goal complete!", goal.title);
}
```

### "Mode" / "Status" changes
There is no native `mode`/`status` API. These are just parts of the
notification text you pass to `updateTimer`, plus data you `Shell.save(...)`.
Example:
```js
Shell.updateTimer(activityName, `${mode} • ${status} • ${formatRemaining(secondsLeft)}`);
```

---

## 3. Required startup wiring (do this once, on load)

```js
window.addEventListener('load', () => {
  if (!Shell.available) return; // running in a plain browser; skip native wiring

  // (a) ask for notification permission once (Android 13+)
  Shell.requestNotifPermission();

  // (b) drain any events that arrived while the app was killed
  Shell.drainPending().forEach(ev => {
    window.onAndroidEvent(ev.name, JSON.parse(ev.payload));
  });

  // (c) restore your data from the source of truth
  const goals = Shell.load("goals", []);
  // ... hydrate your UI from `goals` ...
});
```

---

## 4. Handling native events (notification buttons, backup, permission)

Define this once. It is how notification buttons reach your app.

```js
window.onAndroidEvent = function (name, payload) {
  switch (name) {
    case 'timerAction':
      if (payload === 'pause')  pauseTimer();
      if (payload === 'resume') resumeTimer();
      if (payload === 'stop')   stopTimer();
      break;
    case 'backupRestored':
      location.reload();       // re-read restored data
      break;
    case 'notificationPermission':
      console.log('notif permission:', payload); // "granted" | "denied"
      break;
  }
};
```

---

## 5. Time-safe sessions (prevents fake elapsed time)

Replace `Date.now()`-based elapsed calculations for a running session with the
monotonic clock. This is the whole point of the time-safety system.

```js
let sessionStart = null;

function beginSession() {
  sessionStart = Shell.timeInfo(); // {wallMs, monoMs, bootId} or null in browser
}

function elapsedMs() {
  if (!sessionStart) return 0;
  const now = Shell.timeInfo();
  if (!now) return 0;
  if (now.bootId === sessionStart.bootId) {
    return now.monoMs - sessionStart.monoMs;          // tamper-proof
  }
  return Math.max(0, now.wallMs - sessionStart.wallMs); // rebooted: clamped fallback
}
```
Persist `sessionStart` via `Shell.save("activeSession", sessionStart)` so a
session survives the app being killed, and restore it on load.

---

## 6. Rules that keep updates rebuild-free

- Only call verified `AndroidBridge` methods (see BRIDGE_API.md §12). Calling a
  non-existent method just returns `null` via the helper — but don't rely on
  imaginary features.
- All app logic stays in HTML. The shell never needs a rebuild for HTML/logic
  changes — only for brand-new native capabilities.
- Always `Shell.save(...)` important state; treat IndexedDB as a cache only.
- Never assume the bridge exists — guard with `Shell.available` (the helper
  already does this for you).
