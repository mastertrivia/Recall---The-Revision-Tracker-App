package com.goaltracker.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules all pending alarms after the phone reboots
 * (Android clears all alarms on reboot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                // New boot = monotonic clock reset. Refresh the boot id so
                // the HTML app's time-safety logic can detect the reboot.
                TimeSafety.refreshBootId(context)
                AlarmScheduler.rescheduleAll(context)
            } catch (e: Exception) {
                ErrorLog.log(context, "BootReceiver", e.message ?: "unknown")
            }
        }
    }
}
