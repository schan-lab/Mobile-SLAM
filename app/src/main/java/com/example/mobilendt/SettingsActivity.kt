package com.example.mobilendt

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Settings screen. Values are saved immediately; MainActivity applies them when it comes back to the front. */
class SettingsActivity : Activity() {

    private lateinit var blindValue: TextView
    private lateinit var colorIntensity: TextView
    private lateinit var colorHeight: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val pad = Ui.dpi(c, 12f)

        val back = Ui.button(c, "←", Ui.GREY, radius = 20f, textSize = 18f).apply { setOnClickListener { finish() } }
        val title = Ui.text(c, 20f, bold = true).apply { text = "設定" }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(Ui.dpi(c, 40f), Ui.dpi(c, 40f)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
        }

        // ---- SLAM: near-range cut-off ----
        blindValue = Ui.text(c, 17f, Ui.ACCENT, bold = true)
        val blindHead = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(c, 15f, bold = true).apply { text = "近くの点を無視する距離" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(blindValue)
        }
        val steps = (Settings.MAX_BLIND * 10).roundToInt()
        val seek = SeekBar(c).apply {
            max = steps
            progress = (Settings.blind(c) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) Settings.setBlind(c, p / 10f)
                    showBlind(p / 10f)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val blindNote = Ui.text(c, 12f, Ui.TEXT_DIM).apply {
            text = "マップ作成・自己位置推定で、LiDAR からこの距離より近い点を使いません。" +
                "持っている人の体や手が地図に写り込むのを防ぎます。狭い場所では小さくしてください。" +
                "（規定 %.1f m、ライブ表示には影響しません）".format(Settings.DEFAULT_BLIND)
        }
        val reset = Ui.button(c, "規定値に戻す", Ui.GREY, radius = 16f, textSize = 12f).apply {
            setPadding(Ui.dpi(c, 14f), 0, Ui.dpi(c, 14f), 0)
            setOnClickListener {
                Settings.setBlind(c, Settings.DEFAULT_BLIND)
                seek.progress = (Settings.DEFAULT_BLIND * 10).roundToInt()
            }
        }
        val slamCard = card("SLAM").apply {
            addView(blindHead)
            addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 10f) })
            addView(blindNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 6f) })
            addView(reset, LinearLayout.LayoutParams(-2, Ui.dpi(c, 34f)).apply { topMargin = Ui.dpi(c, 10f) })
        }

        // ---- display: point colour ----
        colorIntensity = Ui.button(c, "反射強度", Ui.GREY, radius = 18f, textSize = 14f).apply {
            setOnClickListener { Settings.setColorMode(c, Settings.COLOR_INTENSITY); showColor() }
        }
        colorHeight = Ui.button(c, "高さ", Ui.GREY, radius = 18f, textSize = 14f).apply {
            setOnClickListener { Settings.setColorMode(c, Settings.COLOR_HEIGHT); showColor() }
        }
        val colorRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(colorIntensity, LinearLayout.LayoutParams(0, Ui.dpi(c, 44f), 1f))
            addView(colorHeight, LinearLayout.LayoutParams(0, Ui.dpi(c, 44f), 1f).apply { marginStart = Ui.dpi(c, 8f) })
        }
        val colorNote = Ui.text(c, 12f, Ui.TEXT_DIM).apply {
            text = "反射強度：反射の弱い面は青、強い面は赤。\n高さ：点の高さで色を分けます。\n" +
                "反射強度の入っていない PCD は高さで色を付けます。"
        }
        val colorCard = card("表示").apply {
            addView(Ui.text(c, 15f, bold = true).apply { text = "点群の色" })
            addView(colorRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 10f) })
            addView(colorNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 8f) })
        }

        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(top)
            addView(slamCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 14f) })
            addView(colorCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 12f) })
        }
        val root = ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        showBlind(Settings.blind(c))
        showColor()
    }

    /** A card with a small section label on top. */
    private fun card(section: String) = LinearLayout(this).apply {
        val p = Ui.dpi(context, 14f)
        orientation = LinearLayout.VERTICAL
        background = Ui.card(context)
        setPadding(p, p, p, p)
        addView(Ui.text(context, 11f, Ui.TEXT_DIM, bold = true).apply { text = section },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(context, 8f) })
    }

    private fun showBlind(m: Float) {
        blindValue.text = if (m <= 0f) "無効" else "%.1f m".format(m)
    }

    private fun showColor() {
        val mode = Settings.colorMode(this)
        Ui.setButtonColor(colorIntensity, if (mode == Settings.COLOR_INTENSITY) Ui.ACCENT else Ui.GREY, 18f)
        Ui.setButtonColor(colorHeight, if (mode == Settings.COLOR_HEIGHT) Ui.ACCENT else Ui.GREY, 18f)
    }
}
