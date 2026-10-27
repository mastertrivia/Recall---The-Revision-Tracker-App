# CAPABILITY_LIST.md — Native Features (Shell v2.1)

> Verified against the actual Kotlin source. Each capability lists the real
> bridge method(s) that provide it. There is **no `getCapabilities()` method**
> in this shell — capability detection is done via `getDiagnostics()` and
> feature flags, shown at the bottom.

---

## Capability matrix

| Capability | Status | Provided by (verified methods) |
|---|---|---|
| Toast messages | Available | `showToast`, `showToastLong` |
| Foreground timer notification | Available | `startTimer`, `updateTimer`, `pauseTimer`, `stopTimer` |
| Timer notification buttons (Pause/Resume/Stop) | Available | Buttons built in `TimerService`; taps arrive as `timerAction` event |
| One-off / completion notification | Available | `showNotification` |
| Notification permission (Android 13+) | Available | `requestNotificationPermission` (result via `notificationPermission` event) |
| Scheduled alarms (fire when app closed) | Available | `scheduleAlarm`, `cancelAlarm` |
| Alarm survives reboot | Available | Handled natively (BootReceiver re-schedules) |
| Alarm sound + vibration + Dismiss | Available | Dedicated alarm channel in `AlarmReceiver` |
| Persistent storage (JSON, internal) | Available | `saveData`, `loadData`, `deleteData`, `listDataKeys` |
| Full backup export / import | Available | `exportBackup`, `importBackup` (+ `backupRestored` event) |
| App & Data menu (top-bar replacement) | Available (v4.11) | `refreshApp`, `importHtmlFile`, `exportCurrentHtml`, `factoryResetUserData`, `systemCheck` |
| Time safety (monotonic clock, reboot detect) | Available | `elapsedRealtime`, `currentTimeInfo` |
| Feature flags (HTML settings) | Available | `getFlag`, `setFlag`, `getAllFlags` |
| Optional custom completion sound (≤ 1 MB) | Available | `saveSoundFile`, `playCompletionSound`, `stopCompletionSound`, `clearSoundFile` |
| Missed-event queue | Available | `getPendingEvents` |
| Diagnostics / system info | Available | `getDiagnostics`, `getErrorLog`, `clearErrorLog`, `getShellVersion` |

### Explicitly NOT available (by design — do not assume these exist)
| Not available | Why / alternative |
|---|---|
| `getCapabilities()` method | Use `getDiagnostics()` + flags (below) |
| `updateActivity()` method | Use `updateTimer(...)` + `saveData(...)` |
| Alarm snooze | Rejected for stability; only Dismiss exists |
| Custom ringtone picker | Rejected; one optional sound file only |
| Floating overlay / bubble | Rejected (permission + OEM instability) |
| Background watchdog / keep-alive | Rejected (battery + OS conflicts) |
| Home-screen widgets | Not implemented |

---

## How HTML checks capabilities

### 1. Is the native bridge present at all?
```js
const hasNative = !!(window.AndroidBridge);
```
In a normal browser this is `false` — degrade gracefully (web-only mode).

### 2. Read the live system state (the real "capabilities" call)
```js
const A = window.AndroidBridge;
const d = A ? JSON.parse(A.getDiagnostics()) : null;

// d contains these verified fields:
// {
//   shellVersion, androidRelease, androidSdk, device,
//   notificationsAllowed,   // boolean: can we post notifications?
//   exactAlarmsAllowed,     // boolean: are exact alarms permitted?
//   dataKeyCount, errorLogCount, customSoundSet, bootId,
//   flags: { notificationsEnabled, alarmsEnabled, timerNotificationEnabled, soundEnabled }
// }

if (d && !d.notificationsAllowed) {
  A.requestNotificationPermission();
}
if (d && !d.exactAlarmsAllowed) {
  A.showToast("Exact alarms are restricted; reminders may be delayed.");
}
```

### 3. Check / toggle feature flags (user settings)
```js
const A = window.AndroidBridge;
const flags = A ? JSON.parse(A.getAllFlags()) : {};
// Known keys (all default true when unset):
//   notificationsEnabled, alarmsEnabled, timerNotificationEnabled, soundEnabled

// read one (with default):
const soundOn = A ? A.getFlag("soundEnabled", true) : false;

// toggle from your settings UI:
if (A) A.setFlag("soundEnabled", false);
```

### 4. Optional: a small helper that mimics a "capabilities" object
You can build this yourself in HTML from the verified data above — no native
method is needed:
```js
function getCapabilities() {
  const A = window.AndroidBridge;
  if (!A) return { native: false };
  const d = JSON.parse(A.getDiagnostics());
  return {
    native: true,
    notifications: d.notificationsAllowed && d.flags.notificationsEnabled,
    exactAlarms: d.exactAlarmsAllowed && d.flags.alarmsEnabled,
    timerNotification: d.flags.timerNotificationEnabled,
    customSound: d.customSoundSet && d.flags.soundEnabled,
    storage: true,
    backup: true,
    timeSafety: true,
    shellVersion: d.shellVersion,
  };
}
```
This is **HTML-side** convenience only; it calls the real `getDiagnostics()` /
`getAllFlags()` under the hood.
