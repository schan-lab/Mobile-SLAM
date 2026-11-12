package com.example.mobilendt

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Small design system for the programmatic UI. */
object Ui {
    const val BG = 0xFF0B0D12.toInt()
    const val CARD = 0xCC161A22.toInt()
    const val CARD_STROKE = 0x33FFFFFF
    const val TEXT = 0xFFE8EAF0.toInt()
    const val TEXT_DIM = 0xFF8A91A0.toInt()
    const val ACCENT = 0xFF3D8BFF.toInt()
    const val GREEN = 0xFF2ECC71.toInt()
    const val ORANGE = 0xFFF5A623.toInt()
    const val RED = 0xFFFF4D5E.toInt()
    const val GREY = 0xFF59606E.toInt()

    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
    fun dpi(c: Context, v: Float) = dp(c, v).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    fun card(c: Context) = rounded(CARD, dp(c, 18f), CARD_STROKE, dpi(c, 1f))

    fun text(c: Context, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** Pill-shaped status chip with a coloured dot. */
    fun chip(c: Context) = text(c, 12f).apply {
        background = rounded(0x22FFFFFF, dp(c, 12f))
        setPadding(dpi(c, 10f), dpi(c, 4f), dpi(c, 10f), dpi(c, 4f))
        gravity = Gravity.CENTER_VERTICAL
    }

    fun setChip(v: TextView, ok: Boolean?, label: String) {
        val dot = when (ok) {
            true -> "#2ECC71"
            false -> "#FF4D5E"
            null -> "#F5A623"
        }
        v.text = android.text.Html.fromHtml("<font color='$dot'>●</font> $label", 0)
    }

    /** Button-like TextView with ripple. */
    fun button(c: Context, label: String, color: Int, radius: Float = 28f, textSize: Float = 15f) =
        text(c, textSize, Color.WHITE, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setButtonColor(this, color, radius)
        }

    fun setButtonColor(v: TextView, color: Int, radius: Float = 28f) {
        v.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color, dp(v.context, radius)), null)
    }
}
