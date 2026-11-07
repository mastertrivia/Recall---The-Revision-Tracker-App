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

    private companion object {
        /** Must match ALARM_DAILY in the HTML shell bridge. */
        const val DAILY_ALARM_ID = 9101
    }

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

            /* The daily reminder is the only alarm whose sentence is about "today".
               The stored sentence is a snapshot from the last app open, so it used to
               announce yesterday's counts when it fired after midnight. Recompute it
               from the mirrored state at fire time and keep the stored text only as a
               fallback for an unreadable/missing mirror. */
            val freshText = if (id == DAILY_ALARM_ID) {
                val summary = DailySummary.read(context)
                when {
                    summary == null -> text
                    summary.topics > 0 -> summary.text()
                    else -> "No revisions are due today."
                }
            } else text

            AlarmScheduler.markFired(context, id)

            if (!Flags.get(context, "alarmsEnabled")) return

            AlarmPlaybackService.start(context, id, title, freshText, soundSlot, durationMs)
        } catch (e: Exception) {
            ErrorLog.log(context, "AlarmReceiver", e.message ?: "unknown")
        } finally {
            pending.finish()
        }
    }
}
