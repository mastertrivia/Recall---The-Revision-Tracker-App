package com.goaltracker.shell

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * v2.4: A single, premium, card-styled confirmation dialog used by every
 * item in the overflow menu.
 *
 * IMPORTANT (v2.4 fix): everything - header, body, the "type to confirm"
 * input and BOTH buttons - lives inside ONE rounded white card. We do NOT
 * use AlertDialog's native positive/negative buttons, because those render
 * in the dialog's own button strip OUTSIDE our card (they appeared floating
 * over the dimmed background in the previous build). The body scrolls with a
 * capped height so a tall dialog never overflows or clips on small screens.
 *
 * Built programmatically so it needs no extra dependencies (the project only
 * ships AppCompat) and wrapped in try/catch that falls back to a plain
 * dialog, honouring the app-wide "optional UI must never crash the app" rule.
 */
object ConfirmDialog {

    // Category accent colours (aligned with the product colour spec).
    const val BLUE = 0xFF1976D2.toInt()    // Refresh / Import app
    const val GREEN = 0xFF43A047.toInt()   // Backup / restore data
    const val ORANGE = 0xFFFB8C00.toInt()  // Restore default app / Replaced
    const val RED = 0xFFD32F2F.toInt()     // Factory reset / Deleted
    const val GREY = 0xFF757575.toInt()    // System check (read-only)

    // Card palette (fixed so it reads well regardless of app theme).
    private const val CARD_BG = 0xFFFFFFFF.toInt()
    private const val TEXT_PRIMARY = 0xFF212121.toInt()
    private const val TEXT_SECONDARY = 0xFF616161.toInt()
    private const val HINGLISH_BG = 0xFFF4F6FA.toInt()
    private const val INPUT_BORDER = 0xFFD0D5DD.toInt()
    private const val CANCEL_TEXT = 0xFF757575.toInt()

    /** One "✓ Label : Value" line in the summary block. */
    data class SummaryLine(val label: String, val value: String)

    /**
     * @param requiredWord when non-null, the user must type it to enable the
     *   confirm button (used for destructive/irreversible actions).
     */
    fun show(
        activity: Activity,
        accent: Int,
        icon: String,
        heading: String,
        english: String,
        hinglish: String,
        summary: List<SummaryLine>,
        confirmLabel: String,
        requiredWord: String? = null,
        onConfirmed: () -> Unit,
    ) {
        try {
            showPremium(
                activity, accent, icon, heading, english, hinglish,
                summary, confirmLabel, requiredWord, onConfirmed,
            )
        } catch (e: Exception) {
            ErrorLog.log(activity, "ConfirmDialog", e.message ?: "render failed")
            showFallback(activity, heading, english, hinglish, confirmLabel, requiredWord, onConfirmed)
        }
    }

    private fun showPremium(
        activity: Activity,
        accent: Int,
        icon: String,
        heading: String,
        english: String,
        hinglish: String,
        summary: List<SummaryLine>,
        confirmLabel: String,
        requiredWord: String?,
        onConfirmed: () -> Unit,
    ) {
        val dm = activity.resources.displayMetrics
        val d = dm.density
        fun dp(v: Int) = (v * d).toInt()

        val radius = dp(20).toFloat()

        // ---- Card root: one rounded surface that clips ALL children ----
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = radius
            }
            clipToOutline = true
        }

        // ---- Header (accent, top corners rounded) ----------------------
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    radius, radius, radius, radius, // top-left, top-right
                    0f, 0f, 0f, 0f,                 // bottom corners square
                )
                setColor(accent)
            }
        }
        header.addView(TextView(activity).apply {
            text = icon
            textSize = 22f
            setPadding(0, 0, dp(12), 0)
        })
        header.addView(TextView(activity).apply {
            text = heading
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- Scrollable body (height capped so it never overflows) -----
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }

        // English description.
        body.addView(TextView(activity).apply {
            text = english
            setTextColor(TEXT_PRIMARY)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        // Hinglish explanation in a subtle tinted box.
        val hinglishBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(HINGLISH_BG)
                cornerRadius = dp(12).toFloat()
            }
        }
        hinglishBox.addView(TextView(activity).apply {
            text = "HINGLISH"
            setTextColor(accent)
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
        })
        hinglishBox.addView(TextView(activity).apply {
            text = hinglish
            setTextColor(TEXT_SECONDARY)
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        body.addView(hinglishBox, marginParams(dp(14), dp(14)))

        // Summary block.
        if (summary.isNotEmpty()) {
            val summaryBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(withAlpha(accent, 0x14))
                    cornerRadius = dp(12).toFloat()
                }
            }
            summary.forEach { line ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(activity).apply {
                    text = "\u2713"
                    setTextColor(valueColor(line.value, accent))
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, dp(8), 0)
                })
                row.addView(TextView(activity).apply {
                    text = line.label
                    setTextColor(TEXT_PRIMARY)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(activity).apply {
                    text = line.value
                    setTextColor(valueColor(line.value, accent))
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                })
                summaryBox.addView(row)
            }
            body.addView(summaryBox, marginParams(0, dp(4)))
        }

        // Optional typed-confirmation field for destructive actions.
        var input: EditText? = null
        if (requiredWord != null) {
            body.addView(TextView(activity).apply {
                text = "Type \u201C$requiredWord\u201D to confirm"
                setTextColor(TEXT_SECONDARY)
                textSize = 13f
                setPadding(0, dp(14), 0, dp(8))
            })
            input = EditText(activity).apply {
                hint = "Type $requiredWord here\u2026"
                setHintTextColor(INPUT_BORDER)
                setTextColor(TEXT_PRIMARY)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                setSingleLine()
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), INPUT_BORDER)
                }
            }
            body.addView(input, marginParams(0, dp(4)))
        }

        val scroll = MaxHeightScrollView(activity, (dm.heightPixels * 0.62f).toInt()).apply {
            isFillViewport = false
            addView(body)
        }
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- Button row (INSIDE the card, bottom-aligned) --------------
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }

        val cancelBtn = TextView(activity).apply {
            text = "CANCEL"
            setTextColor(CANCEL_TEXT)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = ripple(activity, dp(12).toFloat(), withAlpha(GREY, 0x22), Color.TRANSPARENT)
        }
        val confirmBtn = TextView(activity).apply {
            text = confirmLabel.uppercase()
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = ripple(activity, dp(24).toFloat(), withAlpha(Color.WHITE, 0x33), accent)
        }
        buttons.addView(cancelBtn)
        buttons.addView(confirmBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })

        card.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Outer padding so the card never touches the screen edges.
        val outer = LinearLayout(activity).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(outer)
            .setCancelable(true)
            .create()
        // Transparent window so ONLY our rounded card is the visible surface.
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val field = input
            if (field != null && field.text.toString().trim() != requiredWord) {
                field.error = "Type $requiredWord exactly"
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirmed()
        }

        dialog.show()
        // Bound the width so the card looks like a proper centered card.
        val targetW = (dm.widthPixels * 0.92f).toInt().coerceAtMost(dp(420))
        dialog.window?.setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** A ScrollView that never grows taller than [maxH]px (so it scrolls). */
    private class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            super.onMeasure(widthSpec, h)
        }
    }

    /** Ultra-safe fallback if the custom view fails for any reason. */
    private fun showFallback(
        activity: Activity,
        heading: String,
        english: String,
        hinglish: String,
        confirmLabel: String,
        requiredWord: String?,
        onConfirmed: () -> Unit,
    ) {
        val input = if (requiredWord != null) EditText(activity).apply { hint = requiredWord } else null
        val b = AlertDialog.Builder(activity)
            .setTitle(heading)
            .setMessage("$english\n\n$hinglish")
            .setPositiveButton(confirmLabel) { _, _ ->
                if (input == null || input.text.toString().trim() == requiredWord) onConfirmed()
            }
            .setNegativeButton("Cancel", null)
        if (input != null) b.setView(input)
        b.show()
    }

    private fun marginParams(top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top; bottomMargin = bottom }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    /** A filled rounded background with a touch ripple. */
    private fun ripple(ctx: Context, corner: Float, rippleColor: Int, fill: Int): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = corner
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = corner
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    /** Colour a summary value by its meaning. */
    private fun valueColor(value: String, accent: Int): Int = when {
        value.equals("Deleted", true) -> RED
        value.equals("Replaced", true) -> ORANGE
        value.equals("Preserved", true) -> GREEN
        value.equals("Saved", true) || value.equals("Saved to Device", true) -> GREEN
        value.equals("Restored", true) -> BLUE
        value.equals("Not Included", true) -> GREY
        else -> accent
    }
}
