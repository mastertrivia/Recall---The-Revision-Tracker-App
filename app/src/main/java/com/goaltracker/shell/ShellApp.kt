package com.goaltracker.shell

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Application class. Creates the notification channels once at startup.
 * Channels are required for any notification on Android 8+.
 */
class ShellApp : Application() {

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_TIMER = "timer"
        // Legacy channel kept so old notifications still resolve; new scheduled
        // alarms use the silent channel because native playback owns the sound.
        const val CHANNEL_ALARM = "alarm_v2"
        const val CHANNEL_ALARM_SILENT = "alarm_silent_v1"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            try { nm.deleteNotificationChannel("alarm") } catch (_: Exception) {}

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Alerts & reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Notifications and scheduled reminders from the app" }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TIMER,
                    "Running timer",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shown while a timer is running in the background" }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALARM,
                    "Alarms (legacy)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Legacy alarm channel. New alarms use the silent channel."
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                }
            )

            // Silent, high-importance channel. Native AlarmPlayer owns audio, so
            // the notification must not play a second, looping Classic Alarm.
            // Channel sound is immutable after creation, so this is a new id.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALARM_SILENT,
                    "Alarms",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Scheduled alarms. Sound is played by the app, not this channel."
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    setSound(null, null)
                }
            )
        } catch (e: Exception) {
            ErrorLog.log(this, "ShellApp.onCreate", e.message ?: "unknown")
        }
    }
}
