package com.goaltracker.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Schedules exact alarms and remembers them in SharedPreferences so
 * BootReceiver can re-schedule everything after the phone reboots.
 *
 * Sound slot and duration are persisted with each alarm so a killed
 * process still rings the correct clip for the configured window.
 */
object AlarmScheduler {

    private const val PREFS = "scheduled_alarms"

    fun schedule(context: Context, id: Int, triggerAtMillis: Long, title: String, text: String) {
        schedule(
            context, id, triggerAtMillis, title, text,
            AlarmContract.DEFAULT_SLOT, AlarmContract.DEFAULT_DURATION_MS
        )
    }

    fun schedule(
        context: Context,
        id: Int,
        triggerAtMillis: Long,
        title: String,
        text: String,
        soundSlot: String,
        durationMs: Long
    ) {
        val slot = AlarmContract.normalizeSlot(soundSlot)
        val duration = AlarmContract.clampDuration(durationMs)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, id, title, text, slot, duration)

        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entry = JSONObject()
            .put("at", triggerAtMillis)
            .put("title", title)
            .put("text", text)
            .put("soundSlot", slot)
            .put("durationMs", duration)
        prefs.edit().putString(id.toString(), entry.toString()).apply()
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context, id, "", "", AlarmContract.DEFAULT_SLOT, AlarmContract.DEFAULT_DURATION_MS))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(id.toString()).apply()
    }

    /** Cancel every persisted OS alarm. Used by the master Alarms switch. */
    fun cancelAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.all.keys.forEach { key -> key.toIntOrNull()?.let { cancel(context, it) } }
    }

    /** Called from BootReceiver: re-schedule future alarms, drop past ones. */
    fun rescheduleAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            try {
                val obj = JSONObject(value as String)
                val at = obj.getLong("at")
                if (at > now) {
                    schedule(
                        context,
                        key.toInt(),
                        at,
                        obj.getString("title"),
                        obj.getString("text"),
                        obj.optString("soundSlot", AlarmContract.DEFAULT_SLOT),
                        obj.optLong("durationMs", AlarmContract.DEFAULT_DURATION_MS)
                    )
                } else {
                    editor.remove(key)
                }
            } catch (_: Exception) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /** Remove a fired alarm from the persisted list. */
    fun markFired(context: Context, id: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(id.toString()).apply()
    }

    private fun buildPendingIntent(
        context: Context,
        id: Int,
        title: String,
        text: String,
        soundSlot: String,
        durationMs: Long
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmContract.EXTRA_ID, id)
            putExtra(AlarmContract.EXTRA_TITLE, title)
            putExtra(AlarmContract.EXTRA_TEXT, text)
            putExtra(AlarmContract.EXTRA_SOUND_SLOT, soundSlot)
            putExtra(AlarmContract.EXTRA_DURATION_MS, durationMs)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
