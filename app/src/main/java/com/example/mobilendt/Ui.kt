package com.example.mobilendt

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
        setColor(color); cornerRadius = radius
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
        val dot = when (ok) { true -> "#2ECC71"; false -> "#FF4D5E"; null -> "#F5A623" }
        v.text = android.text.Html.fromHtml("<font color='$dot'>●</font> $label", 0)
    }

    /** Button-like TextView with ripple. */
    fun button(c: Context, label: String, color: Int, radius: Float = 28f, textSize: Float = 15f) =
        text(c, textSize, Color.WHITE, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setButtonColor(this, color, radius)
        }

    fun setButtonColor(v: TextView, color: Int, radius: Float = 28f) {
        v.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color, dp(v.context, radius)), null)
    }

    /** "phone" / "tablet" (localised), for user-facing text. */
    fun deviceName(c: Context): String =
        c.getString(if (c.resources.configuration.smallestScreenWidthDp >= 600) R.string.device_tablet else R.string.device_phone)

    /** Pads [v] by the system bars (edge-to-edge). */
    fun padForSystemBars(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Centres [content] horizontally, at most [maxDp] wide (large screens / landscape). */
    fun capWidth(c: Context, content: View, maxDp: Float): View = MaxWidthFrame(c, dpi(c, maxDp)).apply {
        addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
    }

    private class MaxWidthFrame(c: Context, private val maxPx: Int) : FrameLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
            val child = getChildAt(0)
            (child?.layoutParams as? LayoutParams)?.width = if (w > maxPx) maxPx else ViewGroup.LayoutParams.MATCH_PARENT
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** A card with a small section label on top; add content to the returned column. */
    fun section(c: Context, title: String) = LinearLayout(c).apply {
        val p = dpi(c, 14f)
        orientation = LinearLayout.VERTICAL
        background = card(c)
        setPadding(p, p, p, p)
        addView(text(c, 11f, TEXT_DIM, bold = true).apply { text = title },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(c, 8f) })
    }

    /** Small explanatory text. */
    fun note(c: Context, s: String, color: Int = TEXT_DIM) = text(c, 12f, color).apply { text = s }

    /** IPv4 input: digits and dots; full-width input (e.g. a Japanese IME) is converted to ASCII. */
    fun ipField(c: Context, onChange: (String) -> Unit) = EditText(c).apply {
        hint = "0.0.0.0"
        setHintTextColor(GREY)
        setTextColor(TEXT)
        textSize = 16f
        typeface = Typeface.MONOSPACE
        isSingleLine = true
        // numeric keypad, but several dots allowed; pasted / IME full-width text is converted first
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        keyListener = DigitsKeyListener.getInstance("0123456789.")
        filters = arrayOf(InputFilter { src, start, end, _, _, _ ->
            val sub = src.subSequence(start, end).toString()
            val n = Mid360Config.normalize(sub).filter { it.isDigit() && it < '\u0080' || it == '.' }
            if (n == sub) null else n
        })
        background = rounded(0x0FFFFFFF, dp(c, 8f))
        setPadding(dpi(c, 10f), 0, dpi(c, 10f), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable) {
                val n = Mid360Config.normalize(e.toString())
                if (n != e.toString()) { e.replace(0, e.length, n); return }
                setTextColor(if (Mid360Config.isValid(n)) TEXT else RED)
                onChange(n)
            }
        })
    }

    /** Navigation row: bold label and a chevron. */
    fun navRow(c: Context, label: String, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(c, 15f, bold = true).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(c, 18f, TEXT_DIM).apply { text = "›" })
        isClickable = true; isFocusable = true
        setPadding(0, dpi(c, 4f), 0, dpi(c, 4f))
        setOnClickListener { onClick() }
    }
}
