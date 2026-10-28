package com.goaltracker.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * The app's only audible output: two short action effects, one shot each.
 *
 *  - `R.raw.topic_completed` when a revision is marked completed
 *  - `R.raw.topic_overdue`    when a revision is marked overdue
 *
 * Contract (user requirement): the clip plays for exactly its own duration and
 * NEVER loops. Three independent guarantees enforce that:
 *   1. `isLooping = false`,
 *   2. release on natural completion,
 *   3. a hard Handler cap slightly above the clip length, so even a player
 *      that never reports completion is stopped and freed (no endless ring,
 *      no leaked MediaPlayer if the activity dies).
 *
 * The old alarm/ringtone path (`AlarmPlayer`) is silent; reminders arrive as a
 * plain notification. Everything that makes noise goes through here.
 */
object ActionSound {

    /** Clips here are ~1 s; the cap only exists as a runaway guard. */
    const val MAX_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var player: MediaPlayer? = null

    private var guard: Runnable? = null

    /** Plays [resId] once. Returns false only when playback could not start. */
    fun play(context: Context, resId: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            handler.post(object : Runnable {
                override fun run() {
                    play(app, resId)
                }
            })
            return true
        }
        stop()
        val appCtx = context.applicationContext
        return try {
            val p = MediaPlayer()
            appCtx.resources.openRawResourceFd(resId).use { afd ->
                if (afd == null) throw IllegalStateException("missing sound resource")
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.isLooping = false
            p.setOnCompletionListener { release() }
            p.setOnErrorListener { _, _, _ -> release(); true }
            p.prepare()
            p.start()
            player = p
            val stop = Runnable { stop() }
            guard = stop
            handler.postDelayed(stop, MAX_MS)
            true
        } catch (e: Exception) {
            ErrorLog.log(appCtx, "ActionSound", e.message ?: "unknown")
            false
        }
    }

    /** Stops and releases any playing effect. Safe to call at any time. */
    fun stop() = release()

    fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    private fun release() {
        guard?.let { handler.removeCallbacks(it) }
        guard = null
        val p = player
        player = null
        if (p != null) {
            try { p.stop() } catch (_: Exception) {
            }
            try { p.release() } catch (_: Exception) {
            }
        }
    }
}