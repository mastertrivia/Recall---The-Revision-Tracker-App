package com.goaltracker.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class BackupNamingTest {

    @Test
    fun fileNameUsesDayMonthYear() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-11-01")!!
        assertEquals("Recall_01-11-2026_Backup.json", BackupNaming.fileName(date))
    }

    @Test
    fun sameDayExportsGetANumberedSuffixBeforeTheExtension() {
        val name = "Recall_01-11-2026_Backup.json"
        assertEquals(name, BackupNaming.withSuffix(name, 1))
        assertEquals("Recall_01-11-2026_Backup (2).json", BackupNaming.withSuffix(name, 2))
        assertEquals("Recall_01-11-2026_Backup (7).json", BackupNaming.withSuffix(name, 7))
    }

    @Test
    fun suffixWorksForNamesWithoutAnExtension() {
        assertEquals("backup (3)", BackupNaming.withSuffix("backup", 3))
    }
}
