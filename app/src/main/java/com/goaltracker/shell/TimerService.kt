package com.goaltracker.shell

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Foreground service that owns the persistent "timer running" notification.
 *
 * v2.4 - NATIVE TIMER ENGINE (single source of truth for the notification):
 * Previously the notification only changed when JS pushed a new pre-rendered
 * string every second. When the WebView was backgrounded, Android throttled
 * that JS heartbeat after ~30s and the notification froze. Now the service
 * ticks itself:
 *
 *   - JS sends a STATE (title + text template + the raw countdown numbers)
 *     ONLY when something changes (start / pause / resume / stop / subject
 *     or bomb change) via setTimerState -> ACTION_SYNC.
 *   - The service converts each countdown into an absolute end time on the
 *     monotonic clock (SystemClock.elapsedRealtime) and re-renders the
 *     notification on its OWN Handler loop, independent of the WebView.
 *   - State is persisted, the service is START_STICKY, and it restores itself
 *     if Android restarts it - so the countdown never freezes and never
 *     depends on JavaScript or WebView activity.
 *
 * Legacy ACTION_START/UPDATE/PAUSE/STOP (static text, no self-ticking) are
 * kept so any older/imported HTML that uses the old contract still works.
 */
class TimerService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_UPDATE = "update"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"
        const val ACTION_SYNC = "sync"

        // v2.5 - TEMPORARY notification-presentation feedback for notification
        // button taps. These let the shade react instantly (freeze the numbers,
        // flip the Pause/Resume button, keep a ticking bomb alive) even while
        // the WebView is dead in the background. They are PRESENTATION ONLY and
        // are NOT authoritative: HTML remains the single source of truth and
        // overwrites this the moment it is alive again (andSyncNotification).
        const val ACTION_NOTIF_PAUSE = "notif_pause"
        const val ACTION_NOTIF_RESUME = "notif_resume"
        const val ACTION_NOTIF_STOP = "notif_stop"
        const val NOTIFICATION_ID = 9001

        private const val PREFS = "timer_engine"
        private const val KEY_STATE = "state"
        // Re-render cadence. 500ms keeps the seconds digit visually accurate
        // without meaningful battery cost (setOnlyAlertOnce avoids buzzing).
        private const val TICK_MS = 500L
    }

    /** One dynamic value inside the template, e.g. the MM:SS clock. */
    private data class Clock(
        val id: String,
        val fmt: String,        // "mmss" | "left" | "bomb"
        val ticking: Boolean,   // false => frozen (paused)
        val endElapsed: Long,   // deadline on the monotonic clock (same boot)
        val endWall: Long,      // deadline on wall clock (survives reboot)
        val frozenMs: Long      // remaining shown while not ticking
    )

    /** The full notification state the engine renders every tick. */
    private data class State(
        val running: Boolean,
        val paused: Boolean,
        val title: String,
        val template: String,
        val clocks: List<Clock>,
        // INERT presentation data supplied by HTML (the single source of truth).
        // Lets background Pause feedback show the bomb line with a value HTML
        // already computed. NOT authoritative and involves NO native bomb math.
        // bombPreviewMs < 0 means "no bomb line available".
        val bombPreviewMs: Long = -1L,
        val bombPreviewLine: String = "",
        // Running identity supplied by HTML (green Study / blue other).
        // It lets native notification actions render the correct yellow
        // paused status and restore the original colour on resume.
        val statusEmoji: String = "🔵"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var state: State? = null
    private var lastText: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            renderTick()
            if (state?.running == true) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_SYNC -> {
                    val json = intent.getStringExtra("json")
                    if (json != null) applyState(parseState(json))
                }
                ACTION_START, ACTION_UPDATE -> {
                    // Legacy static path (no self-ticking numbers available).
                    applyState(legacyState(intent, paused = false))
                }
                ACTION_PAUSE -> {
                    applyState(legacyState(intent, paused = true))
                }
                ACTION_STOP -> stopEngine()
                // Temporary presentation feedback (see constants above). Never
                // authoritative — HTML reconciles/overwrites on resume.
                ACTION_NOTIF_PAUSE -> nativePauseResumeFeedback(paused = true)
                ACTION_NOTIF_RESUME -> nativePauseResumeFeedback(paused = false)
                ACTION_NOTIF_STOP -> nativeStopFeedback()
                null -> {
                    // START_STICKY restart after being killed: restore ourselves.
                    val saved = loadState()
                    if (saved != null && saved.running) applyState(saved)
                    else stopEngine()
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(this, "TimerService", e.message ?: "unknown")
            // Never crash the FGS; keep whatever we last rendered.
            if (state == null) stopEngine()
        }
        return START_STICKY
    }

    /** Keep ticking even if the user swipes the app from Recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally do NOT stop. The countdown must survive task removal.
        // START_STICKY + persisted state also cover a later process kill.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ---------------- Engine core ----------------

    private fun applyState(s: State?) {
        if (s == null || !s.running) {
            stopEngine()
            return
        }
        state = s
        saveState(s)
        lastText = null // force a re-render
        // Enter/refresh the foreground notification immediately...
        startForeground(NOTIFICATION_ID, buildNotification(s, renderText(s)))
        lastText = renderText(s)
        // ...then keep it ticking on our own clock.
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun renderTick() {
        val s = state ?: return
        if (!s.running) return
        val text = renderText(s)
        if (text == lastText) return // nothing changed; avoid needless notify
        lastText = text
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(s, text))
        } catch (e: Exception) {
            ErrorLog.log(this, "TimerService.tick", e.message ?: "notify failed")
        }
    }

    private fun stopEngine() {
        state = null
        lastText = null
        handler.removeCallbacks(ticker)
        clearState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- Temporary presentation feedback (NOT authoritative) ----------------
    //
    // These give the notification an INSTANT reaction to a button tap while the
    // WebView is backgrounded/dead. They only mutate the already-pushed render
    // state (freeze/unfreeze clocks, flip the paused flag, keep a bomb alive).
    // They deliberately contain NO timer/bomb business logic: the same tap is
    // ALSO delivered to the HTML, which owns all timer semantics and re-pushes
    // the authoritative state via setTimerState the instant it is alive again,
    // overwriting whatever we show here.

    /**
     * A "bomb-only" notification is the one built by the HTML's andBuildBombState:
     * a single ticking bomb clock and no session clock. HTML ignores Pause/
     * Resume/Stop for the bomb, so we mirror that here to stay consistent.
     */
    private fun isBombOnly(s: State): Boolean {
        val hasBomb = s.clocks.any { it.fmt == "bomb" }
        val hasNonBomb = s.clocks.any { it.fmt != "bomb" }
        return hasBomb && !hasNonBomb
    }

    private fun nativePauseResumeFeedback(paused: Boolean) {
        val s = state ?: loadState() ?: return
        if (!s.running) return
        // Mirror HTML: the bomb notification does not pause/resume.
        if (isBombOnly(s)) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        // Freeze (pause) or unfreeze (resume) the SESSION clocks only. The bomb
        // line is managed separately below from the inert data HTML gave us — we
        // never compute bomb timing here.
        val sessionClocks = s.clocks.filter { it.fmt != "bomb" }.map { c ->
            if (paused) {
                c.copy(ticking = false, frozenMs = remainingMs(c))
            } else {
                val rem = if (c.ticking) remainingMs(c) else c.frozenMs
                c.copy(ticking = true, endElapsed = nowElapsed + rem, endWall = nowWall + rem, frozenMs = rem)
            }
        }

        // Base (session-only) template with any previously-appended bomb line
        // stripped, so pause/resume toggles are idempotent.
        val baseTemplate =
            if (s.bombPreviewLine.isNotEmpty()) s.template.replace(s.bombPreviewLine, "") else s.template

        val newClocks: List<Clock>
        val newTemplate: String
        if (paused && s.bombPreviewMs >= 0 && s.bombPreviewLine.isNotEmpty()) {
            // Show the bomb line using the remaining time HTML already computed.
            // The bomb ticks through a paused session (HTML's rule), so render
            // it ticking down from that value. This is a plain countdown render,
            // not bomb business logic.
            val bombClock = Clock(
                id = "c2",
                fmt = "bomb",
                ticking = true,
                endElapsed = nowElapsed + s.bombPreviewMs,
                endWall = nowWall + s.bombPreviewMs,
                frozenMs = s.bombPreviewMs
            )
            newClocks = sessionClocks + bombClock
            newTemplate = baseTemplate + s.bombPreviewLine
        } else {
            // Resume (or no bomb data): show the session only.
            newClocks = sessionClocks
            newTemplate = baseTemplate
        }
        applyState(s.copy(
            paused = paused,
            title = withStatusEmoji(s.title, if (paused) "🟡" else s.statusEmoji),
            template = newTemplate,
            clocks = newClocks
        ))
    }

    private fun withStatusEmoji(title: String, emoji: String): String =
        title.replaceFirst(Regex("^[🟢🔵🟡]\\s*"), "$emoji ")

    private fun nativeStopFeedback() {
        val s = state ?: loadState()
        // Mirror HTML, which ignores "stop" for the bomb: keep the bomb-only
        // notification alive (re-render current state) instead of destroying it.
        if (s != null && s.running && isBombOnly(s)) {
            applyState(s)
        } else {
            stopEngine()
        }
    }

    /** Substitute every {id} token with its live, formatted remaining time. */
    private fun renderText(s: State): String {
        var out = s.template
        for (c in s.clocks) {
            out = out.replace("{" + c.id + "}", formatClock(c))
        }
        return out
    }

    private fun remainingMs(c: Clock): Long {
        if (!c.ticking) return c.frozenMs.coerceAtLeast(0)
        // Detect a reboot: the monotonic clock resets, so compare boot markers.
        val nowElapsed = SystemClock.elapsedRealtime()
        val remainElapsed = c.endElapsed - nowElapsed
        // If the monotonic deadline looks sane, trust it (immune to clock edits).
        if (remainElapsed in -1000..(48L * 3600_000L)) {
            return remainElapsed.coerceAtLeast(0)
        }
        // Otherwise (likely a reboot) fall back to the wall-clock deadline.
        val remainWall = c.endWall - System.currentTimeMillis()
        return remainWall.coerceAtLeast(0)
    }

    private fun formatClock(c: Clock): String {
        val sec = Math.round(remainingMs(c) / 1000.0).toInt().coerceAtLeast(0)
        return when (c.fmt) {
            "left" -> fmtLeft(sec)
            "bomb" -> fmtBomb(sec)
            else -> fmtMmss(sec)
        }
    }

    // --- Formatters replicated EXACTLY from the HTML (fmtClock/andFmtLeft/andFmtBomb) ---

    /** fmtClock: zero-padded MM:SS. */
    private fun fmtMmss(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return pad2(m) + ":" + pad2(s)
    }

    /** andFmtLeft: "Hh MMm" when >= 1h else "Mm" (minutes rounded up). */
    private fun fmtLeft(sec: Int): String {
        val m = Math.ceil(sec / 60.0).toInt()
        val h = m / 60
        return if (h > 0) h.toString() + "h " + pad2(m % 60) + "m" else (m % 60).toString() + "m"
    }

    /** andFmtBomb: HH:MM:SS only when >= 1h, otherwise MM:SS. */
    private fun fmtBomb(sec: Int): String {
        val h = sec / 3600
        val m = (sec / 60) % 60
        val s = sec % 60
        return (if (h > 0) pad2(h) + ":" else "") + pad2(m) + ":" + pad2(s)
    }

    private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

    // ---------------- Parsing / legacy ----------------

    private fun parseState(json: String): State {
        val o = JSONObject(json)
        val running = o.optBoolean("running", true)
        val paused = o.optBoolean("paused", false)
        val title = o.optString("title", "Timer running")
        val template = o.optString("template", "")
        val clocks = ArrayList<Clock>()
        val arr = o.optJSONArray("clocks")
        if (arr != null) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val remainMs = c.optDouble("remainMs", 0.0).toLong().coerceAtLeast(0)
                val ticking = c.optBoolean("ticking", true)
                clocks.add(
                    Clock(
                        id = c.optString("id", "c$i"),
                        fmt = c.optString("fmt", "mmss"),
                        ticking = ticking,
                        endElapsed = nowElapsed + remainMs,
                        endWall = nowWall + remainMs,
                        frozenMs = remainMs
                    )
                )
            }
        }
        // Inert bomb-preview presentation data (optional). Used only by the
        // background Pause feedback; never for any live rendering or timing.
        var bombPreviewMs = -1L
        var bombPreviewLine = ""
        val bp = o.optJSONObject("bombPreview")
        if (bp != null) {
            bombPreviewMs = bp.optDouble("remainMs", -1.0).toLong()
            bombPreviewLine = bp.optString("line", "")
        }
        return State(running, paused, title, template, clocks, bombPreviewMs, bombPreviewLine,
            o.optString("statusEmoji", "🔵"))
    }

    private fun legacyState(intent: Intent, paused: Boolean): State {
        val title = intent.getStringExtra("title") ?: "Timer running"
        val text = intent.getStringExtra("text") ?: ""
        // No numbers in the legacy contract, so there are no ticking clocks;
        // the text is shown verbatim (old behavior preserved for compatibility).
        return State(running = true, paused = paused, title = title, template = text, clocks = emptyList())
    }

    // ---------------- Persistence ----------------

    private fun saveState(s: State) {
        try {
            val o = JSONObject()
            o.put("running", s.running)
            o.put("paused", s.paused)
            o.put("title", s.title)
            o.put("template", s.template)
            val arr = org.json.JSONArray()
            for (c in s.clocks) {
                val co = JSONObject()
                co.put("id", c.id)
                co.put("fmt", c.fmt)
                co.put("ticking", c.ticking)
                co.put("endElapsed", c.endElapsed)
                co.put("endWall", c.endWall)
                co.put("frozenMs", c.frozenMs)
                arr.put(co)
            }
            o.put("clocks", arr)
            o.put("bombPreviewMs", s.bombPreviewMs)
            o.put("bombPreviewLine", s.bombPreviewLine)
            o.put("statusEmoji", s.statusEmoji)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadState(): State? {
        return try {
            val json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, null) ?: return null
            val o = JSONObject(json)
            val clocks = ArrayList<Clock>()
            val arr = o.optJSONArray("clocks")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    clocks.add(
                        Clock(
                            id = c.optString("id", "c$i"),
                            fmt = c.optString("fmt", "mmss"),
                            ticking = c.optBoolean("ticking", true),
                            endElapsed = c.optLong("endElapsed", 0),
                            endWall = c.optLong("endWall", 0),
                            frozenMs = c.optLong("frozenMs", 0)
                        )
                    )
                }
            }
            State(
                running = o.optBoolean("running", false),
                paused = o.optBoolean("paused", false),
                title = o.optString("title", "Timer running"),
                template = o.optString("template", ""),
                clocks = clocks,
                bombPreviewMs = o.optLong("bombPreviewMs", -1L),
                bombPreviewLine = o.optString("bombPreviewLine", ""),
                statusEmoji = o.optString("statusEmoji", "🔵")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun clearState() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_STATE).apply()
        } catch (_: Exception) {
        }
    }

    // ---------------- Notification ----------------

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            Intent(this, TimerActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(s: State, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, ShellApp.CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(s.title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (s.paused) {
            builder.addAction(0, "Resume", actionIntent(TimerActionReceiver.ACTION_RESUME))
        } else {
            builder.addAction(0, "Pause", actionIntent(TimerActionReceiver.ACTION_PAUSE))
        }
        builder.addAction(0, "Stop", actionIntent(TimerActionReceiver.ACTION_STOP))
        return builder.build()
    }
}
