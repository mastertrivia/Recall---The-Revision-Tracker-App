package com.goaltracker.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny persistent error log (ring buffer of the last 50 entries) stored in
 * SharedPreferences. Every native failure is recorded here instead of
 * crashing, and the HTML app can read it via AndroidBridge.getErrorLog().
 *
 * RULE: logging itself must NEVER throw.
 */
object ErrorLog {

    private const val PREFS = "shell_error_log"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 50

    fun log(context: Context, where: String, message: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            arr.put(
                JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("where", where)
                    .put("message", message.take(500))
            )
            // Keep only the newest MAX_ENTRIES
            val trimmed = JSONArray()
            val start = maxOf(0, arr.length() - MAX_ENTRIES)
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            prefs.edit().putString(KEY, trimmed.toString()).apply()
        } catch (_: Exception) {
            // Intentionally swallowed: the error log must never be a crash source.
        }
    }

    /** Returns a JSON array string of log entries (oldest first). */
    fun getAll(context: Context): String = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
    } catch (_: Exception) {
        "[]"
    }

    fun count(context: Context): Int = try {
        JSONArray(getAll(context)).length()
    } catch (_: Exception) {
        0
    }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        } catch (_: Exception) {
        }
    }
}
