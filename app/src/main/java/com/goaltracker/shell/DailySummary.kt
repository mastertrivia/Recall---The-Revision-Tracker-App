package com.goaltracker.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Recomputes the daily revision reminder from the state the HTML mirrors into the
 * wrapper (`saveData("recall_state", …)` -> `files/data/recall_state.json`).
 *
 * Why this exists: the HTML hands a finished sentence to `scheduleAlarm()`, and the
 * wrapper stores that sentence inside the PendingIntent. The snapshot is therefore
 * taken at the last app open, so a reminder firing after midnight announced the
 * previous day's counts (9 topics / 2h 2m while Home showed 14 / 3h 2m).
 *
 * The rules below are deliberately the same ones Home uses:
 *  - skip blocked topics;
 *  - a revision counts when dueDate <= today, not completed, not cleared by a later
 *    completion;
 *  - one entry per topic id;
 *  - minutes = the topic's estimated duration, added once per counted topic.
 *
 * It is pure enough to unit-test on the JVM: [parse] takes the raw JSON string and a
 * study-day date, so no Android framework call is needed to verify the counting.
 */
object DailySummary {

    private const val STATE_DIR = "data"
    private const val STATE_FILE = "recall_state.json"

    data class Summary(val topics: Int, val minutes: Int) {
        /** Same wording the HTML produces, so both sources read identically. */
        fun text(): String =
            "$topics " + (if (topics == 1) "topic" else "topics") +
                " to revise today \u00b7 " + minsLong(minutes) + " estimated."
    }

    /** Null when the wrapper has no usable mirror - callers keep their stored text. */
    fun read(context: Context): Summary? = try {
        val file = File(File(context.filesDir, STATE_DIR), STATE_FILE)
        if (!file.exists()) null else parse(file.readText(), studyDayISO(dayStartAt(context)))
    } catch (e: Exception) {
        ErrorLog.log(context, "DailySummary", e.message ?: "unknown")
        null
    }

    /** The day-start boundary configured in the HTML Settings (0-5, clamped). */
    fun dayStartAt(context: Context): Int = try {
        val file = File(File(context.filesDir, STATE_DIR), STATE_FILE)
        if (!file.exists()) 0
        else JSONObject(file.readText()).optJSONObject("settings")?.optInt("dayStartAt", 0) ?: 0
    } catch (e: Exception) {
        0
    }

    /** Today's study day as yyyy-MM-dd, honouring the configured boundary hour. */
    fun studyDayISO(dayStartAt: Int): String {
        val boundary = dayStartAt.coerceIn(0, 5)
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < boundary) cal.add(Calendar.DAY_OF_YEAR, -1)
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun parse(json: String, today: String): Summary? = try {
        val root = JSONObject(json)
        val topics = root.optJSONArray("topics") ?: return null
        val seen = HashSet<String>()
        var count = 0
        var minutes = 0
        for (i in 0 until topics.length()) {
            val topic = topics.optJSONObject(i) ?: continue
            if (topic.optBoolean("blocked", false)) continue
            if (!hasPendingDue(topic.optJSONArray("revisions"), today)) continue
            val id = topic.optString("id")
            if (id.isEmpty() || !seen.add(id)) continue
            count++
            minutes += topic.optInt("duration", 0).coerceAtLeast(0)
        }
        Summary(count, minutes)
    } catch (e: Exception) {
        null
    }

    private fun hasPendingDue(revisions: JSONArray?, today: String): Boolean {
        if (revisions == null) return false
        for (j in 0 until revisions.length()) {
            val revision = revisions.optJSONObject(j) ?: continue
            val due = revision.optString("dueDate")
            if (due.isEmpty() || due > today) continue
            if (isSet(revision, "completedAt")) continue
            if (isSet(revision, "clearedByLaterRevisionAt")) continue
            return true
        }
        return false
    }

    private fun isSet(obj: JSONObject, key: String): Boolean =
        !obj.isNull(key) && obj.optString(key).isNotEmpty()

    private fun minsLong(mins: Int): String {
        val n = mins.coerceAtLeast(0)
        val h = n / 60
        val m = n % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
