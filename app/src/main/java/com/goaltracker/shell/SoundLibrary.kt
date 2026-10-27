package com.goaltracker.shell

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central registry for the app's built-in (bundled) sounds and the user's
 * current sound selection.
 *
 * Design goals (v2.3):
 *  - 14 sounds are bundled in res/raw, so they are ALWAYS available: after a
 *    fresh install, after a factory reset, with no storage permission, and
 *    without depending on any external/user file.
 *  - The user's choice is stored as lightweight metadata in SharedPreferences
 *    (a type + a value), never as a copied audio blob. Playback is resolved
 *    natively at fire time, so the frozen HTML only needs to say "play the
 *    completion/bomb sound" - it never has to know which sound is selected.
 *  - "Choose from device" stores only the picked content Uri string.
 */
object SoundLibrary {

    /** A bundled sound. [isLong] marks alarm/warning tones that should loop. */
    data class BuiltinSound(
        val key: String,
        val label: String,
        val resId: Int,
        val isLong: Boolean
    )

    // Order here is the order shown in the native chooser.
    val BUILTINS: List<BuiltinSound> = listOf(
        // Short notification tones (played once).
        BuiltinSound("chime", "Chime", R.raw.snd_short_chime, false),
        BuiltinSound("ding", "Ding", R.raw.snd_short_ding, false),
        BuiltinSound("marimba", "Marimba", R.raw.snd_short_marimba, false),
        BuiltinSound("pop", "Pop", R.raw.snd_short_pop, false),
        BuiltinSound("glass", "Glass", R.raw.snd_short_glass, false),
        BuiltinSound("harp", "Soft Harp", R.raw.snd_short_harp, false),
        BuiltinSound("wood", "Wood Tap", R.raw.snd_short_wood, false),
        BuiltinSound("piano", "Soft Piano", R.raw.snd_short_piano, false),
        BuiltinSound("drop", "Water Drop", R.raw.snd_short_drop, false),
        // Long alarm/warning tones (looped to a full ring by the shell).
        BuiltinSound("classic", "Classic Alarm", R.raw.snd_long_classic, true),
        BuiltinSound("siren", "Siren", R.raw.snd_long_siren, true),
        BuiltinSound("pulse", "Urgent Pulse", R.raw.snd_long_pulse, true),
        BuiltinSound("bells", "Bells", R.raw.snd_long_bells, true),
        BuiltinSound("digital", "Digital Alarm", R.raw.snd_long_digital, true),
    )

    fun builtinByKey(key: String?): BuiltinSound? =
        BUILTINS.firstOrNull { it.key == key }

    // ----- Slots -----
    // The HTML has two independent sound settings:
    //   "completion" -> played when a session/pomodoro completes
    //   "bombwarn"   -> played when the Bomb reaches zero
    const val SLOT_COMPLETION = "completion"
    const val SLOT_SESSION_END = "sessionend"
    const val SLOT_TASK_COMPLETE = "taskcomplete"
    const val SLOT_BOMB = "bombwarn"

    // Selection types.
    const val TYPE_BUILTIN = "builtin"  // value = builtin key
    const val TYPE_DEVICE = "device"    // value = content Uri string

    private const val PREFS = "sound_selection"

    /** Sensible out-of-the-box defaults so audio works immediately on install. */
    private fun defaultKeyFor(slot: String) =
        if (slot == SLOT_BOMB) "classic" else "chime"

    data class Selection(val type: String, val value: String)

    fun getSelection(ctx: Context, slot: String): Selection {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val type = p.getString("${slot}_type", null)
        val value = p.getString("${slot}_value", null)
        if (type == null || value == null) {
            return Selection(TYPE_BUILTIN, defaultKeyFor(slot))
        }
        // A device selection whose Uri is no longer accessible must not brick
        // the alarm - fall back to the built-in default when it can't resolve.
        if (type == TYPE_DEVICE && !uriIsUsable(ctx, value)) {
            return Selection(TYPE_BUILTIN, defaultKeyFor(slot))
        }
        return Selection(type, value)
    }

    fun setBuiltin(ctx: Context, slot: String, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${slot}_type", TYPE_BUILTIN)
            .putString("${slot}_value", key)
            .apply()
    }

    fun setDevice(ctx: Context, slot: String, uri: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${slot}_type", TYPE_DEVICE)
            .putString("${slot}_value", uri)
            .apply()
    }

    private fun uriIsUsable(ctx: Context, uri: String): Boolean = try {
        ctx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { }
        true
    } catch (_: Exception) {
        false
    }

    /** Whether the current selection for a slot should loop as an alarm. */
    fun shouldLoop(ctx: Context, slot: String): Boolean {
        // The bomb slot always behaves like a full alarm.
        if (slot == SLOT_BOMB) return true
        val sel = getSelection(ctx, slot)
        // Device tones for completion also loop (user picked a ringtone/alarm);
        // built-in completion tones loop only if they are the "long" variety.
        return when (sel.type) {
            TYPE_DEVICE -> true
            else -> builtinByKey(sel.value)?.isLong == true
        }
    }

    /** JSON describing the built-in list + current selections, for a chooser. */
    fun toJson(ctx: Context): String {
        val arr = JSONArray()
        for (b in BUILTINS) {
            arr.put(JSONObject().apply {
                put("key", b.key)
                put("label", b.label)
                put("long", b.isLong)
            })
        }
        return JSONObject().apply {
            put("builtins", arr)
            put("completion", selectionJson(getSelection(ctx, SLOT_COMPLETION)))
            put("bomb", selectionJson(getSelection(ctx, SLOT_BOMB)))
        }.toString()
    }

    private fun selectionJson(s: Selection) = JSONObject().apply {
        put("type", s.type)
        put("value", s.value)
    }
}
