package com.goaltracker.shell

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Foreground service that plays a scheduled alarm for its configured duration
 * even when the WebView is frozen or the activity is dead. The notification
 * itself is silent; [AlarmPlayer] owns the audible output.
 */
class AlarmPlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.goaltracker.shell.ALARM_PLAY_START"
        const val ACTION_STOP = "com.goaltracker.shell.ALARM_PLAY_STOP"

        fun start(
            context: Context,
            id: Int,
            title: String,
            text: String,
            soundSlot: String,
            durationMs: Long
        ) {
            val i = Intent(context, AlarmPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmContract.EXTRA_ID, id)
                putExtra(AlarmContract.EXTRA_TITLE, title)
                putExtra(AlarmContract.EXTRA_TEXT, text)
                putExtra(AlarmContract.EXTRA_SOUND_SLOT, soundSlot)
                putExtra(AlarmContract.EXTRA_DURATION_MS, durationMs)
            }
            context.startForegroundService(i)
        }

        fun stopPlayback(context: Context) {
            try {
                context.stopService(Intent(context, AlarmPlaybackService::class.java))
            } catch (_: Exception) {
            }
            AlarmPlayer.stop()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: Long = 0L
    private var stopRunnable: Runnable? = null
    private var notificationId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    finishPlayback(detachNotification = true)
                    return START_NOT_STICKY
                }
                else -> {
                    val id = intent?.getIntExtra(AlarmContract.EXTRA_ID, 0) ?: 0
                    val title = intent?.getStringExtra(AlarmContract.EXTRA_TITLE) ?: "Reminder"
                    val text = intent?.getStringExtra(AlarmContract.EXTRA_TEXT) ?: ""
                    val slot = AlarmContract.normalizeSlot(
                        intent?.getStringExtra(AlarmContract.EXTRA_SOUND_SLOT)
                    )
                    val durationMs = AlarmContract.clampDuration(
                        intent?.getLongExtra(
                            AlarmContract.EXTRA_DURATION_MS,
                            AlarmContract.DEFAULT_DURATION_MS
                        ) ?: AlarmContract.DEFAULT_DURATION_MS
                    )
                    beginPlayback(id, title, text, slot, durationMs)
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(this, "AlarmPlaybackService", e.message ?: "unknown")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        AlarmPlayer.stop()
        super.onDestroy()
    }

    private fun beginPlayback(
        id: Int,
        title: String,
        text: String,
        slot: String,
        durationMs: Long
    ) {
        session += 1
        val mySession = session
        notificationId = id
        val notification = buildSilentNotification(id, title, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(id, notification)
        }
        AlarmPlayer.play(this, slot, durationMs)
        stopRunnable?.let { handler.removeCallbacks(it) }
        val stop = Runnable {
            if (session == mySession) finishPlayback(detachNotification = true)
        }
        stopRunnable = stop
        handler.postDelayed(stop, durationMs)
    }

    private fun finishPlayback(detachNotification: Boolean) {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        AlarmPlayer.stop()
        try {
            if (detachNotification) {
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun buildSilentNotification(id: Int, title: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            this, id,
            Intent(this, TimerActionReceiver::class.java)
                .setAction(TimerActionReceiver.ACTION_DISMISS_ALARM)
                .putExtra(AlarmContract.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ShellApp.CHANNEL_ALARM_SILENT)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true)
            .addAction(0, "Dismiss", dismiss)
            .build()
    }
}
