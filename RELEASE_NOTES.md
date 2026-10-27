# Goal Tracker — Release Notes

> The newest version is always at the TOP. Older versions remain below.

---

## v4.12 — 2026-10-24

**Thought Logging off-switch fixed · shared typed-confirm dialog · Display Size remapped to 1–9**

- **Thought Logging toggle bug fixed:** the OFF choice was silently reverted to
  ON whenever the settings draft had been cleared by an earlier Save (the
  change handler dropped the value when no draft existed). The listener now
  (re)begins the draft and pins the checkbox, so the choice is never lost.
- **Turning OFF is confirmed at Save Changes:** pressing Save Changes with the
  switch OFF opens the typed confirmation — **type THOUGHT** (case-insensitive).
  A wrong word keeps everything unchanged; **Cancel keeps the feature ON** and
  still saves the category edits. After confirmation the feature hides
  **everywhere at once** (Home card, History reader, Goals reader, session-end
  logging; Settings subtitle shows "Off"). **Saved records are never deleted** —
  turning the feature back on brings them all back (verified by test).
- **`thoughtsEnabled` now has a single writer** (the Thought Logger Save flow);
  the general settings save no longer writes it, so an unconfirmed toggle can
  not leak through another path.
- **ONE shared typed-confirmation dialog** (`#sharedConfirmDialog` +
  `openSharedConfirm()`) now drives every typed-confirm flow — same elements,
  only text/input/password differ: Goal delete (`12345`), Planner delete
  (`12345`), Delete-all-pending (`12345`), Reset All Data (`123456789`), Task
  restart (`123`), Turn off Thought Logging (`THOUGHT`). Custom-layout dialogs
  (Early-complete, task-delete preview, Bomb lock screen) keep their own markup.
- **Display Size remapped to 9 levels (1–9, uniform 8% steps):** old→new —
  1→3, 2→4, **3→5**, 4→6, 5→7, 6(normal)→8, 7→9; old 8/9/10 are removed
  (clamped to 9). New levels **1 and 2** are the two extra-small sizes
  (−42% / −37% of normal). **Default = Level 5** (= the old level 3, 21%
  smaller), **Level 8 = exactly normal**, and Level 5→6 is the standard subtle
  8% step you asked for.
- **One-time migration everywhere:** native prefs (`displayScaleScheme`=`"2"`)
  and browser localStorage (`gt_displaySizeScheme`) both guard the old→new
  +2 shift, so every stored choice keeps its exact visual size and is never
  shifted twice. Ancient xsmall/small/medium/large values migrate through the
  same shift.
- **On-device debug readout kept** (baseline marker now at Level 8).
- **Home Thoughts card can no longer be resurrected while OFF:**
  `renderSleepExtraCard()` (ticked every second from `renderHeader()`) used to
  reset the card's visibility to visible whenever the Sleep window was inactive,
  flipping it back on moments after the toggle hid it (vanish on touch, reappear
  after ~1s). It now respects `thoughtsEnabled` alongside the sleep state.
- **Import User Data confirmation word: `RESTORE` → `IMPORT`.**
- **NEW setting: "↩️ Revert to original HTML"** (right under Install NEW HTML
  File in Android Shell Settings → App & Data). Deletes a custom-installed HTML
  interface and restores the bundled original (native typed confirmation —
  **type ORIGINAL**; no dialog when nothing custom is installed). User data
  directories are never touched; exposed as `AndroidBridge.revertHtmlFile()`.
- **NEW: conditional Home layout for Bomb Timer OFF (L-shape):** while Bomb
  Mode is disabled the Bomb card is REMOVED from the DOM (takes no space) and
  the Study control buttons (START/PAUSE/STOP) relocate into a card directly
  under Day Streak that is fused FLUSH against the Study card (negative-margin
  join covering the 1px border, squared junction corners, closed bottom
  elbow) — one continuous white L surface, no seam line. A subtle footer line (tiny bomb SVG + "Bomb Timer Mode Disabled. Enable
  from Settings.", styled after the Tasks lock banner) sits at the very bottom
  of Home. Toggling in Settings moves the SAME DOM nodes (listeners intact —
  verified by test) and re-enabling restores the EXACT original structure.
  Bomb ON renders 100% identical to before.
- **Native/version:** `versionName "4.12"`, `versionCode 22`,
  `JsBridge.getShellVersion()` → `"4.12"`.

---

## v4.11 — 2026-10-23

**Display Size (1–10), top-bar removal, App & Data menu, fail-safe backups**

- **Display Size setting (levels 1–10, level 6 = exactly the old look):** Settings →
  Android Shell Settings → Display Size. Default level **2**, ~8% per level. The app owns
  its scale through the viewport-meta engine; the wrapper pins `textZoom = 100`, so
  Android's system Font size / Display size no longer restyle the app. Legacy
  xsmall/small/medium/large values migrate automatically.
- **ROOT-CAUSE FIX — levels below 6 now genuinely shrink.** Android WebView defaults to
  narrow viewport mode (`useWideViewPort=false`, `loadWithOverviewMode=false`), which
  silently discarded every requested scale **below 1.0** while allowing zoom-in above 1.0 —
  the same defect that made the earlier Extra-Small/Small options do nothing. The shell now
  configures WebView browser-style (`useWideViewPort=true`, `loadWithOverviewMode=true`),
  the setup Chromium documents for WebView/Chrome parity. Level 6 still emits
  `width=device-width, initial-scale=1.0` — byte-identical baseline.
- **On-device debug readout** in the Display Size card (kept by request): level → computed
  scale → viewport meta → rendered viewport (`vv.scale` + applied ✓/✗ marker) → native
  WebView settings.
- **Green top bar removed (NoActionBar):** the HTML starts at the top of the screen; the
  three-dot menu is gone with it.
- **All menu actions moved into Settings → Android Shell Settings → App & Data**, in order:
  🔄 Refresh App · 📂 Import User Data · 💾 Export User Data · 📥 Install NEW HTML File ·
  📄 Save Current HTML File (NEW) · 🗑️ Factory Reset User Data · 🔍 System Check. Native
  typed confirmations, staged HTML/ZIP validation and file pickers are unchanged. "Restore
  Default App" was removed as requested; installing HTML never touches user data, and
  Factory Reset never touches the HTML file.
- **Android Shell Settings launcher moved to the top of the Settings list.**
- **Backup import is now fail-safe:** the native side validates the ENTIRE file (app id,
  JSON shape, state structure, FNV-1a checksum) before any stored file is written — a
  corrupt or foreign JSON shows "Import failed: …" and changes nothing. The HTML side
  re-validates before clearing the database and rolls back automatically if a restore fails
  midway (the old path cleared first, which could corrupt data).
- **Backup export hardened:** fresh state mirror first (aborts if it cannot be read),
  checksum attached, atomic temp-then-rename writes, refuses to export when no data exists.
- **Native/version:** `versionName "4.11"`, `versionCode 21`,
  `JsBridge.getShellVersion()` → `"4.11"`.

---

## v4.10 — 2026-10-10

**Short alarm durations and extra notification tones**

- **1 / 2 / 3 second options** added to Pomodoro, Task Complete, and Bomb sound-duration dropdowns. Native clamp is now 1–60 seconds so a 1-second Test and a 1-second real event both stop on time.
- **New pleasant notification tones:** Soft Harp, Wood Tap, Soft Piano, Water Drop.
- **Native/version:** `versionName "4.10"`, `versionCode 20`, `JsBridge.getShellVersion()` → `"4.10"`.

---

## v4.9 — 2026-10-10

**Native alarm sound, duration, and Reality Check silence**

- **Per-slot scheduled alarms:** `scheduleAlarmWithSound(id, triggerMs, title, text, soundSlot, durationMs)` persists `sessionend`, `taskcomplete`, and `bombwarn` independently. Playback uses the selected built-in or device sound, not the old looping Classic Alarm notification.
- **Native duration:** the configured 5–60s window is enforced by `AlarmPlayer` / `AlarmPlaybackService`. It no longer depends on WebView timers, so a 5-second alarm stops after 5 seconds even when the app is backgrounded.
- **Silent alarm notifications:** scheduled alarms post on a new silent channel (`alarm_silent_v1`) without `FLAG_INSISTENT`. Native playback is the only audio source.
- **Stop without cancelling the future:** `stopAlarmPlayback()` silences current audio. Tapping a Reality Check rating (1–5) calls this immediately and does not `cancelAlarm()`, so future Pomodoro/Bomb schedules stay intact.
- **HTML:** bundled `index-v21-html-fixed.html` as `assets/app/index.html`. Non-imported installs pick up the new HTML on upgrade.
- **Native/version:** `versionName "4.9"`, `versionCode 19`, `JsBridge.getShellVersion()` → `"4.9"`.

---

## v4.8 — 2026-07-16

**Bomb Challenge Mode — Wake-up Trigger Behaviour Fix**

- **Both modes now always restore the full Bomb Duration at Wake-up.** In all previous builds, if yesterday's Bomb had time remaining, that leftover value carried into the new day. From v4.8 onward, the very first step at every day rollover is unconditionally resetting `bombRemainingMs` to the full configured Bomb Duration, regardless of which trigger mode is selected.
- **Mode 1 (Start at Wake-up Time):** After restoring the full clock, the Bomb arms live immediately at Wake-up and the replay orchestrator feeds the elapsed budget (time since Wake-up) through the existing engine — consuming Lifelines and exploding at each cycle boundary exactly as designed.
- **Mode 2 (Start After First Activity):** After restoring the full clock, the Bomb enters a `bombDormant` state — `activeTimer = null`, no countdown, no red background, no wobble. The timer displays the full duration and waits. The moment the user starts their first Study session (regular or Extra Study), `bombDormant` is cleared and the normal Bomb engine takes over for the rest of the day without any further interference from Mode 2.
- **Idle UI label (Mode 2):** While dormant, the Bomb card now shows "Starts after your first activity" instead of the misleading "Bomb Paused".
- **Native/version:** `versionName "4.8"`, `versionCode 18`. HTML-only change; the standalone HTML and the bundled `assets/app/index.html` are identical.

---

## v3.9 — 2026-07-15

**Mark Activity As Completed Early (Today's activity View/Info action)**

- **Pencil → View/Info icon once an activity is started:** On the Tasks screen, as soon as a
  Today activity has been started (has any consumed time), its edit pencil is replaced by a
  circular View/Info icon. Not-yet-started activities keep the pencil, and the existing
  "Lock today's editing" setting still hides the action entirely when enabled.
- **Confirmation popup (type `DONE`):** Tapping the View/Info icon opens a confirmation popup
  (reusing the shared typed-confirm dialog shell) instead of the old "editing is not allowed"
  toast. It shows the activity name, subject, current progress (consumed / target), the exact
  remaining time that will be released, and a plain-language "What will happen" list. Confirming
  requires typing `DONE`.
- **What happens on confirm:** The activity is immediately marked **Completed**. Its assigned
  duration is trimmed down to the time actually consumed, so ONLY the unfinished remainder is
  discarded and released back into the budget for creating another activity. Completed study/
  activity time, History records, Goal Progress, Campaign Progress and Today's Study Progress are
  all preserved — nothing decreases.
- **History "Completed Early" line (Study only):** Completed-early Study activities show a single
  compact `✔ Completed Early` line in History → Study Task Progress that reports the ACTUAL (earlier)
  completion time vs. the ORIGINAL assigned time (e.g. `3h / 5h`). It replaces the standard
  "Completed" line entirely — no separate row — and never renders the two values as equal.
  Restarting a task clears this bookkeeping for the fresh cycle.
- **UI polish:** The started-activity View/Info button on the Tasks screen now uses a red palette so
  it is clearly distinct from the purple edit pencil, and the Completed line uses a bold `✔` check
  glyph for better visibility.
- **Native/version:** `versionName "3.9"`, `versionCode 16`,
  `JsBridge.getShellVersion()` → `"3.9"`. HTML feature work; the standalone HTML and the bundled
  `assets/app/index.html` remain byte-for-byte identical.

---

## v3.8 — 2026-07-15

**Goal Status simplified to On Time / Delayed + Goal Section manual externalized**

- **On Time / Delayed (Projected Finish sub-line):** The live "Delay by X Days Y Hours"
  engine was replaced with a clean two-state indicator: **🟢 On Time** or **🔴 Delayed**.
  It reuses the exact "Pure Study Time Available Today" spine from the Today's Progress →
  Finishing By engine — `pureStudyAvail = (Day End − now) − remaining non-study activities` —
  and compares it against the campaign backlog (`Expected − Completed`, the same value shown
  as "Behind by X"). If the whole backlog still fits inside today's pure study time → On Time,
  otherwise → Delayed. No delay-days/hours are computed. Fully live on the render loop.
- **Projected Finish DATE + Final Day Study untouched:** The pace-anchored finish date
  (`remaining / originalDailyTarget`) and the fractional Final Day Study line are preserved
  exactly as before.
- **Goal Section manual externalized to `manuals.js`:** The "Formula & Calculation Logic"
  card no longer hardcodes its text. The full Goal Section philosophy + formulas now live in
  a bundled `manuals.js` (`window.GOAL_SECTION_MANUAL`), loaded via a normal `<script>` tag so
  it works both in the Android WebView and when the standalone HTML is opened directly
  (`file://`) — no `fetch()`. Shipped in both the payload root (next to the standalone HTML)
  and inside `app/src/main/assets/app/`.
- **New developer README at the payload root:** `README - Goal Section Philosophy & Formulas.md`
  is the first-reference document describing every Goal section's philosophy and formula.
- **Native/version:** `versionName "3.8"`, `versionCode 15`,
  `JsBridge.getShellVersion()` → `"3.8"`. HTML/docs feature work; the standalone HTML and the
  bundled `assets/app/index.html` remain byte-for-byte identical.

---

## v3.7 — 2026-07-15

**Goal Status Card: live Campaign Delay engine + Projected Finish enhancements**

- **Live Delay indicator (Projected Finish):** The status line under the projected
  finish date is now a live "On Time" / "Delay by X Days Y Hours" indicator instead of
  a static "(N days late)". It shares the same "Pure Study Time Available Today" spine as
  the Today's Progress → Finishing By engine (single source of truth).
- **Recoverable-first delay math:** Today's daily target is always part of Expected
  (so Behind/Ahead reflects it immediately), but is NOT counted as campaign delay while it
  is still realistically recoverable within today's remaining study time. Only the
  unrecoverable remainder is converted into Delay Days + Hours. A caught-up day with time
  available stays "On Time"; as time is wasted, delay grows hour-by-hour; as you study,
  it ticks back down. Fully live.
- **Final day study restored when delayed:** "Final day study: ~Xh" now shows whenever
  the projection is delayed (previously it hid when the backlog divided into whole days);
  in that exact-division case it shows the full daily target for the final day.
- **Status Card display polish:** Behind/Ahead and Required-Now now render as
  "H h M min" instead of decimals; Required-Now sub-line uses "more / less every day".
- **NaN/Infinity guards:** Delay computation is guarded against an unset daily target.
- **Native/version:** `versionName "3.7"`, `versionCode 14`,
  `JsBridge.getShellVersion()` → `"3.7"`. HTML feature work; the standalone HTML and the
  bundled `assets/app/index.html` are byte-for-byte identical.

---

## v3.6 — 2026-07-14

**Completion Sound: configurable duration + Test/Stop preview (centralized SoundController)**

- **Sound Duration setting:** New `Sound Duration` selector (5 / 10 / 15 / 30 / 60
  seconds) added to the Completion Sound card in Application Settings. Persisted as
  `settings.android.soundDurationSec` (default 60, clamped to the allowed choices).
- **Centralized SoundController:** All completion-sound playback (session complete,
  review notification, bomb warning, and settings previews) now flows through a single
  `SoundController`. It reads `soundDurationSec` and stops the natively-looped sound
  early at the chosen duration, so every trigger honors the same setting.
- **Test → Stop toggle:** The Test buttons (completion + bomb) now toggle to "Stop"
  while a preview plays and reset to "Test" automatically when the duration elapses.
  Only one preview can play at a time — starting a different preview stops the current
  one, preventing overlapping audio.
- **Browser fallback:** When no Android shell bridge is present, a WebAudio tone drives
  previews so duration + Test/Stop still work in a plain browser.
- **Native/version:** `versionName "3.6"`, `versionCode 13`, `JsBridge.getShellVersion()`
  → `"3.6"`. HTML-only feature work otherwise; the standalone HTML and the bundled
  `assets/app/index.html` are byte-for-byte identical.

---

## v3.4 — 2026-07-14

**Bomb Mode: Tasks 5–7 complete + Freeze hardening + Timeline fix + Settings precision**

- **Task 5 — Configurable Daily Free Lifelines (5.1–5.3):** `dailyFreeLifelines`
  setting added to `DEFAULT_SETTINGS` (default 1, range 0–3, hard max 3).
  `BombEngine.resetDailyLifelines()` now reads this setting instead of the
  hardcoded `1`. Stepper UI added to the Bomb settings sub-page.
- **Task 6 — Explosion Card → Restart → Password → Resume (6.1–6.5):**
  `pendingBreachScreen` auto-popup replaced with a deliberate card flow.
  `dailyLifelinesEarned` reset to 0 on explosion (bonus lifelines forfeit).
  The explosion state reuses the Time-Inconsistency-style warning card with a
  `bombLocked` CSS class and "Restart Our Streak" button. Password verify clears
  the lock. Day-rollover auto-clears the lock so a user who doesn't return until
  tomorrow is never permanently locked out. Dead `pendingBreachScreen` variable
  and its unreachable render-block check fully removed.
- **Freeze Architecture hardening:** `state.bombLocked` (boolean) is now
  persisted to IDB as the single source of truth for BOMB_LOCK. Previously the
  lock was in-memory only — a force-kill cleared it silently. Now
  `syncFreezeReasons()` is the single place that mirrors the persisted flag into
  `FreezeManager`, making BOMB_LOCK fully symmetric with SLEEP and
  TIME_INCONSISTENCY. `saveSettings` also clears a stale `activeTimer` of type
  `bomb` when Bomb Mode is turned off.
- **Task 7 — Timeline root-cause fix:** `buildFullDayTimeline` rewritten to
  anchor on the actual Wake-up timestamp (`todayWakeTs`) and end at the Sleep
  boundary (`nextSleepTs`), capped at `now`. A gap sub-segment is only labelled
  `bomb` if the bomb timer is actively armed (`activeTimer.type === 'bomb'`) and
  the segment starts at or after `activeTimer.startTs`. This eliminates the
  24-hour ghost bomb entry that appeared after day-rollover (empty IDB) and the
  false bomb colouring of gaps before the bomb was armed. `state.bombExplodedToday`
  counter added (incremented on explosion, reset at day-rollover) and shown as a
  compact stat line in the Timeline view.
- **Settings — 30-minute Sleep/Wake precision:** Both dropdowns now offer 48
  half-hour slots (`12:00 AM`, `12:30 AM` … `11:30 PM`). All read/write paths
  updated from `parseInt` to `parseFloat`. `sleepIntervalsInWindow` updated to
  handle fractional hour values precisely.
- **Settings — Labels & Headings card** moved to bottom of All Other Settings
  sub-page (no functional change).
- Shell version bumped to **3.4** (`versionCode 12`). Standalone
  `goal-tracker.html` verified byte-for-byte identical to the bundled
  `app/src/main/assets/app/index.html`. All inline `<script>` blocks pass
  `node --check`.

---

## v3.3 — 2026-07-14

**Bomb Mode Engine: controlled centralization of all Bomb logic (Tasks 1–4.2)**

- Introduced `BombEngine` — a single authoritative object that now owns ALL
  Bomb Mode derivation and state-transition logic. This eliminates ~25 scattered
  Bomb call sites and makes duplicated ownership structurally impossible.
- Approach: controlled incremental centralization — no full rewrite. The timer
  engine, Study Time architecture, Pomodoro logic, goals, campaign, history,
  Telegram reports, and all non-Bomb code were left untouched.
- **Task 1 — Engine foundation:** `BombEngine` introduced with single ownership
  of all derivation helpers (`isEnabled`, `fullClockMs`, `extensionMs`,
  `maxClockMs`, `baseMs`, `isTicking`, `liveRemainingMs`, `clampToFull`). The
  five legacy helpers (`bombModeOn`, `bombBaseMs`, `bombIsTicking`,
  `bombLiveRemainingMs`, `lifelineExtensionMs`) became thin delegates.
- **Task 2 — State transitions (mini-steps 2.1–2.6):** All Bomb state
  transitions migrated into the engine: `arm` (idle activation), `armForNewDay`
  (day-rollover full-clock refill), `explode`/`explodePaused` (both explosion
  paths), `consumeLifeline` (single heart-decrement + extension authority),
  `onSessionPause`/`bankPausedElapsed`/`onSessionResume` (session banking),
  `grantEarnedLifelines`/`resetDailyLifelines` (single lifeline award authority).
- **Task 3 — Countdown fix + upper safety clamp (3.1–3.2):** Added `maxClockMs`
  ceiling (larger of a full clock or one extension). `clampToFull` wired into the
  `baseMs` read and `bankPausedElapsed` write. `liveRemainingMs` hardened with
  future-timestamp guard and upper clamp on both paths. Closes the bomb-timer
  accumulation leak across future timestamps, background recovery, sleep,
  lifeline extension, multiple banking, and app restart (investigation item #7).
- **Task 4 — Lifeline duplicate-award race fix (4.1–4.2):** `grantEarnedLifelines`
  hardened with a re-entrancy latch (`_granting`) and monotonic clamp of
  `dailyLifelinesEarned` so duplicate awards are structurally impossible. Added
  `persistAward()` — a serialized promise-tail persist path — so the 1s-tick
  live-award save can never be clobbered by a concurrent save, closing the
  background-restore re-award path.
- Shell version bumped to **3.3** (`versionCode 11`). Standalone
  `goal-tracker.html` verified byte-for-byte identical to the bundled
  `app/src/main/assets/app/index.html`. All inline `<script>` blocks pass
  `node --check`.

---

## v3.2 — 2026-07-14-10-06

**Fix: Hour Streak briefly inflated by ~+8h during a Sleep freeze (and silent Lifeline/Timeline corruption)**

- Fixed the bug where, if the app was closed across Sleep Start and reopened
  mid-sleep, the Hour Streak on the home screen jumped by roughly the whole
  night (~+8h) and then snapped back to the correct value after Wake Time.
- Root cause: the live study-time displays each computed elapsed time inline as
  `effectiveElapsedMs()/3600` with NO upper bound, and the Sleep freeze latched
  its boundary at the instant a recovery poll happened to run (`Date.now()`)
  rather than at the actual Sleep-Start moment. A session frozen across a closed
  app therefore reported the entire overnight gap as "studied time" until the
  Wake-time daily rollover replaced the stale session and reset the display.
- Two related, more serious defects were fixed at the same time: the per-second
  `renderHeader()` render pass was AWARDING and PERSISTING Lifeline hearts off
  that same uncapped value (permanent, did not self-correct), and `stopSession`
  / `submitReview` could bank an uncapped duration into the Timeline (and from
  there into Campaign / Goal / History / Telegram totals).
- Fix (localized, no timer/engine/calculation redesign — the Pomodoro logic,
  Bomb logic, countdown math, timer engine, persistence, recovery and sync were
  left intact; only bug fixes were applied):
  - **Freeze Manager** — a single, generic, extensible reason-Set that stays
    frozen while ANY reason (Sleep, Time-Inconsistency, Bomb-Lock) is active and
    unfreezes only when ALL clear, so overlapping freezes behave correctly.
  - **Root-cause fix** — the Sleep freeze now latches at the last trusted wall
    time (`min(now, lastSeenWall)`) instead of poll time, so a session frozen
    across a closed app freezes at the pre-sleep boundary, not the whole night.
  - **Canonical clamped study time** — one read-only `sessionElapsedSec()` that
    can never exceed a session's assigned duration; every live display uses it.
  - **Read-only rendering + strict commit gate** — `renderHeader()` no longer
    mutates state (the live Lifeline award moved into freeze-gated business
    logic), and every persisted study value passes a strict clamp-and-log gate
    (`commitStudySeconds`) so an impossible value can never reach Hour Streak,
    Daily Study, Timeline, Goals, Campaign, History, Telegram, or Hearts.
- Guarantee: study time can never exceed a session's real duration anywhere, and
  no render pass can write state — so the inflation and the silent corruption are
  structurally impossible while all normal behaviour is unchanged. (The optional
  one-time backfill repair of any already-corrupted stored values was reviewed
  but intentionally DEFERRED for this release, pending on-device verification.)
- Shell version bumped to **3.2** (`versionCode 10`) in `build.gradle` and
  `JsBridge.getShellVersion()`. Standalone `goal-tracker.html` verified
  byte-for-byte identical to the bundled `app/src/main/assets/app/index.html`.

---

## v3.1 — 2026-07-13-19-15

**Fix: notification showed stale Bomb line after a background Pause / Resume**

- After v3.0, tapping Pause/Resume on the notification while the app was
  backgrounded correctly changed the timer, but the notification's Bomb line
  was stale: on Pause the Bomb line was missing, and on Resume a Bomb line
  sometimes lingered. Opening the app corrected it.
- Root cause: while backgrounded the notification is drawn entirely by the
  v3.0 temporary native feedback (HTML is asleep, and HTML's own re-render only
  runs on its 1s heartbeat, which Android freezes in the background). That
  feedback froze/unfroze the session clock but never added or removed the Bomb
  line — and it had no Bomb remaining-time to show, because that value lives
  only in HTML.
- Fix (small, localized, no timer/engine/sync redesign):
  - HTML now attaches an INERT `bombPreview` (the Bomb's remaining ms + its
    exact line text) to the running-session state it already pushes via
    `setTimerState`. HTML computes this with its existing `bombLiveRemainingMs`.
  - The native Pause/Resume feedback uses that inert data to append the Bomb
    line on Pause and strip it on Resume. Native performs NO Bomb calculation
    and owns no Bomb logic; it only renders/hides what HTML provided.
- Guarantee preserved: HTML remains the single source of truth for all timer
  and Bomb semantics; it overwrites the temporary native presentation the
  instant it is alive again. The timer subsystem (Pomodoro logic, Bomb logic,
  countdown math, timer engine, persistence, recovery, synchronization) was
  left LOCKED and untouched.
- Shell version bumped to **3.1** (`versionCode 9`). Standalone
  `goal-tracker.html` verified byte-for-byte identical to the bundled
  `app/src/main/assets/app/index.html`.

---

## v3.0 — 2026-07-13-18-30

**Fix: notification action buttons (Pause / Resume / Stop) worked only while the app was open**

- Fixed the "dummy button" bug where tapping Pause / Resume / Stop on the
  timer notification did nothing while the app was in the background — on
  reopening, the Pomodoro was still running and the tap appeared to have been
  ignored.
- Fixed the related bug where pressing Stop while a Bomb was still ticking
  destroyed the timer notification entirely, even though the Bomb kept running.
- Shell version bumped to **3.0** (`versionCode 8`).

---

## v2.6 — 2026-07-13

**Fix: freeze-on-resume regression**

- Fixed the bug where the app UI appeared frozen for several seconds after
  returning from the background, then jumped and updated all at once.
- Shell version bumped to **2.6** (`versionCode 7`).

---
