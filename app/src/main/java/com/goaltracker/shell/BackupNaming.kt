package com.goaltracker.shell

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One naming rule for every native JSON export path.
 *
 * The HTML's browser export already uses `Recall_DD-MM-YYYY_Backup.json`; the native
 * paths used a fixed `recall-backup.json`, so several exports in one day were either
 * overwritten or silently renamed by the provider. Filename generation belongs here
 * (the native layer owns file creation) and must be shared by:
 *   - the user-chosen export folder
 *   - the JSON `CreateDocument` picker
 *   - the generic fallback picker (any MIME type)
 *   - the picker-free Downloads fallback
 *
 * Pure Kotlin so the format can be unit-tested without an Android device.
 */
object BackupNaming {

    /** Local calendar date, matching the HTML convention: 01-11-2026. */
    private const val PATTERN = "dd-MM-yyyy"

    /** `Recall_01-11-2026_Backup.json` */
    fun fileName(date: Date = Date()): String =
        "Recall_${SimpleDateFormat(PATTERN, Locale.US).format(date)}_Backup.json"

    /** `Recall_01-11-2026_Backup (2).json` - same-day duplicates never overwrite. */
    fun withSuffix(name: String, index: Int): String {
        if (index <= 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) + " ($index)" + name.substring(dot)
        } else {
            "$name ($index)"
        }
    }
}
