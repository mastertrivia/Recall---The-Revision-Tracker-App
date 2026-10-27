package com.goaltracker.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queue for events that happened while the HTML app could not receive them
 * (e.g. a notification button tapped after Android killed the activity).
 *
 * JS contract: call AndroidBridge.getPendingEvents() on page load and on
 * document visibilitychange. Reading the queue empties it (drain semantics),
 * so events are delivered exactly once.
 */
object PendingEvents {

    private const val PREFS = "shell_pending_events"
    private const val KEY = "queue"
    private const val MAX_EVENTS = 100

    fun add(context: Context, name: String, payloadJson: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("payload", payloadJson)
                    .put("at", System.currentTimeMillis())
            )
            val trimmed = JSONArray()
            val start = maxOf(0, arr.length() - MAX_EVENTS)
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            prefs.edit().putString(KEY, trimmed.toString()).apply()
        } catch (e: Exception) {
            ErrorLog.log(context, "PendingEvents.add", e.message ?: "unknown")
        }
    }

    /** Returns all queued events as a JSON array string and clears the queue. */
    fun drain(context: Context): String = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.getString(KEY, "[]") ?: "[]"
        prefs.edit().remove(KEY).apply()
        all
    } catch (_: Exception) {
        "[]"
    }
}
