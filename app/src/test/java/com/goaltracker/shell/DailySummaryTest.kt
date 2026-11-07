package com.goaltracker.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The daily reminder used to be a sentence frozen at schedule time, so it announced
 * the previous day's counts. These tests pin the recompute rules to Home's:
 * blocked topics skipped, completed/cleared revisions ignored, one entry per topic,
 * and the topic's estimate added once.
 */
class DailySummaryTest {

    private fun rev(due: String, completed: String? = null, cleared: String? = null) =
        """{"dueDate":"$due","completedAt":${completed?.let { "\"$it\"" } ?: "null"},"clearedByLaterRevisionAt":${cleared?.let { "\"$it\"" } ?: "null"}}"""

    private fun topic(id: String, duration: Int, blocked: Boolean = false, revisions: String) =
        """{"id":"$id","duration":$duration,"blocked":$blocked,"revisions":[$revisions]}"""

    private fun state(dayStartAt: Int = 0, vararg topics: String) =
        """{"version":1,"settings":{"dayStartAt":$dayStartAt},"topics":[${topics.joinToString(",")}]}"""

    @Test
    fun countsPendingTopicsDueTodayAndOverdue() {
        val json = state(
            0,
            topic("a", 30, revisions = rev("2026-11-07")),
            topic("b", 15, revisions = rev("2026-10-28")),
            topic("c", 30, revisions = rev("2026-11-20"))
        )
        val summary = DailySummary.parse(json, "2026-11-07")
        assertEquals(2, summary!!.topics)
        assertEquals(45, summary.minutes)
        assertEquals("2 topics to revise today \u00b7 45m estimated.", summary.text())
    }

    @Test
    fun ignoresCompletedAndClearedRevisions() {
        val json = state(
            0,
            topic("a", 30, revisions = rev("2026-11-07", completed = "2026-11-07T04:00:00.000Z")),
            topic("b", 20, revisions = rev("2026-11-06", cleared = "2026-11-07T04:00:00.000Z")),
            topic("c", 10, revisions = rev("2026-11-07"))
        )
        val summary = DailySummary.parse(json, "2026-11-07")
        assertEquals(1, summary!!.topics)
        assertEquals(10, summary.minutes)
    }

    @Test
    fun skipsBlockedTopics() {
        val json = state(
            0,
            topic("a", 30, blocked = true, revisions = rev("2026-11-07")),
            topic("b", 10, revisions = rev("2026-11-07"))
        )
        assertEquals(10, DailySummary.parse(json, "2026-11-07")!!.minutes)
    }

    @Test
    fun countsOneEntryPerTopicEvenWithSeveralDueRevisions() {
        val json = state(
            0,
            topic("a", 30, revisions = rev("2026-11-01") + "," + rev("2026-11-07"))
        )
        val summary = DailySummary.parse(json, "2026-11-07")
        assertEquals(1, summary!!.topics)
        assertEquals(30, summary.minutes)
    }

    @Test
    fun singleTopicUsesSingularWording() {
        val json = state(0, topic("a", 125, revisions = rev("2026-11-07")))
        assertEquals("1 topic to revise today \u00b7 2h 5m estimated.", DailySummary.parse(json, "2026-11-07")!!.text())
    }

    @Test
    fun unreadablePayloadReturnsNullSoCallersKeepStoredText() {
        assertNull(DailySummary.parse("not json", "2026-11-07"))
        assertNull(DailySummary.parse("""{"settings":{}}""", "2026-11-07"))
    }
}
