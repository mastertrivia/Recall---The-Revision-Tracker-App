# Goal Tracker — Android Shell v3.3 — ENGINEERING HANDOVER

> **This is the authoritative handover for v3.3.**
> A brand-new AI session with ZERO prior context can continue using ONLY:
> this file + the project ZIP + `PROJECT_STRUCTURE.md`. Read
> `PROJECT_STRUCTURE.md` FIRST before touching anything.

---

## WORKFLOW RULE (mandatory — do NOT ignore)

After completing ANY task or mini-step, **STOP immediately** and ask the owner:

1. Continue to the next mini-step?
2. Stop here and prepare the ZIP + HANDOVER package?

Never auto-continue. Every finished step is a safe checkpoint. The owner
decides what happens next.

---

## v3.3 — Bomb Mode Engine (controlled centralization, Tasks 1–4.2)

**Shell/native version = 3.3** (`JsBridge.getShellVersion()` → `"3.3"`,
`app/build.gradle` versionCode 11 / versionName "3.3"). Only the bundled HTML
changed this release (no Kotlin change needed), but the shell version was bumped
per the packaging rulebook — rebuild the GitHub Actions APK so the bundled HTML
and reported shell version match.

### What was done this session

This session introduced the **Bomb Mode Engine** — a controlled, incremental
centralization of all Bomb Mode logic from ~25 scattered call sites into a single
authoritative `BombEngine` object inside `index.html`. No full rewrite was
performed. The timer engine, Study Time architecture, Pomodoro logic, goals,
campaign, history, Telegram reports, and all non-Bomb code were left untouched.

#### Task 1 — Engine foundation (derivation ownership)

Created `const BombEngine = { ... }` in `index.html`, placed immediately before
the old helper declarations. The engine became the **single owner of all Bomb
derivation logic**:

- `isEnabled(state)` — master switch (Bomb Challenge Mode on/off)
- `fullClockMs(state)` — canonical fresh-full-clock value (`bombDuration * 1000`)
- `extensionMs(state)` — lifeline extension duration (`lifelineExtraMinutes * 60 * 1000`)
- `maxClockMs(state)` — hard ceiling = `max(fullClockMs, extensionMs)`
- `baseMs(state)` — read-clamped persisted day-clock (`bombRemainingMs`), capped
  to `[0, maxClockMs]` at read time so corrupt storage can never exceed the ceiling
- `isTicking(state)` — true when the bomb is actively counting down
- `liveRemainingMs(state)` — live ms remaining, clamped and future-timestamp
  guarded (Task 3 hardening)
- `clampToFull(ms, state)` — upper safety clamp to `maxClockMs`

The five legacy helpers (`bombModeOn`, `bombBaseMs`, `bombIsTicking`,
`bombLiveRemainingMs`, `lifelineExtensionMs`) were converted to **thin delegates**
so all ~25 existing call sites kept working unchanged.

#### Task 2 — State transition ownership (mini-steps 2.1–2.6)

Every Bomb state transition was moved into the engine. Behavior was preserved
exactly at every step; thin delegates were left where the old function names were
called by other code:

| Mini-step | Engine method | What it owns |
|-----------|--------------|--------------|
| 2.1 | `arm(state)` | Install idle bomb timer using persisted day-clock |
| 2.2 | `armForNewDay(state)` | Full-clock refill + re-arm at day-rollover |
| 2.3 | `explode(state)` / `explodePaused(state)` | Both explosion paths (heart-consume, streak-reset, fresh-clock, breach flag) |
| 2.4 | `consumeLifeline(state)` | Heart-decrement + clock-extension (single mutation; both explosion paths call it) |
| 2.5 | `onSessionPause` / `bankPausedElapsed` / `onSessionResume` | Bomb freeze/tick/bank around productive session pause/resume/stop |
| 2.6 | `grantEarnedLifelines(state, totalStudySec)` / `resetDailyLifelines(state)` | All lifeline award logic and daily reset |

Key rules honoured throughout Task 2:
- Bomb Mode only ever affects the **Hour Bomb Streak** (`hourStreakSec`,
  `streakPomodoros`). It never touches study hours, goals, campaign progress,
  timeline study duration, history, Telegram reports, or Pomodoro math.
- The only two events that earn a fresh full clock are: **day-rollover** (via
  `armForNewDay`) and **explosion** (via `explode`/`explodePaused`). All other
  clock writes are extensions or banking of time already burned.
- `state.hearts = 1` and `state.dailyLifelinesEarned = 0` at day-rollover now
  route through `BombEngine.resetDailyLifelines()` — single owner.

#### Task 3 — Countdown fix + upper safety clamp (mini-steps 3.1–3.2)

- **3.1 — Upper safety clamp wired in:** `maxClockMs()` established as the true
  ceiling (larger of a fresh full clock or one extension, so legitimate lifeline
  extensions are never clipped). `clampToFull()` updated to clamp to this ceiling.
  Applied at two sites: `baseMs` read (defence-in-depth against corrupted storage)
  and `bankPausedElapsed` computed write (prevents multiple-banking inflation).

- **3.2 — Live countdown hardened:** `liveRemainingMs` now upper-clamps both the
  armed-bomb path (`msRemaining(at)`) and the paused-session path, and the paused
  elapsed is floored at 0 with a future-timestamp guard. Closes the accumulation
  leak across: future timestamps, background recovery, sleep, lifeline extension,
  multiple banking, persistence, and app restart — investigation item #7.

#### Task 4 — Lifeline duplicate-award race fix (mini-steps 4.1–4.2)

- **4.1 — Atomic single grant authority:** `grantEarnedLifelines` hardened with:
  - Synchronous re-entrancy latch (`_granting`) — no path can interleave a second
    award mid-computation.
  - Monotonic clamp of `dailyLifelinesEarned` into `[0, maxBonus]` — a stale or
    corrupted counter can neither regress nor re-open an already-passed threshold.
  - Hard `heartsMaxPerDay` cap on every hearts write.

- **4.2 — Serialized persist:** Added `persistAward(state)` which sequences award
  writes onto a single promise tail (`_persistTail`). The 1s-tick live-award path
  previously called `saveState(STATE)` without awaiting it; a concurrent save (from
  another path) could resolve after it and clobber the freshly-incremented
  `dailyLifelinesEarned` in storage, causing re-award on background-restore / app
  restart. The live-award caller now routes through `persistAward` instead.

### Architecture constraint (non-negotiable — every future session must honour)

**Controlled centralization rules (decided by owner):**
1. `BombEngine` is being built as the ONE owner of all Bomb logic.
2. Every time a Bomb-related function is modified, move its ownership fully into
   the engine — never leave duplicated ownership.
3. Do NOT perform a full rewrite. Migrate incrementally.
4. Do NOT touch: the timer engine, Study Time architecture, Pomodoro logic,
   goals, campaign, history, Telegram reports.
5. Bomb Mode only protects the **Hour Bomb Streak**. It must never affect study
   hours, goal/campaign progress, timeline study duration, history, or reports.

### Files modified this session (v3.3)

- `app/src/main/assets/app/index.html` — only file changed (HTML-only release).
  Added `BombEngine` object; delegated 5 legacy helpers; migrated all 6 transition
  categories; added Task 3 clamp + Task 4 race-hardening.
- `app/build.gradle` — versionCode 10 → 11, versionName "3.2" → "3.3"
- `app/src/main/java/com/goaltracker/shell/JsBridge.kt` — getShellVersion "3.2" → "3.3"
- `HANDOVER-v3.3.md` — this file (new)
- `RELEASE_NOTES.md` — v3.3 entry added at top

No Kotlin logic changed. Shell ZIP structure is unchanged from v3.2. The bundled
`index.html` is byte-for-byte identical to the standalone HTML (Rule 7).

### Verification

All inline `<script>` blocks in `index.html` pass `node --check` (run after every
mini-step). This is an Android/Kotlin project and cannot run in the v0 web
preview; build the APK via GitHub Actions to verify on-device.

---

## REMAINING WORK (nothing below is implemented)

The dependency-first implementation order decided by the owner:

### Task 5 — Configurable Daily Free Lifelines (0–3, hard max 3)

**What was decided:**
- A new Settings field: `settings.dailyFreeLifelines` (integer, range 0–3,
  hard maximum 3, default 1).
- The UI enforces the 0–3 range with a stepper/input in the Bomb settings
  sub-page (`data-sub="subBomb"`).
- `BombEngine.resetDailyLifelines()` must respect this setting: on a new day,
  `state.hearts` is set to `settings.dailyFreeLifelines` (not always 1).
- `DEFAULT_SETTINGS` must include `dailyFreeLifelines: 1` so factory reset is safe.
- The hard max of 3 is enforced both in the UI (stepper cannot exceed 3) and in
  `resetDailyLifelines` (`Math.min(settings.dailyFreeLifelines, 3)`).

**Implementation plan (mini-steps):**
- 5.1 — Add `dailyFreeLifelines` to `DEFAULT_SETTINGS` with value `1`.
- 5.2 — Update `BombEngine.resetDailyLifelines()` to use
  `Math.min(st.settings.dailyFreeLifelines ?? 1, 3)` instead of the hardcoded `1`.
- 5.3 — Add a stepper UI control in the Bomb settings sub-page (0–3 range,
  capped at 3) wired to `state.settings.dailyFreeLifelines`.

### Task 6 — Explosion Card → Restart → Password → Resume flow redesign

**What was decided:**
- The current explosion flow (`pendingBreachScreen → auto-popup on render`) must
  be replaced with a deliberate multi-step card flow that mirrors the
  Time-Inconsistency card (the owner's reference).
- New flow: **Explosion Card → Restart button → Password verify → Resume**
- `BOMB_LOCK` (already a Freeze reason) must persist until the user verifies via
  the password step. It must auto-clear on a new day (so a user who comes back
  tomorrow isn't permanently locked).
- Extra-lifeline progress (the `dailyLifelinesEarned` bonus count) is reset to 0
  on an explosion — only free daily lifelines survive; earned bonuses are forfeit.
- `BombEngine.explode()` and `BombEngine.explodePaused()` should set `BOMB_LOCK`
  via the Freeze Manager and queue the new card instead of setting
  `pendingBreachScreen`.

**Implementation plan (mini-steps):**
- 6.1 — Reset `dailyLifelinesEarned` to 0 inside `consumeLifeline` on the
  "no hearts left" branch (explosion resets extra progress).
- 6.2 — Replace `pendingBreachScreen = true` in `explode`/`explodePaused` with
  `FreezeManager.add('BOMB_LOCK')` + a new `pendingExplosionCard = true` flag.
- 6.3 — Build the Explosion Card UI (mirrors Time-Inconsistency card style).
- 6.4 — Wire Restart → Password verify → auto-clear `BOMB_LOCK` + resume flow.
- 6.5 — Add auto-clear of `BOMB_LOCK` at day-rollover in `recoverState`.

### Task 7 — Timeline / Statistics / UI / Animations

**What was decided:**
- Timeline must NOT paint bomb-active gaps as inactivity when Bomb Mode is ON.
  A Bomb gap should appear as its own `{category:'bomb'}` block (already exists
  in the gap-filler) — but must be visually distinct from inactivity, not merged.
- "Exploded Today: N" stat line shown on the home screen only while the session
  is paused (not during a live session).
- Bomb Mode master toggle must kill ALL subsystems from one place (arm, ticking,
  lifelines, UI, notifications, freeze reasons, timeline blocks) — currently the
  toggle disables individual behaviours but there is no single shutdown path.
- UI animation polish for the breach/explosion card.

**Implementation plan (mini-steps):** to be broken down at start of Task 7.

---

## PACKAGING RULES (read PROJECT_STRUCTURE.md for full detail)

Next version after this release = **v3.4**
- `versionCode` → 12, `versionName` → "3.4"
- `JsBridge.getShellVersion()` → "3.4"
- ZIP name: `GoalTracker-v3.4-(<YYYY-MM-DD-HH-MM>).zip`
- The standalone HTML must be byte-for-byte identical to the bundled `index.html`
- Add v3.4 entry at TOP of `RELEASE_NOTES.md`
- Never leave old copies inside the payload

---

## "CONTINUE FROM HERE" PROMPT (paste into a fresh v0 chat)

```
You are continuing the Goal Tracker Android project (v3.3).
You have ZERO prior context. Read HANDOVER-v3.3.md FIRST — it is the ground truth.
Also read PROJECT_STRUCTURE.md before packaging anything.

WORKFLOW RULE (mandatory): After EVERY mini-step, STOP and ask:
  "Mini-step X.Y done. Continue to X.Z, or stop and prepare ZIP + HANDOVER?"
Never auto-continue. The owner decides every next step.

Project = one real deliverable:
  app/src/main/assets/app/index.html  (7,034+ line single-file HTML app, bundled into Android WebView)
  Standalone copy: goal-tracker-v3.3-(...).html (must be byte-for-byte identical to the above)

The Bomb Mode Engine (BombEngine object) was introduced in v3.3 as a controlled
centralization of all Bomb logic. Tasks 1 through 4.2 are complete. See HANDOVER-v3.3.md
for the exact architecture, what each engine method owns, and the non-negotiable constraints.

Remaining work in order (full detail in HANDOVER-v3.3.md "REMAINING WORK"):
  Task 5: Configurable Daily Free Lifelines (0–3, hard max 3) — 3 mini-steps
  Task 6: Explosion Card → Restart → Password → Resume flow redesign — 5 mini-steps
  Task 7: Timeline / Statistics / UI / Animations

Hard rules:
- BombEngine is the ONLY owner of Bomb logic. Never duplicate Bomb ownership outside it.
- Bomb Mode only affects the Hour Bomb Streak (hourStreakSec, streakPomodoros).
  Never touch study hours, goals, campaign, history, Telegram, or Pomodoro math.
- Never modify the timer engine or Study Time architecture.
- The only two events that give a fresh full clock: day-rollover and explosion.
- node --check must pass after every edit to index.html.
- This is an Android/Kotlin project; it cannot run in the v0 preview.
  Build via GitHub Actions to test on-device.

Start with Task 5. Break it into mini-steps. Stop after each mini-step and ask.
```

---

END OF HANDOVER v3.3
