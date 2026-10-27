# A. Audit Report — Deep Reliability Audit, Goal Tracker

**Scope:** `app/src/main/assets/app/index.html` (the entire application:15,384 lines) +
`app/src/main/java/com/goaltracker/shell/*` (all Kotlin sources) + AndroidManifest.
**Method:** source-level inspection, deterministic jsdom + fake-clock + fake-IndexedDB
reproduction harness, fix-then-reverify regression tests. Every finding below was reproduced
empirically before any code was changed, unless explicitly marked otherwise.

Line references are given as **pre-fix → post-fix** (`index.html`); the file was the only
application source file modified.

---

## Confirmed defects (all reproduced, all fixed)

### GT-01 — Cold-boot recovery computes day/wake boundaries from DEFAULT_SETTINGS
- **Severity:** High
- **Location:** `getDayResetHour()`/`getWakeHour()` pre-fix L3561–3562; `recoverState()` entry
  pre-fix L5627. Fixed at post-fix L3561–3571 and L5647–5650.
- **Observed:** On every cold boot with non-default boundary settings (repro: wake hour
  configured `09:00`, default `08:00`), recovery computed `nextSleepTs`/wake anchors with the
  default `08:00`, arming the new-day bomb deadline **one hour in the past** → a spurious
  explosion immediately at boot (streak reset, explosion history entry written, no user action).
  `studyDayKey()` and the Sleep-freeze window were likewise keyed on the default hours for the
  whole recovery pass.
- **Root cause:** `boot()` does `STATE = await recoverState()` — during the entire recovery pass
  the module-level `STATE` is still `null`, so the boundary helpers fell back to
  `DEFAULT_SETTINGS` instead of the persisted `state.settings` that recovery is reconciling.
- **Impact:** Wrong bomb arming, wrong study-day keying, wrong freeze evaluation on every cold
  boot for any user whose configured hours differ from defaults; visible data consequences
  (phantom explosion, streak reset).
- **Correction:** A `RECOVERY_SETTINGS` bridge is published by `recoverState()` immediately
  after loading persisted state; the helpers prefer `STATE.settings || RECOVERY_SETTINGS`.
  `STATE` still takes precedence once assigned. No persisted field, business rule, or default
  changed — recovery now simply reads the user's real configuration.
- **Regression test:** `timer.test.js` suite **T1** (boot/day-rollover arms bomb with wake
  `09:00`) — **PASS** (all T1 checks; suite 106/106 overall).

### GT-02 — Module-level `renderPlanners()` throws on every page load
- **Severity:** Medium
- **Location:** pre-fix L13081–13082 (module scope, bottom of script); fixed at post-fix
  L13144–13147 (call removed) and L15308–15309 (`boot()` now calls it).
- **Observed:** The call executes while the script evaluates, long before `boot()` assigns
  `STATE`; `renderPlanners()` dereferences `STATE` → unhandled promise rejection on **every**
  single load. The Planner list stayed unrendered until some unrelated action handler happened
  to call `renderPlanners()` again.
- **Root cause:** initialization ordering — view code invoked at module scope before state
  ownership begins.
- **Impact:** Guaranteed console error on each launch; Planner screen initially blank.
- **Correction:** Call removed from module scope; `boot()` invokes it once after `STATE` exists,
  wrapped in try/catch so a planner-render failure can never abort boot.
- **Regression test:** `smoke.js` boot check — `pageErrors: []` both at boot and after clock
  advance — **PASS** (exit 0).

### GT-03 — Background natural completion never banked into Hour Streak / Daily Study
- **Severity:** High
- **Location:** `recoverState()` catch-up loop, pre-fix L6013/L6016; fixed at post-fix
  L6046 and L6050–6058.
- **Observed:** Repro (T7): a running 30-min Study session completes while the app is dead.
  Recovery synthesizes the completion and writes the Timeline/IDB row correctly, but in that
  same pass `hourStreakSec` and `dailyStudySeconds` remained at their pre-completion values
  (streak showed 0 h after a 30-min completion; `reconcileDailyStudySeconds` results from the
  pass were computed *before* the catch-up loop ran). Foreground completion offered no later
  healing either, because `_pendingRecovery` had been consumed.
- **Root cause:** ordering — the pass-level `reconcileDailyStudySeconds()` runs before the
  catch-up loop that creates the completed session's IDB entry; nothing re-reconciled after the
  synthetic completion, and the only caller that guaranteed a later reconcile was one-shot.
- **Impact:** Hour Streak card, Today widget, heart progress, and Day Streak shown stale/wrong
  after background completions; a bomb expiry occurring later in the same recovery pass could
  capture an under-counted pre-explosion streak. This is the data-side family behind the
  reported "streak vs progress indicator" symptom.
- **Correction:** After a synthetic Study completion, recovery re-runs the **idempotent**
  `reconcileDailyStudySeconds(state)` (IDB is ground truth; reconcile only corrects upward) and
  saves. Business rules and reconciliation semantics unchanged.
- **Regression tests:** **T11** (foreground natural completion banks Hour Streak / Daily
  Study) and **T7.4/T7.5** (background completion) — **PASS**.

### GT-04 — `stopSession` double-counted pre-pause time in Timeline rows
- **Severity:** High
- **Location:** `stopSession()` pre-fix L6421/L6427, `stopSessionForEarlyComplete()` pre-fix
  L6393/L6395; fixed at post-fix L6435–6442, L6468–6485.
- **Observed:** Repro (T9): study 60 s → pause (first segment of 60 s committed by
  `pauseResumeSession`) → resume 30 s → stop. The stop wrote a Timeline row with the **whole
  session's** elapsed duration (`90 s`) but a **current-segment** span (`30 s`). Timeline sum
  became 150 s for 90 s of real study; the next reconcile then banked the inflated total.
  Stopping **while paused** additionally wrote a duplicate row for an already-committed segment.
- **Root cause:** `startSession` commits a previous activity under a `!pausedAt` guard, but
  `stopSession`/`stopSessionForEarlyComplete` wrote the row unconditionally with
  `actualDurationSec` (whole-session) instead of the segment duration.
- **Impact:** Inflated Timeline/history sums and inflated Hour Streak / Daily Study after any
  pause+resume cycle; duplicate rows for stop-while-paused.
- **Correction:** Same rule as `startSession`: no row when stopping while paused, and the row
  duration is clamped to the row's own start→end span. **The value banked to Hour Streak /
  Daily Study remains the whole-session elapsed** (`actualDurationSec`) — unchanged business
  rule; only the per-row Timeline representation was corrected.
- **Regression tests:** **T9** (pause/resume/stop math) and **T12** (stop while paused writes no
  duplicate row) — **PASS**.

### GT-05 — Idle observer sends `cancelAlarm` bridge calls every second, forever
- **Severity:** Low (churn/battery; no data corruption)
- **Location:** pre-fix L14347, L14547–14552 (`andClearAlarm`); fixed at post-fix L14413–14415,
  L14616–14626.
- **Observed:** The1-second alarm observer calls `andClearAlarm()` while idle; each call sent
  `cancelAlarm` over the JS→Android bridge (two alarm ids), performing AlarmManager +
  PendingIntent round-trips **every idle second for the lifetime of the app** — even though
  nothing was ever scheduled since the last cancel.
- **Root cause:** no "already cancelled" tracking; the original comment justifiably cancels
  after reload (native state unknown), but then repeats the same native cancel endlessly.
- **Impact:** Continuous background IPC/notification-manager churn; battery and log noise.
- **Correction:** An `andAlarmsCleared` marker per id: after a native cancel has been sent,
  repeats are skipped until a real `andSetAlarm` clears the marker. The post-reload reconcile
  behavior is preserved — the marker starts unset after every reload, so exactly one native
  cancel is still sent, as before.
- **Regression test:** notification suite **N3.4** (no repeat session-alarm cancels across a
  6 s idle window) — **PASS**.

### GT-06 — `armBomb` ungated: with Bomb Mode OFF, stop/completion installed a phantom bomb
- **Severity:** High
- **Location:** `armBomb()` delegate pre-fix L5434; call sites in
  `completeNonStudySession` pre-fix L5465 and `submitReview` pre-fix L6901; recovery pre-fix
  L5972. Fixed at post-fix L5443–5452, L5484, L5996–6004, L6964.
- **Observed:** Repro (T15/T17): with Bomb Mode **OFF**, stopping a session (and natural
  completion) installed a duration-0 (already-expired) bomb `activeTimer`. The next recovery
  pass replayed that expired deadline: consuming a real Lifeline "save", rewriting the bomb
  clock, or recording a phantom explosion — all while the mode is off.
- **Root cause:** `armBomb()` was a thin delegate that did **not** check
  `BombEngine.isEnabled()`, unlike `armForNewDay()`, so every call site armed unconditionally;
  `saveSettings` only clears existing bombs at toggle-off time, not at arm time.
- **Impact:** Violates the app's own rule (Bomb Mode off ⇒ no bomb): phantom explosions and
  Lifeline consumption from an inert subsystem; corrupted bomb history entries.
- **Correction:** The delegate now enforces the mode: off ⇒ the app simply goes **IDLE**
  (`activeTimer = null`, with `_idleStartTs` anchored so History's Idle row counts from the stop
  moment). Recovery also clears leftover legacy bomb timers created by older builds when the
  mode is off. Bomb Mode **ON** behavior is byte-for-byte unchanged, including all explosion
  rules.
- **Regression tests:** **T15** (Bomb OFF: stop/reload must not install a phantom bomb),
  **T17** (Bomb OFF: natural completion) — **PASS**.

### GT-07 — Stop-time idle anchor written to a discarded object
- **Severity:** Medium
- **Location:** `stopSession()` pre-fix L6443 (`STATE._idleStartTs = ...`); fixed at post-fix
  L6501–6505 (`state._idleStartTs = ...`).
- **Observed:** Repro (T16): after a stop, History's live Idle row anchored to a stale/null
  value (defaulting to the day's wake time) instead of the stop moment — idle time displayed
  as far larger than reality.
- **Root cause:** `stopSession` mutates the **pre-saveState** `STATE` cache object, while
  `saveState(state)` then replaces the cache with a fresh `getState()` clone. The assignment
  happened on the wrong object and was silently discarded; `startSession`'s later
  `STATE = STATE`-style resync confirmed the loss.
- **Impact:** Wrong live Idle duration in History after every stop; also interacts with GT-06's
  idle accounting.
- **Correction:** Write the anchor on `state` — the exact object `saveState` persists.
- **Regression test:** **T16** (History idle anchor = stop moment) — **PASS**.

---

## Reported-symptom disposition

| Reported symptom | Disposition |
|---|---|
| Hour streak resets to 0 while progress bar stays partially filled | **Root causes confirmed & fixed via GT-01 / GT-03 / GT-04** (stale banked streak values after boot/completion/pause paths). Additionally, `#streakNum` and `#streakBarFill` are two render paths reading the same `STATE` in the same 1 s tick, so no durable divergence source remains. Note (intended, unchanged): while the app is **locked** after an explosion both the number and the bar deliberately show pre-reset values until the password restart — asserted by T5.4/T5.5. In Bomb-OFF mode the card intentionally shows *Day Streak (days)* while the bar shows *daily hours progress* — documented dual-mode design, not divergence. Suite **T5** (11 checks) passes. |
| Bomb fires at odd times / after restart | **GT-01** (boot arming with default wake hour) and **GT-06** (phantom bomb with mode off). Fixed; T1/T4/T15/T17 pass. |
| Totals don't match study time | **GT-04** (Timeline double-count) and **GT-03** (unbanked completions). Fixed; T7/T9/T11/T12 pass. |

---

## Suspected defects / architectural risks (not fixed — documented only)

1. **Single-tick cosmetic window (Low, theoretical):** `#streakNum` (renderHeader path,
   ~L8020) and `#streakBarFill` (`updateStreakBar`, ~L7251) are updated by two different
   functions. Both read the same `STATE.hourStreakSec + liveSec` within the same second-tick, so
   any divergence would last at most one render cycle. Not reproducible in tests; left as-is —
   consolidating the renders would be a refactor without a demonstrated failure mode.
2. **`floor()` vs fractional fill (by design):** with 30 banked minutes the number shows `0`
   (floor of 0.5 h) while the bar shows 50%. This is the intended "progress toward the next
   hour" math (`(totalSec % 3600)/3600`), not a defect. Preserved.
3. **`RECOVERY_SETTINGS` staleness (Low):** the module variable persists after recovery
   finishes. It is re-published on every `recoverState()` call and always shadowed by `STATE`
   once assigned, so no read path can see it before `STATE` exists. Documented as a bounded
   lifetime design note.
4. **Alarm permission surfacing:** exact-alarm/notification permission re-request is handled on
   Android side (`MainActivity`/`JsBridge`); no re-prompt loop was found in HTML settings. Not
   a confirmed defect; device-level behavior untested (see 05-remaining-risks).

## Optional improvements (not implemented — no demonstrated failure)

- A single `renderStreak()` owning both number and bar (cosmetic consolidation).
- Instrumented bridge-call counters for alarm churn diagnostics in release builds.
- `tests/debug1.js` / `tests/debug2.js` are investigation scratch files; harmless, but could be
  deleted from a release of the test folder.
