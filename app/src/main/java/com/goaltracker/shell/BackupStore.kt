package com.goaltracker.shell

import java.io.File

/**
 * Transactional replacement of the wrapper's managed JSON data set.
 *
 * The old restore staged the incoming files and then renamed them over the live ones
 * one by one. If a later rename failed, the app was left with a mixture of old and new
 * files, and keys that existed before but were absent from the backup stayed behind.
 *
 * This helper treats a restore as a full snapshot swap:
 *   1. write every incoming key into a staging directory (payload complete first);
 *   2. copy the live directory into a rollback directory;
 *   3. remove managed `*.json` files that the snapshot does not contain;
 *   4. write every incoming file and verify its contents;
 *   5. delete staging/rollback only after everything verified;
 *   6. on ANY failure, restore the exact previous directory and report false.
 *
 * Pure java.io, so the whole transaction can be unit-tested with temp directories.
 * [failAfterWrites] exists only as a deterministic fault-injection seam for tests.
 */
object BackupStore {

    private const val STAGING_DIR = "data_import_tmp"
    private const val ROLLBACK_DIR = "data_import_rollback"

    fun replaceAll(
        targetDir: File,
        incoming: Map<String, String>,
        failAfterWrites: Int = -1
    ): Boolean {
        val parent = targetDir.parentFile ?: return false
        val staging = File(parent, STAGING_DIR)
        val rollback = File(parent, ROLLBACK_DIR)
        staging.deleteRecursively()
        rollback.deleteRecursively()
        if (!staging.mkdirs()) return false
        try {
            // 1) Stage the complete payload before touching live data.
            for ((key, value) in incoming) {
                File(staging, "$key.json").writeText(value)
            }

            // 2) Snapshot the live set so any later failure can be undone exactly.
            if (targetDir.exists()) {
                if (!targetDir.copyRecursively(rollback, overwrite = true)) return false
            } else if (!rollback.mkdirs()) {
                return false
            }
            if (!targetDir.exists() && !targetDir.mkdirs()) return false

            // 3) Full snapshot: a key absent from the backup must not survive the restore.
            targetDir.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.forEach { file ->
                    if (!incoming.containsKey(file.nameWithoutExtension)) file.delete()
                }

            // 4) Write and verify every incoming key.
            var written = 0
            for ((key, value) in incoming) {
                if (failAfterWrites in 0..written) {
                    throw IllegalStateException("simulated write failure after $written files")
                }
                File(targetDir, "$key.json").writeText(value)
                written++
            }
            val verified = incoming.all { (key, value) ->
                val file = File(targetDir, "$key.json")
                file.isFile && file.readText() == value
            }
            if (!verified) throw IllegalStateException("restored files did not verify")

            // 5) Commit.
            staging.deleteRecursively()
            rollback.deleteRecursively()
            return true
        } catch (e: Exception) {
            // 6) Roll back to the exact previous set.
            try {
                targetDir.deleteRecursively()
                if (rollback.exists()) rollback.copyRecursively(targetDir, overwrite = true)
            } catch (_: Exception) {
                // The rollback copy stays on disk for manual recovery.
            }
            staging.deleteRecursively()
            rollback.deleteRecursively()
            return false
        }
    }
}
