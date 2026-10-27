# B. Timer Architecture Report — Goal Tracker

This describes the **actual** implementation as verified in source, not an intended design.

## 1. Sources of truth

| Store | Owner | Contents |
|---|---|---|
| **IndexedDB (`goalTracker` DB)** | HTML/JS (`saveState`/`getState`, atomic per-call writes) | `state` document: `activeTimer`, `hourStreakSec`, `dailyStudySeconds`, settings, bomb bookkeeping, `_pendingRecovery`, day/wake/report keys; plus Timeline/pomo-log row store |
| **Module-level `STATE` cache** | HTML/JS `boot()` | In-memory mirror of the persisted document; reassigned from `getState()` after every save/recovery |
| **`RECOVERY_SETTINGS` bridge** (new, GT-01) | `recoverState()` | Publishes persisted settings for the window where `STATE` is still `null`; shadowed by `STATE` once assigned |
| **Wall-clock timestamps in `activeTimer`** (`wallStartTs`, bomb deadlines) | persisted state | The authoritative elapsed-time basis: all elapsed/remaining math is `now − wallStartTs` or `deadline − now`, never accumulated ticks |
| **Native alarms (AlarmManager)** | Android `AlarmScheduler` via `andSetAlarm`/`andClearAlarm` bridge | Session-end and bomb-explosion alarms that survive process death |
| **Native notification state** | Android `JsBridge.setTimerState` etc. | Displayed notification clocks; a projection of JS state, never authoritative |

**Authoritative:** IndexedDB + wall-clock fields. **Derived:** DOM countdowns, notification
text, progress bars, widgets — all recomputed from authoritative values each 1 s tick.

## 2. Timer state flow and callback ownership

```
boot()
 └─ recoverState()            ← single recovery authority (guarded by _recovering)
     ├─ publish RECOVERY_SETTINGS (GT-01)
     ├─ reconcileDailyStudySeconds(state)      (IDB → hourStreakSec/dailyStudySeconds)
     ├─ bomb-mode cleanup (GT-06)
     ├─ catch-up while-loop on wallStartTs     (synthetic transitions for dead time:
     │    study completion → completeNonStudySession → re-reconcile (GT-03)
     │    bomb deadline    → handleBombExpired (lock + explosion rules)
     │    pomodoro         → REVIEW_PENDING)
     ├─ saveState() after each transition
     └─ syncFreezeReasons(state) → FreezeManager
 └─ render() + start 1s tick (renderHeader / updateStreakBar / alarm observer)
```

- **One 1-second interval** drives display, alarm reconciliation, and notification pushes.
  `activeTimer` transitions are guarded (`_recovering`, `_sendingScheduledReport`, engine
  guards) against re-entry; recovery catch-up only processes rows with `endTime == null`, so
  it cannot duplicate sessions.
- **Session lifecycle:** `startSession` writes `wallStartTs` and commits a previous *paused*
  activity only under a `!pausedAt` guard; `pauseResumeSession` commits each completed segment
  at pause time; `stopSession` banks whole-session elapsed to Hour Streak/Daily Study and
  writes a Timeline row for the **current segment only** (GT-04); bomb arming is gated by
  Bomb-Mode-ON (GT-06); the stop-time idle anchor is written to the persisted object (GT-07).
- **Bomb engine (`BombEngine`):** arms from persisted deadline; expiry flows through
  `handleBombExpired` (lock → lifeline/password restart rules — business rules unchanged).
  Explosion is the only operation that resets `hourStreakSec`.

## 3. Competing sources of timer state (audited)

1. **JS interval vs. native alarms** — JS computes display; AlarmManager fires the real
   session/bomb end while dead. Reconciled by the per-second `andClearAlarm` observer
   (now churn-limited, GT-05) and by `recoverState` on resume. Not conflicting: native side
   never mutates state, only notifies.
2. **Recovery vs. live tick** — mutually excluded by the `_recovering` flag; the tick's
   mutations run only after recovery returns.
3. **`STATE` vs. local `state` clones inside async functions** — this was the GT-07 class of
   bug (writing to a pre-`saveState` object). Post-fix, mutations that must persist are written
   to the object passed through `saveState`.
4. **Two DOM render paths for the streak card** — same tick, same source values; theoretical
   one-tick cosmetic window only (documented in 01-audit-report, not reproducible).

## 4. Background responsibilities (division of labor, as implemented)

- **HTML/JS:** all business logic, current activity state, elapsed math, Timeline/history
  writes, bomb rules, Telegram scheduling, freeze evaluation, notification *content*.
- **Android:** permission requests, AlarmManager scheduling, notification posting, alarm
  audio playback (`AlarmPlaybackService` foreground service), boot re-scheduling
  (`BootReceiver`), WebView lifecycle/`onResume` → reload path. The Kotlin side contains no
  business rules; it echoes what JS asks for via the bridge.

## 5. Persistence and recovery behavior

- Every transition persists via `saveState()` **before** consequential side effects where a
  crash in between would otherwise replay them (e.g. report keys, explosion records).
- Recovery is idempotent: IDB rows with `endTime == null` are re-constructed; completed rows
  are skipped; reconcile only corrects upward from IDB ground truth (this is why the GT-03
  mid-loop re-reconcile is safe).
- **Crash mid-recovery (PARTIAL-WORK SAFETY):** committed `saveState` chunks are re-reconciled
  from scratch on next launch; no duplicate rows.
- **Device restart:** `BootReceiver` re-arms persisted native alarms; HTML state is untouched
  until the next WebView load, when `recoverState` replays wall-clock deltas.

## 6. Recovery matrix (verified by tests)

| Scenario | Mechanism | Test |
|---|---|---|
| Screen navigation | SPA views, state untouched | T2, T10 |
| Background & reopen | wall-clock math + alarm fire + `recoverState` on resume | T6, T8 |
| WebView reload | fresh page over same IDB → full `recoverState` | T6, N4 |
| Process death, session completes | catch-up loop synthesizes completion + re-reconcile (GT-03) | T7, T11 |
| Process death, bomb deadline passes | catch-up replays expiry through `handleBombExpired` | T4, T7 |
| Overnight sleep / wake rollover | boundary helpers (GT-01) + report scheduler keys | T8, T1 |
| Device reboot | `BootReceiver` → AlarmManager re-arm (Android, not replayed in JS tests) | untested on device |
| Restart after explosion | fresh bomb, no stale callback revival | T5, T10, T15, T17 |
