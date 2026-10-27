# C. Change Summary

All application changes are confined to **one file**. No Kotlin, manifest, build, or resource
file was modified. No persisted field, bridge method name, API, toast text, notification
channel, or data structure was changed.

## Modified

### `app/src/main/assets/app/index.html` — +89 / −13 lines, 18 hunks
| Finding | Hunks (post-fix lines) | Why |
|---|---|---|
| GT-01 | L3561–3571, L5647–5650 | Boundary helpers fall back to `RECOVERY_SETTINGS` during recovery so wake/day/sleep math uses the user's persisted hours, not `DEFAULT_SETTINGS` |
| GT-02 | L13144–13147, L15308–15309 | Module-level `renderPlanners()` removed; `boot()` calls it after `STATE` exists |
| GT-03 | L6046, L6050–6058 | After a synthetic Study completion in recovery, re-run the idempotent reconcile so `hourStreakSec`/`dailyStudySeconds` bank the completed session in that same pass |
| GT-04 | L6435–6442 (early-complete), L6468–6485 (`stopSession`) | Timeline row = current segment only; no row when stopping while paused; whole-session value still banked to streak/daily totals (rule unchanged) |
| GT-05 | L14413–14415, L14616, L14619–14626 | `andAlarmsCleared` marker stops per-second idle `cancelAlarm` bridge churn; one native cancel per reload is preserved |
| GT-06 | L5443–5452 (delegate guard), L5484, L5996–6004, L6964 | `armBomb` gated by Bomb-Mode-ON (mirrors `armForNewDay`); off ⇒ app goes idle with anchored `_idleStartTs`; recovery clears legacy phantom bombs |
| GT-07 | L6501–6505 | Stop-time `_idleStartTs` written to the persisted `state` object, not the discarded pre-save cache |

## Added (test infrastructure, not shipped in the APK)
- `tests/harness.js` — jsdom + fake-clock + fake-IndexedDB harness running the **real**
  `index.html`, with IDB connection lifecycle management for isolation between tests.
- `tests/timer.test.js` — 106-check timer/recovery/bomb/streak regression suite (T1–T17).
- `tests/notification.test.js` — 23-check notification/bridge lifecycle suite (N1–N7) against
  `tests/bridge-mock.js` (recording mock of the Android bridge).
- `tests/smoke.js` — boot/diagnostic smoke check.
- `tests/debug1.js`, `tests/debug2.js` — root-cause scratch repros for GT-01 and GT-07.
- `package.json`, `package-lock.json` — pinned dev deps (`jsdom`, `fake-indexeddb`) for the
  tests; do not affect the Android build (assets are packaged from `app/src/main/assets`).

## Behavior preserved (explicitly)
- All business rules: session durations, bomb countdown/explosion/lifeline/password-restart
  rules, streak & day-rollover semantics, freeze/sleep windows, goal math, Telegram report
  timing, review flow.
- All persisted formats, HTML/ZIP import/export, backups, toast texts, notification channels,
  bridge method names, Kotlin APIs.
- Intended UX quirks kept as-is: pre-reset values shown while locked (T5.4/T5.5), Bomb-OFF
  dual-mode streak card, `floor()`-based hour display with fractional bar fill.

## Compatibility implications
- **None for data:** no field renames/migrations; legacy phantom bombs created by older builds
  are cleaned up on first recovery when Bomb Mode is off (GT-06) — a one-time corrective, not a
  format change.
- **No API changes:** Android side untouched; JS→bridge calls identical except strictly fewer
  repeated `cancelAlarm` calls when idle (GT-05).
