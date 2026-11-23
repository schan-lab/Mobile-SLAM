package com.example.mobilendt

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Advanced SLAM parameters. Values are saved immediately; MainActivity applies them when it comes back. */
class SlamParamsActivity : Activity() {

    private class Row(val p: SlamParam, val value: TextView, val seek: SeekBar, val reset: TextView)

    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val column = Ui.column(c).apply {
            addView(Ui.topBar(c, getString(R.string.params_title)))
            addView(Ui.note(c, getString(R.string.params_intro)), Ui.below(c, 12f))
        }
        for (g in SlamParam.Group.entries) {
            val card = Ui.section(c, getString(groupTitle(g)))
            groupNote(g)?.let { card.addView(Ui.note(c, getString(it)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(c, 6f) }) }
            for (p in SlamParam.ALL.filter { it.group == g }) card.addView(row(p), Ui.below(c, 6f))
            column.addView(card, Ui.below(c, 12f))
        }
        val resetAll = Ui.button(c, getString(R.string.params_reset_all), Ui.RED, radius = 20f, textSize = 14f).apply {
            setPadding(Ui.dpi(c, 18f), 0, Ui.dpi(c, 18f), 0)
            setOnClickListener { confirmReset() }
        }
        column.addView(resetAll, LinearLayout.LayoutParams(-2, Ui.dpi(c, 40f)).apply {
            topMargin = Ui.dpi(c, 16f)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setContentView(Ui.screen(c, column))
        rows.forEach { show(it) }
    }

    private fun row(p: SlamParam): View {
        val c = this
        val value = Ui.text(c, 16f, Ui.ACCENT, bold = true).apply { typeface = android.graphics.Typeface.MONOSPACE }
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(c, 15f, bold = true).apply { text = getString(p.title) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
        }
        val reset = Ui.text(c, 11f, Ui.ACCENT, bold = true).apply {
            isClickable = true
            setPadding(Ui.dpi(c, 8f), Ui.dpi(c, 4f), 0, Ui.dpi(c, 4f))
        }
        val seek = SeekBar(c).apply { max = p.sliderSteps }
        val r = Row(p, value, seek, reset)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, pos: Int, fromUser: Boolean) {
                if (!fromUser) return
                Settings.setValue(c, p, p.fromSlider(pos))
                show(r, moveSlider = false)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        reset.setOnClickListener {
            Settings.setValue(c, p, p.default)
            show(r)
        }
        val foot = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.note(c, if (p.note != 0) getString(p.note) else ""), LinearLayout.LayoutParams(0, -2, 1f))
            addView(reset)
        }
        rows += r
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(seek, Ui.below(c, 4f))
            addView(foot)
        }
    }

    private fun show(r: Row, moveSlider: Boolean = true) {
        val v = Settings.value(this, r.p)
        val isDefault = r.p.isDefault(v)
        r.value.text = r.p.format(v)
        r.value.setTextColor(if (isDefault) Ui.TEXT_DIM else Ui.ACCENT)
        r.reset.text = getString(R.string.params_default, r.p.format(r.p.default))
        r.reset.visibility = if (isDefault) View.INVISIBLE else View.VISIBLE
        if (moveSlider) r.seek.progress = r.p.toSlider(v)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.params_reset_all_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.params_reset) { _, _ ->
                Settings.resetParams(this)
                rows.forEach { show(it) }
            }
            .show()
    }

    private fun groupTitle(g: SlamParam.Group) = when (g) {
        SlamParam.Group.SCAN -> R.string.pg_scan
        SlamParam.Group.OPTIMIZER -> R.string.pg_optimizer
        SlamParam.Group.NOISE -> R.string.pg_noise
        SlamParam.Group.EXTRINSIC -> R.string.pg_extrinsic
        SlamParam.Group.LOCALIZATION -> R.string.pg_localization
    }

    private fun groupNote(g: SlamParam.Group) = if (g == SlamParam.Group.EXTRINSIC) R.string.pg_extrinsic_note else null
}
