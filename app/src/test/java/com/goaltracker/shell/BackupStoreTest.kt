package com.goaltracker.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A restore must behave like a transaction: either the whole snapshot lands (and keys
 * absent from it disappear), or the previous data directory is exactly as it was.
 */
class BackupStoreTest {

    private fun tempRoot(): File = Files.createTempDirectory("recall-backup-store").toFile()

    @Test
    fun replaceAllWritesEveryKeyAndRemovesStaleOnes() {
        val root = tempRoot()
        val target = File(root, "data").apply { mkdirs() }
        File(target, "recall_state.json").writeText("old-state")
        File(target, "old_feature.json").writeText("stale")

        val ok = BackupStore.replaceAll(
            target,
            linkedMapOf("recall_state" to "new-state", "recall_state_shadow" to "shadow")
        )

        assertTrue(ok)
        assertEquals("new-state", File(target, "recall_state.json").readText())
        assertEquals("shadow", File(target, "recall_state_shadow.json").readText())
        assertFalse("a key absent from the backup must not survive", File(target, "old_feature.json").exists())
        assertFalse(File(root, "data_import_tmp").exists())
        assertFalse(File(root, "data_import_rollback").exists())
        root.deleteRecursively()
    }

    @Test
    fun aFailedWriteRestoresThePreviousDirectoryExactly() {
        val root = tempRoot()
        val target = File(root, "data").apply { mkdirs() }
        File(target, "recall_state.json").writeText("old-state")
        File(target, "old_feature.json").writeText("stale")

        val ok = BackupStore.replaceAll(
            target,
            linkedMapOf("recall_state" to "new-state", "recall_state_shadow" to "shadow"),
            failAfterWrites = 1
        )

        assertFalse(ok)
        assertEquals("old-state", File(target, "recall_state.json").readText())
        assertTrue("a stale file removed during the attempt must come back", File(target, "old_feature.json").exists())
        assertFalse("a partially written key must not remain", File(target, "recall_state_shadow.json").exists())
        assertFalse(File(root, "data_import_tmp").exists())
        assertFalse(File(root, "data_import_rollback").exists())
        root.deleteRecursively()
    }

    @Test
    fun restoreIntoAMissingDirectoryStillWorks() {
        val root = tempRoot()
        val target = File(root, "data")

        val ok = BackupStore.replaceAll(target, linkedMapOf("recall_state" to "first"))

        assertTrue(ok)
        assertEquals("first", File(target, "recall_state.json").readText())
        root.deleteRecursively()
    }
}
