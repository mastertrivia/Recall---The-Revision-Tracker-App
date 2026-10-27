# Goal Tracker — Android Shell v3.9 — ENGINEERING HANDOVER

> **This is the authoritative handover for v3.9.**
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

## v3.9 — Mark Activity As Completed Early (Today's activity View/Info action)

**Shell/native version = 3.9** (`JsBridge.getShellVersion()` -> `"3.9"`,
`app/build.gradle` versionCode 16 / versionName "3.9"). This release is an
HTML + documentation release; the Kotlin change is limited to the version
string bump.

### What was done this session

All the work is on the **Tasks screen** (the Today action affordance) plus a new
confirmation popup and a History tag. No timer/engine/goal/campaign math was
changed.

1. **Pencil → circular View/Info icon once an activity is started.**
   In the Today task-row renderer, when `isTaskStarted(t)` is true the edit
   pencil is replaced by a new circular View/Info icon (`data-earlyinfo`).
   Not-yet-started activities keep the pencil (`data-edit`). The existing
   `lockTodayEditing` setting still short-circuits FIRST and hides the action
   entirely, so it wins over both the pencil and the new icon.

2. **Confirmation popup replaces the old "editing not allowed" toast.**
   Tapping the View/Info icon calls `openEarlyCompleteDialog(id)` which opens
   `#earlyCompleteDialog` — built from the same shared typed-confirm dialog shell
   used by Reset / Restart / Time-Inconsistency. It shows: activity name, subject
   (Study shows the chosen subject; other categories show the category label),
   progress (`consumed / target` via `fmtHMu`), remaining time to release, and a
   "What will happen" list. Confirmation requires typing `DONE` (the confirm
   button enables live on input).

3. **Confirm = mark Completed + trim duration to consumed (release remainder).**
   On confirm the task is marked `completionStatus='completed'` with a
   `completedAt` timestamp. `task.duration` is trimmed down to the consumed time
   so ONLY the unfinished remainder is discarded and released back into the daily
   budget (available for creating another activity). Consumed study time, History,
   Goal Progress, Campaign Progress and Today's Study Progress are all preserved.

4. **"Finished Early" History tag (Study only).**
   New per-task bookkeeping fields (backfilled in `ensureTaskProgressFields`):
   `finishedEarly` (bool), `plannedDuration` (original target), `earlyConsumedSec`
   (consumed at completion). In History → Study Task Progress, completed Study
   tasks with `finishedEarly` show a `⚡ Finished Early` chip with
   `earlyConsumedSec / plannedDuration`. Restarting a task clears all three fields
   for the fresh cycle.

### Files changed

- `app/src/main/assets/app/index.html` (bundled) and the byte-identical
  standalone `goal-tracker-v3.9-(2026-07-15-08-36).html`:
  - Added the `INFO` icon constant.
  - Today task-row action logic: `lockToday` check moved first; started activities
    now render the `data-earlyinfo` View/Info icon instead of a locked pencil.
  - Task-list click handler: `data-earlyinfo` opens `openEarlyCompleteDialog`.
  - `ensureTaskProgressFields`: backfills `finishedEarly` / `plannedDuration` /
    `earlyConsumedSec`.
  - New `#earlyCompleteDialog` markup + `openEarlyCompleteDialog`, input-gate,
    cancel/confirm handlers, and overlay-dismiss wiring.
  - History Study Task Progress renderer: appends the `⚡ Finished Early` tag.
  - Restart-task path: clears the three early-complete fields.
- `app/build.gradle` — versionCode 16, versionName "3.9".
- `JsBridge.kt` — `getShellVersion()` → `"3.9"`.
- `RELEASE_NOTES.md` — v3.9 entry added at the top.

### Not changed / do NOT touch

- All timer/engine, Pomodoro, Bomb, study-time, goal, campaign, history and
  Telegram math.
- The Goal Section On Time / Delayed indicator and Projected Finish logic (v3.8).
- The Today's Progress → Finishing By engine (the Pure-Study-Time spine).

### Known follow-up (optional)

- `getEffectiveConsumedSec(task)` is used to display progress in the popup while
  the trim writes `task.consumedSec`; both reflect the same consumed value today,
  but a future refactor could funnel them through one helper.
