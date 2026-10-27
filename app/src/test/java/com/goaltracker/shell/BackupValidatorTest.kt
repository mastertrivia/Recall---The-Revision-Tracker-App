package com.goaltracker.shell

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The import gate must accept every legitimate Recall backup (with checksum
 * AND legacy without one) and reject everything else - corrupt JSON, a file
 * from another app, a state missing topics/subjects/settings, checksum
 * mismatch - with a clear user-facing reason BEFORE any stored file is
 * touched.
 */
class BackupValidatorTest {

    private fun validInner(): String {
        return JSONObject()
            .put("version", 1)
            .put("subjects", JSONArray().put("Maths"))
            .put("settings", JSONObject().put("examName", "Polity test").put("dailyStudyHours", 6))
            .put("topics", JSONArray()
                .put(JSONObject().put("id", "t1").put("name", "Algebra").put("subject", "Maths").put("duration", 45)))
            .toString()
    }

    private fun backupDoc(inner: String = validInner(), checksum: String? = null): String {
        val data = JSONObject().put(BackupValidator.CORE_KEY, inner)
        val doc = JSONObject()
            .put("app", BackupValidator.APP_ID)
            .put("exportedAt", 123L)
            .put("data", data)
        if (checksum != null) doc.put("checksum", checksum)
        return doc.toString()
    }

    private fun validChecksum(entries: Map<String, String>): String =
        BackupValidator.fnv1a32(BackupValidator.checksumPayload(entries))

    private fun expectReject(json: String, needle: String) {
        try {
            BackupValidator.validate(json)
            fail("expected rejection mentioning \"$needle\"")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            assertTrue("reason \"$msg\" should mention \"$needle\"", msg.contains(needle, ignoreCase = true))
        }
    }

    @Test
    fun validFileWithChecksumPasses() {
        val inner = validInner()
        val entries = mapOf(BackupValidator.CORE_KEY to inner)
        val result = BackupValidator.validate(backupDoc(inner, validChecksum(entries)))
        assertEquals(setOf(BackupValidator.CORE_KEY), result.keys)
        assertEquals(inner, result[BackupValidator.CORE_KEY])
    }

    @Test
    fun legacyFileWithoutChecksumStillPasses() {
        val result = BackupValidator.validate(backupDoc(checksum = null))
        assertNotNull(result[BackupValidator.CORE_KEY])
    }

    @Test
    fun notValidJsonRejected() {
        expectReject("{this is not json", "not valid JSON")
    }

    @Test
    fun foreignAppRejected() {
        val doc = JSONObject()
            .put("app", "some-other-app")
            .put("data", JSONObject().put(BackupValidator.CORE_KEY, validInner()))
            .toString()
        expectReject(doc, "not a Recall backup")
    }

    @Test
    fun missingDataSectionRejected() {
        val doc = JSONObject().put("app", BackupValidator.APP_ID).toString()
        expectReject(doc, "no data section")
    }

    @Test
    fun nonStringFieldValueRejected() {
        val doc = JSONObject()
            .put("app", BackupValidator.APP_ID)
            .put("data", JSONObject().put(BackupValidator.CORE_KEY, 42))
            .toString()
        expectReject(doc, "corrupt")
    }

    @Test
    fun corruptInnerJsonRejected() {
        expectReject(backupDoc(inner = "{\"topics\": [ {\"broken\": true"), "damaged")
    }

    @Test
    fun coreKeyMissingRejected() {
        val doc = JSONObject()
            .put("app", BackupValidator.APP_ID)
            .put("data", JSONObject().put("some_other_key", "{}"))
            .toString()
        expectReject(doc, "no Recall data")
    }

    @Test
    fun missingTopicsRejected() {
        val inner = JSONObject()
            .put("subjects", JSONArray())
            .put("settings", JSONObject())
            .toString()
        expectReject(backupDoc(inner = inner), "topics")
    }

    @Test
    fun missingSubjectsRejected() {
        val inner = JSONObject()
            .put("topics", JSONArray())
            .put("settings", JSONObject())
            .toString()
        expectReject(backupDoc(inner = inner), "subjects")
    }

    @Test
    fun missingSettingsRejected() {
        val inner = JSONObject()
            .put("topics", JSONArray())
            .put("subjects", JSONArray())
            .toString()
        expectReject(backupDoc(inner = inner), "no settings")
    }

    @Test
    fun topicsNotArrayRejected() {
        val inner = JSONObject()
            .put("topics", "not-an-array")
            .put("subjects", JSONArray())
            .put("settings", JSONObject())
            .toString()
        expectReject(backupDoc(inner = inner), "topics")
    }

    @Test
    fun checksumMismatchRejected() {
        val doc = JSONObject()
            .put("app", BackupValidator.APP_ID)
            .put("exportedAt", 123L)
            .put("checksum", "deadbeef")
            .put("data", JSONObject().put(BackupValidator.CORE_KEY, validInner()))
            .toString()
        expectReject(doc, "damaged")
    }

    @Test
    fun checksumDetectsEditedContents() {
        val inner = validInner()
        val entries = mapOf(BackupValidator.CORE_KEY to inner)
        val tampered = backupDoc(inner = inner.replace("Algebra", "Evil"), checksum = validChecksum(entries))
        expectReject(tampered, "checksum")
    }

    @Test
    fun fnv1aMatchesKnownValues() {
        // FNV-1a 32-bit of the empty string is the offset basis itself.
        assertEquals("811c9dc5", BackupValidator.fnv1a32(""))
        // Deterministic, order-independent payload (sorted keys).
        val a = BackupValidator.checksumPayload(linkedMapOf("b" to "2", "a" to "1"))
        val b = BackupValidator.checksumPayload(linkedMapOf("a" to "1", "b" to "2"))
        assertEquals(a, b)
        assertEquals(BackupValidator.fnv1a32(a), BackupValidator.fnv1a32(b))
    }
}
