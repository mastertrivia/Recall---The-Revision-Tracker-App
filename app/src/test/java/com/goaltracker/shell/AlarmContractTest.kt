package com.goaltracker.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmContractTest {

    @Test
    fun clampDuration_enforcesOneToSixtySeconds() {
        assertEquals(1_000L, AlarmContract.clampDuration(0))
        assertEquals(1_000L, AlarmContract.clampDuration(999))
        assertEquals(1_000L, AlarmContract.clampDuration(1_000))
        assertEquals(2_000L, AlarmContract.clampDuration(2_000))
        assertEquals(3_000L, AlarmContract.clampDuration(3_000))
        assertEquals(5_000L, AlarmContract.clampDuration(5_000))
        assertEquals(15_000L, AlarmContract.clampDuration(15_000))
        assertEquals(60_000L, AlarmContract.clampDuration(60_000))
        assertEquals(60_000L, AlarmContract.clampDuration(120_000))
    }

    @Test
    fun normalizeSlot_keepsKnownSlotsIndependent() {
        assertEquals("sessionend", AlarmContract.normalizeSlot("sessionend"))
        assertEquals("taskcomplete", AlarmContract.normalizeSlot("taskcomplete"))
        assertEquals("bombwarn", AlarmContract.normalizeSlot("bombwarn"))
        assertEquals("completion", AlarmContract.normalizeSlot("completion"))
    }

    @Test
    fun normalizeSlot_unknownFallsBackToSessionEnd() {
        assertEquals("sessionend", AlarmContract.normalizeSlot(null))
        assertEquals("sessionend", AlarmContract.normalizeSlot(""))
        assertEquals("sessionend", AlarmContract.normalizeSlot("classic"))
        assertEquals("sessionend", AlarmContract.normalizeSlot("bomb"))
    }

    @Test
    fun extrasAndDefaults_areStableForPersistence() {
        assertEquals("id", AlarmContract.EXTRA_ID)
        assertEquals("title", AlarmContract.EXTRA_TITLE)
        assertEquals("text", AlarmContract.EXTRA_TEXT)
        assertEquals("soundSlot", AlarmContract.EXTRA_SOUND_SLOT)
        assertEquals("durationMs", AlarmContract.EXTRA_DURATION_MS)
        assertEquals("sessionend", AlarmContract.DEFAULT_SLOT)
        assertEquals(60_000L, AlarmContract.DEFAULT_DURATION_MS)
    }
}
