package com.example.mobilendt

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
        val title = Ui.text(c, 20f, bold = true).apply { text = getString(R.string.settings) }
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
            addView(Ui.text(c, 15f, bold = true).apply { text = getString(R.string.blind_title) }, LinearLayout.LayoutParams(0, -2, 1f))
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
            text = getString(R.string.blind_note, Settings.DEFAULT_BLIND)
        }
        val reset = Ui.button(c, getString(R.string.reset_default), Ui.GREY, radius = 16f, textSize = 12f).apply {
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
        colorIntensity = Ui.button(c, getString(R.string.intensity), Ui.GREY, radius = 18f, textSize = 14f).apply {
            setOnClickListener { Settings.setColorMode(c, Settings.COLOR_INTENSITY); showColor() }
        }
        colorHeight = Ui.button(c, getString(R.string.height), Ui.GREY, radius = 18f, textSize = 14f).apply {
            setOnClickListener { Settings.setColorMode(c, Settings.COLOR_HEIGHT); showColor() }
        }
        val colorRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(colorIntensity, LinearLayout.LayoutParams(0, Ui.dpi(c, 44f), 1f))
            addView(colorHeight, LinearLayout.LayoutParams(0, Ui.dpi(c, 44f), 1f).apply { marginStart = Ui.dpi(c, 8f) })
        }
        val colorNote = Ui.text(c, 12f, Ui.TEXT_DIM).apply {
            text = getString(R.string.colour_note)
        }
        val colorCard = card(getString(R.string.section_display)).apply {
            addView(Ui.text(c, 15f, bold = true).apply { text = getString(R.string.point_colour) })
            addView(colorRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 10f) })
            addView(colorNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 8f) })
        }

        // ---- Mid-360: network settings ----
        val netCard = card("Mid-360").apply {
            addView(Ui.navRow(c, getString(R.string.mid360_ip_dest)) {
                startActivity(android.content.Intent(c, Mid360NetworkActivity::class.java))
            })
            addView(Ui.note(c, getString(R.string.mid360_ip_dest_note, Ui.deviceName(c))),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 6f) })
        }

        // ---- demo ----
        val demoSwitch = Switch(c).apply {
            text = getString(R.string.demo_try)
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isChecked = Settings.demoMode
            setOnCheckedChangeListener { _, on ->
                if (on == Settings.demoMode) return@setOnCheckedChangeListener
                Settings.demoMode = on
                Toast.makeText(c, if (on) R.string.demo_on else R.string.demo_off, Toast.LENGTH_SHORT).show()
            }
        }
        val demoCard = card(getString(R.string.demo)).apply {
            addView(demoSwitch, LinearLayout.LayoutParams(-1, -2))
            addView(Ui.note(c, getString(R.string.demo_note)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 6f) })
        }

        // ---- about ----
        val aboutCard = LinearLayout(c).apply {
            val p = Ui.dpi(c, 14f)
            orientation = LinearLayout.VERTICAL
            background = Ui.card(c)
            setPadding(p, p, p, p)
            addView(Ui.navRow(c, getString(R.string.about_licenses)) {
                startActivity(android.content.Intent(c, AboutActivity::class.java))
            })
        }

        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(top)
            addView(slamCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 14f) })
            addView(colorCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 12f) })
            addView(netCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 12f) })
            addView(demoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 12f) })
            addView(aboutCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 12f) })
        }
        val root = ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(Ui.capWidth(c, column, 700f))
            Ui.padForSystemBars(this)
        }
        setContentView(root)
        showBlind(Settings.blind(c))
        showColor()
    }

    /** A card with a small section label on top. */
    private fun card(section: String) = Ui.section(this, section)

    private fun showBlind(m: Float) {
        blindValue.text = if (m <= 0f) getString(R.string.blind_off) else "%.1f m".format(m)
    }

    private fun showColor() {
        val mode = Settings.colorMode(this)
        Ui.setButtonColor(colorIntensity, if (mode == Settings.COLOR_INTENSITY) Ui.ACCENT else Ui.GREY, 18f)
        Ui.setButtonColor(colorHeight, if (mode == Settings.COLOR_HEIGHT) Ui.ACCENT else Ui.GREY, 18f)
    }
}
