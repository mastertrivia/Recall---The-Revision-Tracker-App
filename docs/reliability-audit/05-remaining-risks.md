# E. Remaining Risks and Limitations

## Unresolved / unverified conditions
1. **Device-level notification behavior (verify first):** all notification tests run against a
   recording JS bridge mock in jsdom. Actual posting, `SCHEDULE_EXACT_ALARM` behavior on
   Android 12+, permission-denied flows, and OEM battery killers (Xiaomi/Huawei/Samsung
   standby) need a physical device. Kotlin compiles and `AlarmContractTest` passes, but no
   on-device alarm has fired in this audit.
2. **Real WebView process death:** simulated via fresh page loads over seeded IndexedDB (T6/T7).
   Android's actual WebView/ART process kill and `BootReceiver` reboot path are exercised only
   by code reading, not execution.
3. **`assembleRelease` not built:** debug build + both unit-test variants succeeded; a
   release-signed artifact was not produced (no authorization to publish, no signing run).
4. **Long-running clock drift:** tests use a controlled fake clock; multi-day real-device drift,
   timezone changes mid-session, and DST transitions were not executed (boundary math itself is
   timestamp-based, which is the correct pattern).
5. **Concurrent-tab/multi-window:** IndexedDB single-connection assumptions hold for the
   single-WebView container, but a second foreground instance is not a tested scenario.
6. **Legacy user data migration:** GT-06 recovery cleanup of phantom bombs runs once on first
   launch of the fixed build. Tested against seeded legacy states in T15, but not against a
   real production backup dump from the user's device.
7. **`tests/debug1.js` / `debug2.js`** are investigation scratch files kept for repro
   transparency; they are not part of the pass/fail suite.

## Android-imposed limitations (inherent, not defects)
- Exact alarms can be revoked by the OS (battery optimization, app update, OEM policy); the
  app's recovery catch-up is the safety net — a missed alarm surfaces at next launch rather
  than as a real-time notification. This is existing designed behavior, preserved.
- A frozen/suspended WebView cannot tick; all correctness while dead depends on wall-clock
  timestamps + native alarms + recovery replay (architecture report §5). This is the designed
  division of labor and is now covered by T6/T7/T8.

## Changes that would benefit from user confirmation
- GT-06 behavior change: with Bomb Mode OFF the app now goes cleanly IDLE on stop/completion
  (previously installed an expired bomb). This matches your own `saveSettings` toggle-off rule,
  but the *feel* of stop-with-bomb-off on a real device is worth a quick manual check.
- GT-05: fewer `cancelAlarm` calls while idle — invisible by design; confirm no alarm is
  missed after a reload (N4 covers the logic).
- The T5 lock-freeze display (pre-reset streak shown until password restart) was **kept as
  intended**; if you actually want the bar zeroed at lock time, that would be a business-rule
  change and was deliberately not made.

## Verification status summary
| Claim | Status |
|---|---|
| JS regression suites green | **Verified** (106/106, 23/23, smoke exit 0) |
| Kotlin unit tests green | **Verified** (4/4 debug + release) |
| Debug APK compiles | **Verified** (`BUILD SUCCESSFUL`, artifact on disk) |
| APK runs on a device | **Not verified** — no device/emulator in this environment |
| Notification/alarm on-device delivery | **Not verified** — mock-level only |
| No business rules changed | **Verified by diff review** (only `index.html`, 18 hunks, all corrective) |
