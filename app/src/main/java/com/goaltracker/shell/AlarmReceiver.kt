package com.goaltracker.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a scheduled alarm goes off (even if the app is closed).
 * Starts [AlarmPlaybackService], which posts a silent, actionable
 * notification and plays the selected slot for the configured duration.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            val id = intent.getIntExtra(AlarmContract.EXTRA_ID, 0)
            val title = intent.getStringExtra(AlarmContract.EXTRA_TITLE) ?: "Reminder"
            val text = intent.getStringExtra(AlarmContract.EXTRA_TEXT) ?: ""
            val soundSlot = AlarmContract.normalizeSlot(
                intent.getStringExtra(AlarmContract.EXTRA_SOUND_SLOT)
            )
            val durationMs = AlarmContract.clampDuration(
                intent.getLongExtra(AlarmContract.EXTRA_DURATION_MS, AlarmContract.DEFAULT_DURATION_MS)
            )

            AlarmScheduler.markFired(context, id)

            if (!Flags.get(context, "alarmsEnabled")) return

            AlarmPlaybackService.start(context, id, title, text, soundSlot, durationMs)
        } catch (e: Exception) {
            ErrorLog.log(context, "AlarmReceiver", e.message ?: "unknown")
        } finally {
            pending.finish()
        }
    }
}
