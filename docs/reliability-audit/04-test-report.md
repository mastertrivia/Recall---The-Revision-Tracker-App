# D. Test Report

All commands run on this workspace VM (Linux, Node v24.21.0, portable JDK, Android SDK 34).
Logs: `artifacts/final-smoke.log`, `artifacts/final-timer.log`, `artifacts/final-notif.log`,
`artifacts/gradle.log`.

## Baseline (before any application change)
- **Repository had no JS test suite** (0 pre-existing JS/HTML test files). One Kotlin test
  class existed: `AlarmContractTest` (4 tests).
- The audit harness was built during Phase 1; its first baseline runs mixed genuine defects
  with two harness-side artifacts (jsdom `close()` not closing IndexedDB connections →
  `deleteDatabase` deadlock; state leaking across tests). Both harness defects were fixed
  **before** classifying any application failure, so no finding rests on a harness artifact.
- **Kotlin sources were never modified**, so the build/test result below is identical for the
  baseline and post-fix Android side.

## Final regression run (after all fixes)

| Suite | Command | Expected | Actual | Result |
|---|---|---|---|---|
| Boot smoke | `node tests/smoke.js` | boots with no page errors; state/timers sane | `pageErrors: []` at boot and after clock advance | **PASS** (exit 0) |
| Timer engine | `node tests/timer.test.js` | 106 checks across T1–T17 | 106 pass, 0 fail | **PASS 106/106** |
| Notifications/bridge | `node tests/notification.test.js` | 23 checks across N1–N7 | 23 pass, 0 fail | **PASS 23/23** |
| Kotlin unit tests | `./gradlew --no-daemon test` | AlarmContractTest | debug 4/4, release 4/4, 0 failures/errors | **PASS** |
| Debug APK build | `./gradlew --no-daemon assembleDebug` | APK produced | `BUILD SUCCESSFUL in 1m 31s`, `app/build/outputs/apk/debug/app-debug.apk` (5.9 MB) | **PASS** |

### Timer suite coverage (T1–T17)
- **T1** boot/day rollover arms bomb with configured (non-default) wake hour — GT-01
- **T2** start / live tick / stop (Bomb ON)
- **T3** rapid repeated button presses
- **T4** bomb expiry: lifeline save, real explosion, password restart
- **T5** hour-streak + progress-bar consistency around explosion (the reported symptom) — 11 checks
- **T6** WebView reload while a session runs (restoration + no duplicate sessions)
- **T7** process death: completion + bomb deadlines replayed — GT-03 bank checks
- **T8** overnight: sleep freeze at boundary, wake rollover
- **T9** pause/resume/stop math (Bomb OFF where Pause is enabled) — GT-04
- **T10** restart after explosion does not revive stale state
- **T11** foreground natural completion banks Hour Streak / Daily Study — GT-03
- **T12** stop while PAUSED writes no duplicate row — GT-04
- **T15/T17** Bomb Mode OFF: no phantom bomb from stop/reload/completion — GT-06
- **T16** History idle anchor = stop moment — GT-07

### Notification suite coverage (N1–N7)
- N1 permission asked once, never re-asked
- N2 boot init: leftover-explosion notification cleared, ticking timer notification, session-end
  alarm scheduled at the true deadline (±3 s) with configured sound slot/duration
- N3 stop: session alarm cancelled, bomb alarm armed, **no idle cancel churn** (GT-05), bomb
  notification state
- N4 reload while running: alarm re-armed for the **original** deadline (±4 s)
- N5 pause/resume notification states and alarm re-arm
- N6 full stop clears the native timer notification
- N7 permission-denied event surfaces the settings hint

### Fix verification
Every GT finding has at least one dedicated failing-before/passing-after test (GT-01→T1,
GT-02→smoke `pageErrors`, GT-03→T7/T11, GT-04→T9/T12, GT-05→N3.4, GT-06→T15/T17, GT-07→T16).
Each fix was applied only after its repro test demonstrated the failure on unmodified code.

## Tests NOT executed (honest limits)
- **Physical-device:** notification display, exact-alarm permission flows, OEM battery
  optimization, `BootReceiver` after real reboot, alarm audio playback, WebView process death
  on-device. No emulator/device available in this environment.
- **Release-signed APK:** only `assembleDebug` was built (both release/debug *unit tests* ran;
  no `assembleRelease` signing).
- **Telegram network sends:** mocked/offline paths only (no credentials in test env).
- **Manual UI pass** of all screens (Goals/Thoughts/Settings/Import-Export) — code-read +
  indirect coverage only; no browser-level interaction test beyond the harness.

No test was skipped, weakened, or xfailed to make the suite pass.
