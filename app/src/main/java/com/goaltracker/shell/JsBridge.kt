package com.goaltracker.shell

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The JavaScript bridge. Everything your HTML app can ask Android to do
 * lives here. In JS these appear as: window.AndroidBridge.methodName(...)
 *
 * IMPORTANT: these methods run on a background thread. Anything that
 * touches the UI (toasts are safe, WebView is not) must hop to the
 * main thread via activity.runOnUiThread { }.
 *
 * STABILITY RULE (v2): every method body is wrapped in try/catch.
 * A bridge call may fail and report itself in the error log, but it
 * must NEVER crash the app.
 */
class JsBridge(private val activity: MainActivity) {

    private val dataDir: File
        get() = File(activity.filesDir, "data").apply { mkdirs() }

    private val soundFile: File
        get() = File(File(activity.filesDir, "sound").apply { mkdirs() }, "completion")

    // v2.2: multiple named sounds ("completion", "bombwarn"). The legacy
    // single-sound methods keep working and map to the "completion" name.
    private fun namedSoundFile(name: String): File =
        File(File(activity.filesDir, "sound").apply { mkdirs() }, safeKey(name))

    // Short one-shot preview player used by the native sound chooser.
    // Named/completion/scheduled playback goes through AlarmPlayer.
    private var previewPlayer: MediaPlayer? = null

    private companion object {
        // How long an in-app alarm/completion sound keeps ringing before it
        // auto-stops. Short source clips are looped to fill this window.
        const val RING_DURATION_MS = 60_000L

        // v4.12: app-owned Display Size. The wrapper only persists the level;
        // the HTML applies it before first paint via the viewport meta (see the
        // "Display Size engine" script in index.html). Levels are "1".."9"
        // (8 = the app's normal size, default = "5", ~8% per step). Values are
        // whitelisted because the bridge input is untrusted JavaScript.
        const val DISPLAY_SCALE_KEY = "displayScale"
        const val DISPLAY_SCALE_DEFAULT = "5"
        val DISPLAY_SCALE_LEVELS = (1..9).map { it.toString() }.toSet()
        // v4.12 scheme marker: a stored level WITHOUT this marker was written by
        // v4.11's 1–10 numbering (6 = normal) and is migrated once at read time
        // (shift +2, old 8/9/10 clamp to "9") so every stored choice keeps its
        // exact visual size. With the marker present the value is used as-is.
        const val DISPLAY_SCALE_SCHEME_KEY = "displayScaleScheme"
        const val DISPLAY_SCALE_SCHEME = "2"
        // Values written by the earlier 4-size test build — old-scheme numbers,
        // migrated through the same +2 shift at read time.
        val DISPLAY_SCALE_LEGACY = mapOf(
            "xsmall" to "4", "small" to "6", "medium" to "7", "large" to "8"
        )
    }

    /** Keys become filenames, so strip anything unsafe. */
    private fun safeKey(key: String): String =
        key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)

    /** Runs [block]; on any failure logs to ErrorLog and returns [fallback]. */
    private fun <T> safe(where: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        ErrorLog.log(activity, where, e.message ?: e.javaClass.simpleName)
        fallback
    }

    /**
     * Runs [block] on the UI thread with the try/catch INSIDE the posted
     * runnable. A plain `safe { runOnUiThread { ... } }` only protects the
     * posting - the lambda body executes later, outside the try/catch, so
     * an exception there would crash the app and break the "bridge never
     * crashes" guarantee. This helper closes that gap.
     */
    private fun safeUi(where: String, block: () -> Unit) {
        try {
            activity.runOnUiThread {
                try {
                    block()
                } catch (e: Exception) {
                    ErrorLog.log(activity, where, e.message ?: e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(activity, where, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- Toasts ----------------

    @JavascriptInterface
    fun showToast(message: String) {
        if (!Flags.get(activity, "nativeToastsEnabled", true)) return
        safeUi("showToast") {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun showToastLong(message: String) {
        if (!Flags.get(activity, "nativeToastsEnabled", true)) return
        safeUi("showToastLong") {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- External links ----------------

    /**
     * Telegram app packages tried first so a t.me / tg:// link opens in
     * Telegram itself instead of a browser tab. The manifest declares them in
     * <queries>, otherwise Android 11+ package visibility would hide them and
     * resolveActivity() would always answer "not installed".
     */
    private val telegramPackages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "org.telegram.messenger.web"
    )

    /**
     * Hand an external link (https://t.me/..., tg://, any http(s) URL) to Android.
     *
     * The WebView must never navigate its own frame away from the app: a
     * main-frame jump to t.me used to be intercepted by shouldOverrideUrlLoading,
     * which reloaded the app back to Home and flashed "File was not opened as a
     * page". The link is opened from the Activity instead - the Telegram app first
     * when it can handle the link, otherwise a system chooser / browser fallback.
     *
     * @return true when Android was asked to open the link.
     */
    @JavascriptInterface
    fun openExternalUrl(url: String): Boolean = safe("openExternalUrl", false) {
        val uri = try {
            Uri.parse(url.trim())
        } catch (e: Exception) {
            null
        } ?: return@safe false

        // Only well-known external schemes - never file://, content:// or
        // javascript:, which would hand internal data to another app.
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https" && scheme != "tg") return@safe false

        val wantsTelegram = scheme == "tg" || (uri.host ?: "").endsWith("t.me", ignoreCase = true)
        if (wantsTelegram) {
            for (pkg in telegramPackages) {
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                val resolvable = try {
                    appIntent.resolveActivity(activity.packageManager) != null
                } catch (e: Exception) {
                    false
                }
                if (!resolvable) continue
                try {
                    activity.startActivity(appIntent)
                    return@safe true
                } catch (e: Exception) {
                    ErrorLog.log(activity, "JsBridge.openExternalUrl", e.message ?: pkg)
                    // fall through to the chooser instead of giving up
                }
            }
        }

        // Fallback: any browser / app that can open the link. createChooser
        // always resolves to something when a browser exists, and shows the
        // system app picker when several apps claim the URL.
        activity.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open with")
        )
        true
    }

    // ---------------- Data: JSON files in internal storage ----------------
    // This is your real persistence layer. IndexedDB stays as a working
    // cache in JS; every save should ALSO call saveData().

    @JavascriptInterface
    fun saveData(key: String, json: String): Boolean = safe("saveData", false) {
        // v4.11: temp-then-rename so a concurrent reader (backup export) can
        // never observe a half-written file.
        val dest = File(dataDir, safeKey(key) + ".json")
        val tmp = File(dataDir, safeKey(key) + ".json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(dest)) {
            dest.writeText(json)
            tmp.delete()
        }
        true
    }

    /** Returns the stored JSON string, or "" if the key does not exist. */
    @JavascriptInterface
    fun loadData(key: String): String = safe("loadData", "") {
        val f = File(dataDir, safeKey(key) + ".json")
        if (f.exists()) f.readText() else ""
    }

    @JavascriptInterface
    fun deleteData(key: String): Boolean = safe("deleteData", false) {
        File(dataDir, safeKey(key) + ".json").delete()
    }

    /** Returns a JSON array of all stored keys, e.g. ["goals","settings"] */
    @JavascriptInterface
    fun listDataKeys(): String = safe("listDataKeys", "[]") {
        val keys = dataDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
        JSONArray(keys).toString()
    }

    // ---------------- Backup ----------------

    /**
     * Opens the native typed-confirmation dialog, then the "save file" picker;
     * writes ALL data keys into one JSON file (v4.11: shows the confirmation
     * first — the old top-bar menu behavior, now triggered from the HTML).
     */
    @JavascriptInterface
    fun exportBackup() {
        safeUi("exportBackup") { activity.showExportBackupFlow() }
    }

    /**
     * Folder every export is written into, as chosen in Settings.
     * @return the folder label (e.g. "Download/Recall"), or "" for the default
     * flow (file picker, then Downloads).
     */
    @JavascriptInterface
    fun getExportFolder(): String = safe("getExportFolder", "") {
        activity.exportFolderLabel() ?: ""
    }

    /** Opens the system folder picker so the user can change the target. */
    @JavascriptInterface
    fun chooseExportFolder() {
        safeUi("chooseExportFolder") { activity.chooseExportFolder() }
    }

    /**
     * Writes a CSV straight to storage. A WebView Blob download is dropped on
     * the floor, so without this the "Export topics (.csv)" button produced no
     * file at all inside the wrapper.
     */
    @JavascriptInterface
    fun exportCsv(filename: String, content: String) {
        safeUi("exportCsv") {
            // Keep the .html/.csv extension intact: safeKey() would turn the dot
            // into an underscore, so the name is sanitised here instead.
            val clean = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('.').take(120).ifEmpty { "recall-topics.csv" }
            activity.launchExportCsv(clean, content)
        }
    }

    /** Native typed-confirmation dialog first, then the file picker (v4.11). */
    @JavascriptInterface
    fun importBackup() {
        safeUi("importBackup") { activity.showImportBackupFlow() }
    }

    // ---------------- App & Data menu (v4.11: replaced the top-bar menu) ----------------

    /** Reload the app HTML without deleting user data (native confirm first). */
    @JavascriptInterface
    fun refreshApp() {
        safeUi("refreshApp") { activity.showRefreshAppFlow() }
    }

    /** Install a new HTML/ZIP interface file (native typed confirm + staged import). */
    @JavascriptInterface
    fun importHtmlFile() {
        safeUi("importHtmlFile") { activity.showImportHtmlFlow() }
    }

    /** Revert a custom-installed HTML interface to the bundled original (typed confirm first). */
    @JavascriptInterface
    fun revertHtmlFile() {
        safeUi("revertHtmlFile") { activity.showRevertHtmlFlow() }
    }

    /** Export the CURRENTLY running index.html to a user-chosen file. */
    @JavascriptInterface
    fun exportCurrentHtml() {
        safeUi("exportCurrentHtml") { activity.showSaveCurrentHtmlFlow() }
    }

    /** Factory reset: deletes user data only; the current HTML file is preserved. */
    @JavascriptInterface
    fun factoryResetUserData() {
        safeUi("factoryResetUserData") { activity.showFactoryResetFlow() }
    }

    /** Native System Check dialog (full diagnostics + export as file). */
    @JavascriptInterface
    fun systemCheck() {
        safeUi("systemCheck") { activity.showSystemCheckFlow() }
    }

    // ---------------- Notifications ----------------

    /** Ask for notification permission (needed once on Android 13+). */
    @JavascriptInterface
    fun requestNotificationPermission() {
        safeUi("requestNotificationPermission") {
            activity.requestNotificationPermissionIfNeeded()
        }
    }

    /** Show a notification right now. Respects the "notificationsEnabled" flag. */
    @JavascriptInterface
    fun showNotification(id: Int, title: String, text: String) {
        safe("showNotification", Unit) {
            if (!Flags.get(activity, "notificationsEnabled")) return@safe
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, buildAlertNotification(title, text, persistent = false, withReviewActions = false))
        }
    }

    /**
     * v2.2: persistent (non-swipeable until tapped) notification, used for
     * the alert that must not be swiped away. Tapping it opens Recall and clears it.
     */
    @JavascriptInterface
    fun showPersistentNotification(id: Int, title: String, text: String) {
        safe("showPersistentNotification", Unit) {
            if (!Flags.get(activity, "notificationsEnabled")) return@safe
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, buildAlertNotification(title, text, persistent = true, withReviewActions = false))
        }
    }

    /**
     * Session-completed notification with [ADD] / [CANCEL] actions. ADD opens
     * the app (the HTML receives a "reviewAdd" timerAction event, which apps
     * without a review screen may ignore); CANCEL just dismisses.
     */
    @JavascriptInterface
    fun showReviewNotification(id: Int, title: String, text: String) {
        safe("showReviewNotification", Unit) {
            if (!Flags.get(activity, "notificationsEnabled")) return@safe
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, buildAlertNotification(title, text, persistent = false, withReviewActions = true, notifId = id))
        }
    }

    /** Cancel any notification previously shown through this bridge. */
    @JavascriptInterface
    fun cancelNotification(id: Int) {
        safe("cancelNotification", Unit) {
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        }
    }

    private fun buildAlertNotification(
        title: String,
        text: String,
        persistent: Boolean,
        withReviewActions: Boolean,
        notifId: Int = 0
    ): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            activity, 0,
            Intent(activity, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(activity, ShellApp.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            // BigTextStyle so multi-line bodies (Bomb explosion, reflection
            // prompt with emoji line) render in full.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
        if (persistent) {
            // Not dismissible by swipe; cleared when tapped (contentIntent
            // opens the app, then the HTML calls cancelNotification) or via
            // cancelNotification from JS.
            b.setOngoing(true)
        }
        if (withReviewActions) {
            val add = PendingIntent.getBroadcast(
                activity, notifId,
                Intent(activity, TimerActionReceiver::class.java)
                    .setAction(TimerActionReceiver.ACTION_REVIEW_ADD)
                    .putExtra("id", notifId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cancel = PendingIntent.getBroadcast(
                activity, notifId + 100000,
                Intent(activity, TimerActionReceiver::class.java)
                    .setAction(TimerActionReceiver.ACTION_DISMISS_ALARM)
                    .putExtra("id", notifId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "ADD", add)
            b.addAction(0, "CANCEL", cancel)
        }
        return b.build()
    }

    // ---------------- Scheduled alarms (work when app is closed) ----------------

    /**
     * Schedule a notification at an absolute time.
     * triggerAtMillis comes from JS as Date.now() + delayMs.
     * (Declared as Double because JS numbers arrive as doubles.)
     * Respects the "alarmsEnabled" flag.
     */
    @JavascriptInterface
    fun scheduleAlarm(id: Int, triggerAtMillis: Double, title: String, text: String) {
        safe("scheduleAlarm", Unit) {
            if (!Flags.get(activity, "alarmsEnabled")) return@safe
            AlarmScheduler.schedule(activity, id, triggerAtMillis.toLong(), title, text)
        }
    }

    /**
     * Schedule a notification at an absolute time and persist the sound slot
     * plus ring duration so a killed process still plays the correct clip
     * for the configured window. Duration is clamped to 1–60 seconds.
     */
    @JavascriptInterface
    fun scheduleAlarmWithSound(
        id: Int,
        triggerAtMillis: Double,
        title: String,
        text: String,
        soundSlot: String,
        durationMs: Double
    ) {
        safe("scheduleAlarmWithSound", Unit) {
            if (!Flags.get(activity, "alarmsEnabled")) return@safe
            AlarmScheduler.schedule(
                activity,
                id,
                triggerAtMillis.toLong(),
                title,
                text,
                soundSlot,
                durationMs.toLong()
            )
        }
    }

    /**
     * Stop currently playing scheduled/in-app alarm audio immediately.
     * Does not cancel future AlarmManager schedules.
     */
    @JavascriptInterface
    fun stopAlarmPlayback(): Boolean = safe("stopAlarmPlayback", false) {
        AlarmPlaybackService.stopPlayback(activity)
        releasePreviewPlayer()
        true
    }

    @JavascriptInterface
    fun cancelAlarm(id: Int) {
        safe("cancelAlarm", Unit) {
            AlarmScheduler.cancel(activity, id)
        }
    }

    @JavascriptInterface
    fun cancelAllAlarms() {
        safe("cancelAllAlarms", Unit) { AlarmScheduler.cancelAll(activity) }
    }

    // ---------------- Foreground timer service ----------------

    /** Respects the "timerNotificationEnabled" flag. */
    @JavascriptInterface
    fun startTimer(title: String, text: String) {
        safe("startTimer", Unit) {
            if (!Flags.get(activity, "timerNotificationEnabled")) return@safe
            val i = Intent(activity, TimerService::class.java).apply {
                action = TimerService.ACTION_START
                putExtra("title", title)
                putExtra("text", text)
            }
            activity.startForegroundService(i)
        }
    }

    @JavascriptInterface
    fun updateTimer(title: String, text: String) {
        safe("updateTimer", Unit) {
            val i = Intent(activity, TimerService::class.java).apply {
                action = TimerService.ACTION_UPDATE
                putExtra("title", title)
                putExtra("text", text)
            }
            activity.startService(i)
        }
    }

    /**
     * v2.4 - native timer engine (single source of truth for the notification).
     *
     * JS calls this ONLY when the notification state changes (start / pause /
     * resume / stop / subject or bomb change) - NOT every second. The service
     * then ticks the countdown on its own clock, so the notification keeps
     * counting even when the WebView's JS timers are throttled/suspended in
     * the background. This replaces the old per-second startTimer/updateTimer
     * heartbeat that froze after ~30s.
     *
     * json shape:
     * {
     *   "running": true, "paused": false,
     *   "title": "🟢 Study — Math",
     *   "template": "{c0} — 2/4 —— Left {c1}\n🔴 Bomb Ticking 💣 — {c2} remaining",
     *   "clocks": [
     *     {"id":"c0","fmt":"mmss","remainMs":1475000,"ticking":true},
     *     {"id":"c1","fmt":"left","remainMs":4320000,"ticking":true},
     *     {"id":"c2","fmt":"bomb","remainMs":275000,"ticking":true}
     *   ],
     *   // Optional INERT presentation data (v3.1). Lets background Pause
     *   // feedback show the bomb line using a value HTML already computed;
     *   // native does NO bomb math and HTML overwrites this on resume.
     *   "bombPreview": {"id":"c2","fmt":"bomb","remainMs":275000,"line":"\n🔴 Bomb Ticking 💣 — {c2} remaining"}
     * }
     * running:false stops the foreground service.
     */
    @JavascriptInterface
    fun setTimerState(json: String) {
        safe("setTimerState", Unit) {
            // Respect the same gate as the legacy path: if the user disabled the
            // timer notification, a running state must not start the service.
            val running = try { org.json.JSONObject(json).optBoolean("running", true) } catch (_: Exception) { true }
            if (running && !Flags.get(activity, "timerNotificationEnabled")) return@safe
            val i = Intent(activity, TimerService::class.java).apply {
                action = TimerService.ACTION_SYNC
                putExtra("json", json)
            }
            if (running) activity.startForegroundService(i) else activity.startService(i)
        }
    }

    /** Update the notification and mark it paused (shows Resume button). */
    @JavascriptInterface
    fun pauseTimer(title: String, text: String) {
        safe("pauseTimer", Unit) {
            val i = Intent(activity, TimerService::class.java).apply {
                action = TimerService.ACTION_PAUSE
                putExtra("title", title)
                putExtra("text", text)
            }
            activity.startService(i)
        }
    }

    @JavascriptInterface
    fun stopTimer() {
        safe("stopTimer", Unit) {
            val i = Intent(activity, TimerService::class.java).apply {
                action = TimerService.ACTION_STOP
            }
            activity.startService(i)
        }
    }

    // ---------------- Time safety ----------------
    // JS contract: at session start store {wallMs, monoMs, bootId} from
    // currentTimeInfo(). Elapsed = monotonic delta while bootId is unchanged
    // (immune to manual clock changes). If bootId changed, the phone
    // rebooted: fall back to a clamped wall-clock estimate.

    /** Milliseconds since boot (monotonic - manual clock changes cannot affect it). */
    @JavascriptInterface
    fun elapsedRealtime(): Double = safe("elapsedRealtime", 0.0) {
        SystemClock.elapsedRealtime().toDouble()
    }

    /** JSON: { "wallMs": ..., "monoMs": ..., "bootId": "..." } */
    @JavascriptInterface
    fun currentTimeInfo(): String = safe("currentTimeInfo", "{}") {
        JSONObject()
            .put("wallMs", System.currentTimeMillis())
            .put("monoMs", SystemClock.elapsedRealtime())
            .put("bootId", TimeSafety.bootId(activity))
            .toString()
    }

    // ---------------- Feature flags (HTML settings screen) ----------------

    @JavascriptInterface
    fun getFlag(key: String, defaultValue: Boolean): Boolean =
        safe("getFlag", defaultValue) { Flags.get(activity, key, defaultValue) }

    @JavascriptInterface
    fun setFlag(key: String, value: Boolean) {
        safe("setFlag", Unit) { Flags.set(activity, key, value) }
    }

    /** All flags as a JSON object string. */
    @JavascriptInterface
    fun getAllFlags(): String = safe("getAllFlags", "{}") { Flags.all(activity) }

    /**
     * String settings (reminder times such as "06:00").
     *
     * [setFlag] is Boolean-only: routing a time through it stores "true" and
     * the chosen hour silently never takes effect, so times get their own
     * channel (same Flags string storage the display scale already uses).
     */
    @JavascriptInterface
    fun getStringFlag(key: String, defaultValue: String): String =
        safe("getStringFlag", defaultValue) { Flags.getString(activity, key, defaultValue) }

    @JavascriptInterface
    fun setStringFlag(key: String, value: String) {
        safe("setStringFlag", Unit) {
            require(value.length <= 16) { "value too long" }
            Flags.setString(activity, key, value)
        }
    }

    // ---------------- Display scale (v4.12: app-owned UI size) ----------------
    // The HTML renders itself at this scale (viewport rewrite in its head); the
    // wrapper owns persistence only, so the choice survives HTML reloads and
    // app restarts even though the setting is managed from the HTML settings UI.
    // Known values: "1".."9" ("8" = normal, default "5"). Values stored by the
    // v4.11 1–10 scheme are migrated once, guarded by the scheme marker.

    @JavascriptInterface
    fun getDisplayScale(): String = safe("getDisplayScale", DISPLAY_SCALE_DEFAULT) {
        val raw = Flags.getString(activity, DISPLAY_SCALE_KEY, "").trim().lowercase()
        val scheme = Flags.getString(activity, DISPLAY_SCALE_SCHEME_KEY, "")
        if (scheme == DISPLAY_SCALE_SCHEME) {
            if (raw in DISPLAY_SCALE_LEVELS) raw else DISPLAY_SCALE_DEFAULT
        } else {
            // One-time migration from v4.11's 1–10 numbering (or the 4-size
            // build): +2 preserves the rendered size; old 8/9/10 clamp to "9".
            val oldLevel = DISPLAY_SCALE_LEGACY[raw] ?: raw
            val n = oldLevel.toIntOrNull()
            val migrated = if (n != null && n in 1..10) {
                minOf(9, n + 2).toString()
            } else {
                DISPLAY_SCALE_DEFAULT
            }
            if (raw.isNotEmpty()) Flags.setString(activity, DISPLAY_SCALE_KEY, migrated)
            Flags.setString(activity, DISPLAY_SCALE_SCHEME_KEY, DISPLAY_SCALE_SCHEME)
            migrated
        }
    }

    @JavascriptInterface
    fun setDisplayScale(size: String) {
        safe("setDisplayScale", Unit) {
            val v = size.trim().lowercase()
            if (v in DISPLAY_SCALE_LEVELS) {
                Flags.setString(activity, DISPLAY_SCALE_KEY, v)
                Flags.setString(activity, DISPLAY_SCALE_SCHEME_KEY, DISPLAY_SCALE_SCHEME)
            } else {
                ErrorLog.log(activity, "setDisplayScale", "rejected unknown value: $size")
            }
        }
    }

    // ---------------- Pending events ----------------
    // Call on page load AND on document visibilitychange. Reading drains
    // the queue, so each event is delivered exactly once.

    @JavascriptInterface
    fun getPendingEvents(): String =
        safe("getPendingEvents", "[]") { PendingEvents.drain(activity) }

    // ---------------- Error log / diagnostics ----------------

    @JavascriptInterface
    fun getErrorLog(): String = safe("getErrorLog", "[]") { ErrorLog.getAll(activity) }

    @JavascriptInterface
    fun clearErrorLog() {
        safe("clearErrorLog", Unit) { ErrorLog.clear(activity) }
    }

    /** System state as JSON, for the HTML settings/diagnostics screen. */
    @JavascriptInterface
    fun getDiagnostics(): String = safe("getDiagnostics", "{}") {
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE)
                as android.app.AlarmManager
        val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        val vp = activity.viewportSettingsJson()
        JSONObject()
            .put("shellVersion", getShellVersion())
            .put("androidRelease", Build.VERSION.RELEASE ?: "unknown")
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("notificationsAllowed", NotificationManagerCompat.from(activity).areNotificationsEnabled())
            .put("exactAlarmsAllowed", canExact)
            .put("dataKeyCount", JSONArray(listDataKeys()).length())
            .put("errorLogCount", ErrorLog.count(activity))
            .put("customSoundSet", soundFile.exists())
            .put("displayScale", getDisplayScale())
            .put("useWideViewPort", vp.optBoolean("useWideViewPort", false))
            .put("loadWithOverviewMode", vp.optBoolean("loadWithOverviewMode", false))
            .put("textZoom", vp.optInt("textZoom", -1))
            .put("fontScale", activity.resources.configuration.fontScale)
            .put("bootId", TimeSafety.bootId(activity))
            .put("flags", JSONObject(Flags.all(activity)))
            .toString()
    }

    // ---------------- Optional completion sound ----------------
    // One short sound file, base64-encoded from JS, max 1 MB decoded.
    // Playback failures ALWAYS fall back silently (return false) - the
    // channel's default notification sound remains the reliable path.

    @JavascriptInterface
    fun saveSoundFile(base64: String): Boolean = safe("saveSoundFile", false) {
        saveNamedSoundInternal("completion", base64)
    }

    @JavascriptInterface
    fun clearSoundFile(): Boolean = safe("clearSoundFile", false) {
        releasePlayer()
        !soundFile.exists() || soundFile.delete()
    }

    /**
     * Play the "topic completed" effect - one shot, exactly the clip's own
     * duration, never looping (v5.0; the old alarm/ringtone fallbacks and the
     * ringing duration are gone). Returns false only when playback failed.
     */
    @JavascriptInterface
    fun playCompletionSound(): Boolean = safe("playCompletionSound", false) {
        releasePreviewPlayer()
        ActionSound.play(activity, R.raw.topic_completed)
    }

    /** Play the "topic overdue" effect. Same one-shot contract as above. */
    @JavascriptInterface
    fun playOverdueSound(): Boolean = safe("playOverdueSound", false) {
        releasePreviewPlayer()
        ActionSound.play(activity, R.raw.topic_overdue)
    }

    @JavascriptInterface
    fun stopCompletionSound() {
        safe("stopCompletionSound", Unit) {
            ActionSound.stop()
            AlarmPlaybackService.stopPlayback(activity)
            releasePreviewPlayer()
        }
    }

    // ---- v2.2: named sounds (separate Pomodoro-completion / Bomb-warning) ----

    /** Save a named sound ("completion" or "bombwarn"), base64, max 1 MB. */
    @JavascriptInterface
    fun saveNamedSound(name: String, base64: String): Boolean = safe("saveNamedSound", false) {
        saveNamedSoundInternal(name, base64)
    }

    @JavascriptInterface
    fun clearNamedSound(name: String): Boolean = safe("clearNamedSound", false) {
        releasePlayer()
        val f = if (name == "completion") soundFile else namedSoundFile(name)
        !f.exists() || f.delete()
    }

    @JavascriptInterface
    fun hasNamedSound(name: String): Boolean = safe("hasNamedSound", false) {
        (if (name == "completion") soundFile else namedSoundFile(name)).exists()
    }

    /**
     * Play a named sound. Custom file first; if none is set, falls back to
     * the device's default notification ringtone (so the button is never a
     * dummy). Respects the "soundEnabled" flag. Returns false only when
     * Sound is off or every playback path failed.
     */
    @JavascriptInterface
    fun playNamedSound(name: String): Boolean = safe("playNamedSound", false) {
        playNamedSoundInternal(name)
    }

    /**
     * Plays a named alarm for its caller-selected window (1–60 seconds).
     * Keeping this stop timer native makes the chosen duration survive a
     * paused/throttled WebView after playback has started.
     */
    @JavascriptInterface
    fun playNamedSoundForDuration(name: String, durationMs: Long): Boolean = safe("playNamedSoundForDuration", false) {
        playNamedSoundInternal(name, durationMs.coerceIn(1_000L, 60_000L))
    }

    private fun saveNamedSoundInternal(name: String, base64: String): Boolean {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.size > 1_000_000) {
            ErrorLog.log(activity, "saveNamedSound", "rejected: file larger than 1 MB")
            return false
        }
        val f = if (name == "completion") soundFile else namedSoundFile(name)
        f.writeBytes(bytes)
        return true
    }

    /**
     * Resolve and play the sound for a slot ("completion" or "bombwarn").
     *
     * Resolution order (first hit wins):
     *   1. A user-uploaded custom file (legacy HTML upload path) if present.
     *   2. The native selection: a bundled res/raw sound, or a device Uri.
     *   3. The bundled default for the slot (guaranteed to exist).
     *
     * Every selected clip loops for [ringDurationMs], so the user-selected
     * duration means the same thing for short and long sounds alike.
     */
    private fun playNamedSoundInternal(name: String, ringDurationMs: Long = RING_DURATION_MS): Boolean {
        releasePreviewPlayer()
        // v5.0: no alarm/ringtone path remains. Named playback resolves to the
        // matching action clip and plays exactly once; the duration argument is
        // kept for API compatibility but no longer rings or loops anything.
        val res = if (name == "overdue") R.raw.topic_overdue else R.raw.topic_completed
        return ActionSound.play(activity, res)
    }

    // ---- v2.3: built-in sound library + selection (native-authoritative) ----

    /** JSON: the 10 bundled sounds + current per-slot selection. */
    @JavascriptInterface
    fun listBuiltinSounds(): String = safe("listBuiltinSounds", "{}") {
        SoundLibrary.toJson(activity)
    }

    /** Preview a bundled sound by key without changing the saved selection. */
    @JavascriptInterface
    fun previewBuiltinSound(key: String): Boolean = safe("previewBuiltinSound", false) {
        val b = SoundLibrary.builtinByKey(key) ?: return@safe false
        AlarmPlayer.stop()
        releasePreviewPlayer()
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        activity.resources.openRawResourceFd(b.resId).use { afd ->
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        p.isLooping = false
        p.setOnErrorListener { _, _, _ -> releasePreviewPlayer(); true }
        p.setOnCompletionListener { releasePreviewPlayer() }
        p.prepare()
        p.start()
        previewPlayer = p
        true
    }

    /**
     * Select a bundled sound for a slot. Clears any legacy uploaded file for
     * that slot so the built-in choice takes effect immediately.
     */
    @JavascriptInterface
    fun selectBuiltinSound(slot: String, key: String): Boolean =
        safe("selectBuiltinSound", false) {
            if (SoundLibrary.builtinByKey(key) == null) return@safe false
            val s = normalizeSlot(slot)
            clearSlotFile(s)
            SoundLibrary.setBuiltin(activity, s, key)
            true
        }

    /** Record a device-picked ringtone/alarm Uri as the selection for a slot. */
    @JavascriptInterface
    fun selectDeviceSound(slot: String, uri: String): Boolean =
        safe("selectDeviceSound", false) {
            val s = normalizeSlot(slot)
            clearSlotFile(s)
            SoundLibrary.setDevice(activity, s, uri)
            true
        }

    /**
     * Records which sound slot the user is about to edit. Injected click
     * listeners on the HTML sound inputs call this just before the native
     * chooser opens, so it targets the correct slot. Never throws.
     */
    @JavascriptInterface
    fun noteSoundSlot(slot: String) {
        safe("noteSoundSlot", Unit) {
            activity.runOnUiThread { activity.setPendingSoundSlot(slot) }
        }
    }

    private fun normalizeSlot(slot: String): String = when (slot) {
        SoundLibrary.SLOT_SESSION_END, SoundLibrary.SLOT_TASK_COMPLETE,
        SoundLibrary.SLOT_BOMB, SoundLibrary.SLOT_COMPLETION -> slot
        else -> SoundLibrary.SLOT_COMPLETION
    }

    private fun clearSlotFile(slot: String) {
        val f = if (slot == "completion") soundFile else namedSoundFile(slot)
        if (f.exists()) try { f.delete() } catch (_: Exception) {}
    }

    private fun releasePreviewPlayer() {
        try {
            previewPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            previewPlayer?.release()
        } catch (_: Exception) {
        }
        previewPlayer = null
    }

    private fun releasePlayer() {
        AlarmPlayer.stop()
        releasePreviewPlayer()
    }

    /**
     * Stops any currently ringing in-app sound immediately. Safe to call
     * even when nothing is playing. Lets the app cut the alarm short (e.g.
     * when the user acknowledges it) instead of waiting the full window.
     */
    @JavascriptInterface
    fun stopSound(): Boolean = safe("stopSound", false) {
        ActionSound.stop()
        AlarmPlaybackService.stopPlayback(activity)
        releasePreviewPlayer()
        true
    }

    /** True while native alarm or preview audio is producing sound. */
    fun isRinging(): Boolean {
        if (ActionSound.isPlaying()) return true
        if (AlarmPlayer.isRinging()) return true
        return try { previewPlayer?.isPlaying == true } catch (_: Exception) { false }
    }

    /**
     * Hardware volume-button silence: stop the current ring if one is active.
     * Returns true only when something was actually ringing, so the activity
     * can swallow the key without changing system volume.
     */
    fun stopIfRinging(): Boolean = safe("stopIfRinging", false) {
        val playing = isRinging()
        if (!playing) return@safe false
        ActionSound.stop()
        AlarmPlaybackService.stopPlayback(activity)
        releasePreviewPlayer()
        true
    }

    // ---------------- Misc ----------------

    @JavascriptInterface
    fun getShellVersion(): String = "4.12"

    // ---------------- Manual fallback ----------------

    /**
     * Returns the raw text content of manuals.js so the HTML can display
     * the Goal Section Manual even when the <script src="manuals.js"> tag
     * silently failed (e.g. after a single-HTML import without the side-car file).
     *
     * Strategy (in priority order):
     *  1. Read manuals.js from the live app directory (filesDir/app/manuals.js).
     *     This is the user's current version and is always preferred.
     *  2. Fall back to the copy bundled inside the APK's assets/ folder.
     *     This always exists and cannot be lost.
     *
     * The returned string is the raw JS source (the whole file). The HTML
     * extracts window.GOAL_SECTION_MANUAL from it via a controlled eval so
     * only the template-literal value is rendered — no arbitrary execution.
     *
     * Returns "" on any failure so the HTML can distinguish "bridge absent"
     * (AndroidBridge undefined) from "bridge returned nothing" (empty string).
     */
    @JavascriptInterface
    fun getManualText(): String = safe("getManualText", "") {
        // 1. Live app directory copy (preferred — always up to date).
        val liveFile = File(File(activity.filesDir, "app"), "manuals.js")
        if (liveFile.exists()) {
            val text = liveFile.readText()
            if (text.isNotBlank()) return@safe text
        }
        // 2. Bundled APK asset (guaranteed present).
        activity.assets.open("app/manuals.js").use { it.reader().readText() }
    }
}
