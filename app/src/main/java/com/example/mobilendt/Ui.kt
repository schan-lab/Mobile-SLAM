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

    /** "phone" / "tablet" (localised), for user-facing text. */
    fun deviceName(c: Context): String =
        c.getString(if (c.resources.configuration.smallestScreenWidthDp >= 600) R.string.device_tablet else R.string.device_phone)

    /** Pads [v] by the system bars (edge-to-edge). */
    fun padForSystemBars(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                // Android 10 (API 29): WindowInsets.Type does not exist yet
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
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
                if (n != e.toString()) {
                    e.replace(0, e.length, n)
                    return
                }
                setTextColor(if (Mid360Config.isValid(n)) TEXT else RED)
                onChange(n)
            }
        })
    }

    /** Back button, title and an optional button on the right. */
    fun topBar(a: android.app.Activity, title: String, right: View? = null) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val back = button(a, "←", GREY, radius = 20f, textSize = 18f).apply { setOnClickListener { a.finish() } }
        addView(back, LinearLayout.LayoutParams(dpi(a, 40f), dpi(a, 40f)))
        val t = text(a, 20f, bold = true).apply {
            text = title
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(a, 12f) })
        if (right != null) addView(right, LinearLayout.LayoutParams(-2, dpi(a, 40f)).apply { marginStart = dpi(a, 8f) })
    }

    /** Small rounded button for a top bar or a card. */
    fun smallButton(c: Context, label: String, color: Int = ACCENT, onClick: () -> Unit) =
        button(c, label, color, radius = 20f, textSize = 13f).apply {
            setPadding(dpi(c, 14f), 0, dpi(c, 14f), 0)
            setOnClickListener { onClick() }
        }

    /** List card: icon, bold title and a dim second line. */
    fun listRow(c: Context, icon: String, title: String, meta: String) = LinearLayout(c).apply {
        val iconView = text(c, 20f, ACCENT, bold = true).apply {
            text = icon
            gravity = Gravity.CENTER
            background = rounded(0x223D8BFF, dp(c, 12f))
        }
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(c, 15f, bold = true).apply {
                text = title
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(text(c, 12f, TEXT_DIM).apply { text = meta })
        }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = card(c)
        setPadding(dpi(c, 12f), dpi(c, 12f), dpi(c, 12f), dpi(c, 12f))
        addView(iconView, LinearLayout.LayoutParams(dpi(c, 44f), dpi(c, 44f)))
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(c, 12f) })
        isClickable = true
    }

    /** Single-line text input in the style of [ipField]. */
    fun field(c: Context, value: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_URI, onChange: (String) -> Unit) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(GREY)
        setTextColor(TEXT)
        textSize = 15f
        isSingleLine = true
        this.inputType = inputType
        background = rounded(0x0FFFFFFF, dp(c, 8f))
        setPadding(dpi(c, 10f), 0, dpi(c, 10f), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable) = onChange(e.toString())
        })
    }

    /** Label on the left, [input] on the right. */
    fun labeled(c: Context, label: String, input: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(c, 14f).apply { text = label }, LinearLayout.LayoutParams(dpi(c, 120f), -2))
        addView(input, LinearLayout.LayoutParams(0, dpi(c, 44f), 1f))
    }

    /** Scrolling screen: [column] centred, at most 700 dp wide, padded for the system bars. */
    fun screen(c: Context, column: View) = android.widget.ScrollView(c).apply {
        setBackgroundColor(BG)
        isFillViewport = true
        addView(capWidth(c, column, 700f))
        padForSystemBars(this)
    }

    /** Vertical column with the standard screen padding. */
    fun column(c: Context) = LinearLayout(c).apply {
        val p = dpi(c, 12f)
        orientation = LinearLayout.VERTICAL
        setPadding(p, p, p, p)
    }

    /** Layout params: full width, [top] dp above. */
    fun below(c: Context, top: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(c, top) }

    /** Navigation row: bold label and a chevron. */
    fun navRow(c: Context, label: String, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(c, 15f, bold = true).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(c, 18f, TEXT_DIM).apply { text = "›" })
        isClickable = true
        isFocusable = true
        setPadding(0, dpi(c, 4f), 0, dpi(c, 4f))
        setOnClickListener { onClick() }
    }
}
