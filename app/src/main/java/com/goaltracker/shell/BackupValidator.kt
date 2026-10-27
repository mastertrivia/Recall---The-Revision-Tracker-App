package com.goaltracker.shell

import org.json.JSONArray
import org.json.JSONObject

/**
 * v4.11: strict validation gate for backup import/export.
 *
 * The whole payload is checked BEFORE a single stored file is touched, so a
 * corrupt, truncated or foreign JSON file (e.g. a backup from a different
 * app) is rejected with a clear user-facing reason instead of overwriting
 * existing user data or crashing the HTML app.
 *
 * Pure Kotlin with no Android dependencies - fully unit-testable
 * (BackupValidatorTest).
 */
object BackupValidator {

    const val APP_ID = "recall-revision-studio"

    /** Key the Recall HTML mirrors its revision state into (see the HTML sync layer). */
    const val CORE_KEY = "recall_state"

    /** FNV-1a 32-bit, hex. Must match the JS implementation exactly. */
    fun fnv1a32(s: String): String {
        var h = -2128831035 // 0x811c9dc5 as signed Int
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return Integer.toHexString(h)
    }

    /** Canonical, order-independent hash input: sorted keys, key/value lines. */
    fun checksumPayload(data: Map<String, String>): String =
        data.keys.sorted().joinToString("\n") { "$it\n${data[it]}" }

    /**
     * Validates a whole backup document and returns the key -> file-content
     * map to persist. Throws [IllegalArgumentException] with a user-facing
     * reason when the file must be rejected. Never returns partial results.
     */
    fun validate(backupJson: String): LinkedHashMap<String, String> {
        val backup = try {
            JSONObject(backupJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("file is not valid JSON")
        }

        if (backup.optString("app") != APP_ID) {
            throw IllegalArgumentException("this is not a Recall backup")
        }

        val data = backup.optJSONObject("data")
            ?: throw IllegalArgumentException("backup has no data section")

        // 1) Collect every entry; all values must be text, and anything that
        //    looks like JSON must parse (catches truncated/garbled contents).
        val entries = LinkedHashMap<String, String>()
        for (key in data.keys()) {
            val v = data.opt(key)
                ?: throw IllegalArgumentException("field '$key' is empty")
            if (v !is String) {
                throw IllegalArgumentException("field '$key' is corrupt (not text)")
            }
            when {
                v.startsWith("{") || v.startsWith("[") -> try {
                    if (v.startsWith("[")) JSONArray(v) else JSONObject(v)
                } catch (e: Exception) {
                    throw IllegalArgumentException("field '$key' is damaged")
                }
            }
            entries[key] = v
        }

        // 2) The core Recall state must be structurally sound: topics, subjects
        //    and settings are the three pillars the revision app cannot run
        //    without, so a file missing any of them is rejected BEFORE a single
        //    stored file is touched.
        val innerText = entries[CORE_KEY]
            ?: throw IllegalArgumentException("backup has no Recall data")
        val inner = try {
            JSONObject(innerText)
        } catch (e: Exception) {
            throw IllegalArgumentException("backup data is damaged")
        }
        if (inner.opt("topics") !is JSONArray) {
            throw IllegalArgumentException("backup field 'topics' is missing or damaged")
        }
        if (inner.opt("subjects") !is JSONArray) {
            throw IllegalArgumentException("backup field 'subjects' is missing or damaged")
        }
        if (inner.optJSONObject("settings") == null) {
            throw IllegalArgumentException("backup has no settings")
        }

        // 3) Checksum (present on v4.11+ exports) - detects damage the shape
        //    checks alone would miss. Legacy files without one still pass the
        //    full shape gate above.
        val sum = backup.optString("checksum")
        if (sum.isNotEmpty()) {
            val expected = fnv1a32(checksumPayload(entries))
            if (!sum.equals(expected, ignoreCase = true)) {
                throw IllegalArgumentException("backup file is damaged (checksum mismatch)")
            }
        }

        return entries
    }
}
