package com.goaltracker.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-wide alarm player. Both in-app bridge playback and the background
 * scheduled-alarm service share this so only one clip rings at a time, the
 * selected slot is resolved natively, and duration is enforced on a Handler
 * that does not depend on the WebView.
 */
object AlarmPlayer {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var ringing = false

    @Volatile
    private var generation: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    fun play(context: Context, slot: String, durationMs: Long): Boolean {
        val appCtx = context.applicationContext
        val normalized = AlarmContract.normalizeSlot(slot)
        val clamped = AlarmContract.clampDuration(durationMs)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return playOnMain(appCtx, normalized, clamped)
        }
        var result = false
        val latch = CountDownLatch(1)
        handler.post {
            result = playOnMain(appCtx, normalized, clamped)
            latch.countDown()
        }
        return try {
            latch.await(3, TimeUnit.SECONDS)
            result
        } catch (_: Exception) {
            result
        }
    }

    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseOnMain()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            releaseOnMain()
            latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    fun isRinging(): Boolean {
        if (ringing) return true
        return try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    fun stopIfRinging(): Boolean {
        if (!isRinging()) return false
        stop()
        return true
    }

    private fun playOnMain(ctx: Context, slot: String, durationMs: Long): Boolean {
        val gen = ++generation
        releaseOnMain()
        val started = startPlayer(ctx, slot, durationMs, gen)
        if (!started) {
            return startBuiltinFallback(ctx, slot, durationMs, gen)
        }
        return true
    }

    private fun startPlayer(ctx: Context, slot: String, durationMs: Long, gen: Long): Boolean {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            try {
                p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Exception) {
            }

            val file = namedSoundFile(ctx, slot)
            if (file.exists()) {
                p.setDataSource(file.absolutePath)
            } else {
                val sel = SoundLibrary.getSelection(ctx, slot)
                if (sel.type == SoundLibrary.TYPE_DEVICE) {
                    p.setDataSource(ctx, Uri.parse(sel.value))
                } else {
                    val b = SoundLibrary.builtinByKey(sel.value)
                        ?: SoundLibrary.BUILTINS.first()
                    ctx.resources.openRawResourceFd(b.resId).use { afd ->
                        p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                }
            }

            p.isLooping = true
            p.setOnErrorListener { _, _, _ ->
                if (generation == gen) releaseOnMain()
                true
            }
            p.prepare()
            p.start()
            player = p
            ringing = true
            armStop(durationMs, gen)
            true
        } catch (e: Exception) {
            try {
                p.release()
            } catch (_: Exception) {
            }
            ErrorLog.log(ctx, "AlarmPlayer.play", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun startBuiltinFallback(ctx: Context, slot: String, durationMs: Long, gen: Long): Boolean {
        return try {
            val b = SoundLibrary.builtinByKey(
                if (slot == SoundLibrary.SLOT_BOMB) "classic" else "chime"
            ) ?: SoundLibrary.BUILTINS.first()
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            try {
                p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Exception) {
            }
            ctx.resources.openRawResourceFd(b.resId).use { afd ->
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            p.isLooping = true
            p.setOnErrorListener { _, _, _ ->
                if (generation == gen) releaseOnMain()
                true
            }
            p.prepare()
            p.start()
            player = p
            ringing = true
            armStop(durationMs, gen)
            true
        } catch (e: Exception) {
            ErrorLog.log(ctx, "AlarmPlayer.fallback", e.message ?: "failed")
            false
        }
    }

    private fun armStop(durationMs: Long, gen: Long) {
        stopRunnable?.let { handler.removeCallbacks(it) }
        val stop = Runnable {
            if (generation == gen) releaseOnMain()
        }
        stopRunnable = stop
        handler.postDelayed(stop, durationMs)
    }

    private fun releaseOnMain() {
        ringing = false
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun namedSoundFile(ctx: Context, slot: String): File {
        val dir = File(ctx.filesDir, "sound").apply { mkdirs() }
        val key = slot.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)
        return File(dir, key)
    }
}
