package com.goaltracker.shell

/**
 * Shared alarm parameters used by the JS bridge, the scheduler, and the
 * native playback path. Kept free of Android framework calls so the
 * clamping and slot rules can be unit-tested on the JVM.
 */
object AlarmContract {

    const val MIN_DURATION_MS = 1_000L
    const val MAX_DURATION_MS = 60_000L
    const val DEFAULT_DURATION_MS = 60_000L
    const val SLOT_SESSION_END = "sessionend"
    const val SLOT_TASK_COMPLETE = "taskcomplete"
    const val SLOT_BOMB = "bombwarn"
    const val SLOT_COMPLETION = "completion"
    const val DEFAULT_SLOT = SLOT_SESSION_END

    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_SOUND_SLOT = "soundSlot"
    const val EXTRA_DURATION_MS = "durationMs"

    fun clampDuration(ms: Long): Long = ms.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    fun normalizeSlot(slot: String?): String = when (slot) {
        SLOT_SESSION_END, SLOT_TASK_COMPLETE, SLOT_BOMB, SLOT_COMPLETION -> slot
        else -> DEFAULT_SLOT
    }
}
