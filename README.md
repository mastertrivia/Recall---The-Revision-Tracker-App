# Recall — Android wrapper

An Android shell (WebView app) that runs the **Recall revision studio** HTML
offline, as a real installable application.

- **App name:** Recall · **Package:** `com.mastertrivia.recall`
- **Bundled UI:** `app/src/main/assets/app/index.html` — the audited, hardened
  Recall HTML (`shell/Revision bests - hardened.html`) plus the shell
  integration (`shell/head.js`, `shell/body.js`, `shell/settings.html`),
  assembled by `node shell/inject.js`.
- **Launcher icon:** the workspace logo, rendered into every density under
  `app/src/main/res/mipmap-*` plus an adaptive icon for Android 8+.

## What the wrapper adds

| Capability | How |
|---|---|
| Data safety | HTML state is mirrored into the native store on every save; native export/import covers revision data, with a checksum + full-shape validation gate (`BackupValidator`) that runs **before** any stored file is touched |
| Display size | 9 levels, **Level 5 = true 100%** of the original Recall layout; the viewport meta is rewritten before first paint (see `recall-display` engine in the HTML head) |
| Reminders | Daily summary + overdue reminder, scheduled natively (`AlarmScheduler`), counts computed by the app's own revision logic |
| Settings | Injected Settings card (mirroring the wrapper's App & Data menu): refresh / install HTML / save HTML / revert / factory reset / system check |
| Backup files | Signed, checksummed JSON; a bad file can never overwrite good data |

## Tests

```bash
npm install        # jsdom harness dependencies
npm test           # tests/shell.test.js — 49 checks against the real bundle
```

Kotlin unit tests (`BackupValidatorTest`, `AlarmContractTest`) run in CI as a
separate job.

## Building the APK

This repo is built by GitHub Actions (`.github/workflows/build-apk.yml`):
**Actions → run “Build APK” → Artifacts → `recall-apk` → `app-release.apk`.**

`signing/release.keystore` is the permanent signing key: keep it, keep the
repository **private**, and never delete it — it is what lets every future APK
install as an update over the old one without losing data.
