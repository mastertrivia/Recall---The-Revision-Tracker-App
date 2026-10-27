package com.goaltracker.shell

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives taps on notification action buttons (timer Pause/Resume/Stop and
 * alarm Dismiss). The shell holds NO timer logic: actions are forwarded to
 * the HTML app. If the activity is alive, delivery is instant; if Android
 * killed it, the event is queued and the HTML app picks it up on next load
 * via AndroidBridge.getPendingEvents().
 */
class TimerActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.goaltracker.shell.TIMER_PAUSE"
        const val ACTION_RESUME = "com.goaltracker.shell.TIMER_RESUME"
        const val ACTION_STOP = "com.goaltracker.shell.TIMER_STOP"
        const val ACTION_DISMISS_ALARM = "com.goaltracker.shell.ALARM_DISMISS"
        // v2.2: "ADD" button on the session-completed notification. Opens the
        // app and tells the HTML to jump straight to the Reflection popup.
        const val ACTION_REVIEW_ADD = "com.goaltracker.shell.REVIEW_ADD"

        /**
         * Set by MainActivity in onResume, cleared in onPause.
         * When present, events reach the WebView immediately.
         */
        @Volatile
        var liveDelivery: ((action: String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                // Each tap does TWO things:
                //  1. Ask the service for INSTANT, temporary notification
                //     feedback (freeze/flip the shade, keep a bomb alive) so
                //     the button feels responsive even when the WebView is dead.
                //  2. Deliver the semantic action to the HTML — the single
                //     source of truth — either live (Activity visible) or via
                //     the pending-event queue, replayed on resume. HTML always
                //     reconciles and overwrites the temporary native state.
                ACTION_PAUSE -> {
                    forwardToService(context, TimerService.ACTION_NOTIF_PAUSE)
                    deliver(context, "pause")
                }
                ACTION_RESUME -> {
                    forwardToService(context, TimerService.ACTION_NOTIF_RESUME)
                    deliver(context, "resume")
                }
                ACTION_STOP -> {
                    // Bomb-aware: the service keeps a ticking bomb notification
                    // alive instead of destroying it; otherwise it stops.
                    forwardToService(context, TimerService.ACTION_NOTIF_STOP)
                    deliver(context, "stop")
                }
                ACTION_DISMISS_ALARM -> {
                    val id = intent.getIntExtra("id", 0)
                    AlarmPlaybackService.stopPlayback(context)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(id)
                }
                ACTION_REVIEW_ADD -> {
                    // v2.2: dismiss the notification, deliver "reviewAdd" to
                    // the HTML (exactly-once via the pending queue when the
                    // WebView is dead), then open/foreground the app so the
                    // Reflection popup can be shown.
                    val id = intent.getIntExtra("id", 0)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(id)
                    deliver(context, "reviewAdd")
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "TimerActionReceiver", e.message ?: "unknown")
        }
    }

    /**
     * Sends a command to the (already-running, foreground) TimerService for
     * temporary notification-presentation feedback. This never creates timer
     * state on its own — it only nudges the notification the service already
     * owns. Safe from the background because the service is in the foreground.
     */
    private fun forwardToService(context: Context, serviceAction: String) {
        try {
            context.startService(
                Intent(context, TimerService::class.java).setAction(serviceAction)
            )
        } catch (e: Exception) {
            ErrorLog.log(context, "TimerActionReceiver.forward", e.message ?: "startService failed")
        }
    }

    private fun deliver(context: Context, action: String) {
        val live = liveDelivery
        if (live != null) {
            live(action)
        } else {
            PendingEvents.add(context, "timerAction", "\"$action\"")
        }
    }
}
