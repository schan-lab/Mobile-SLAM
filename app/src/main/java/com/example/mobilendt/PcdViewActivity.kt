package com.example.mobilendt

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Full-screen 3D viewer for one PCD (content Uri in intent.data). */
class PcdViewActivity : Activity() {

    private lateinit var view: PcdView
    private lateinit var info: TextView
    private lateinit var sizeBtn: TextView
    private val sizes = floatArrayOf(1.5f, 2.5f, 4f, 6f)
    private var sizeIdx = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = this
        val uri = intent.data ?: run {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "PCD"
        val pad = Ui.dpi(c, 12f)

        view = PcdView(c)

        val back = Ui.button(c, "←", Ui.GREY, radius = 20f, textSize = 18f).apply { setOnClickListener { finish() } }
        val title = Ui.text(c, 15f, bold = true).apply {
            text = name
            isSingleLine = true
        }
        info = Ui.text(c, 12f, Ui.TEXT_DIM).apply { text = getString(R.string.loading) }
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(info)
        }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(c)
            setPadding(pad, pad, pad, pad)
            addView(back, LinearLayout.LayoutParams(Ui.dpi(c, 40f), Ui.dpi(c, 40f)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
        }

        val fit = Ui.button(c, getString(R.string.fit), Ui.GREY, textSize = 14f).apply { setOnClickListener { view.fit() } }
        sizeBtn = Ui.button(c, getString(R.string.point_size) + " ●", Ui.GREY, textSize = 14f).apply {
            setOnClickListener {
                sizeIdx = (sizeIdx + 1) % sizes.size
                view.pointSize = sizes[sizeIdx]
                view.requestRender()
                updateSizeLabel()
            }
        }
        val share = Ui.button(c, getString(R.string.share), Ui.ACCENT, textSize = 14f).apply {
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.share_pcd)))
            }
        }
        val h = Ui.dpi(c, 50f)
        val controls = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.card(c)
            setPadding(Ui.dpi(c, 8f), Ui.dpi(c, 8f), Ui.dpi(c, 8f), Ui.dpi(c, 8f))
            addView(fit, LinearLayout.LayoutParams(0, h, 1f))
            addView(sizeBtn, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = Ui.dpi(c, 8f); marginEnd = Ui.dpi(c, 8f) })
            addView(share, LinearLayout.LayoutParams(0, h, 1f))
        }
        val hint = Ui.text(c, 11f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.hint_viewer)
        }
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(c, 6f) })
            addView(controls)
        }
        // same arrangement as the main screen: stacked in portrait, side by side in landscape
        val overlay = OverlayLayout(c, top, bottom)
        val root = FrameLayout(c).apply {
            setBackgroundColor(Ui.BG)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
            Ui.padForSystemBars(this)
        }
        setContentView(root)
        updateSizeLabel()

        thread(name = "pcd-load") {
            val t0 = System.currentTimeMillis()
            val result = runCatching { contentResolver.openInputStream(uri)!!.use { PcdFile.read(it) } }
            val ms = System.currentTimeMillis() - t0
            runOnUiThread {
                result.onSuccess { d ->
                    view.setData(d)
                    val sx = d.max[0] - d.min[0]
                    val sy = d.max[1] - d.min[1]
                    val sz = d.max[2] - d.min[2]
                    val sub = if (d.n < d.totalPoints) getString(R.string.viewer_shown, "%,d".format(d.n)) else ""
                    info.text = getString(R.string.viewer_info, "%,d".format(d.totalPoints), sub, sx, sy, sz, d.format, ms.toInt())
                }.onFailure { e ->
                    info.text = getString(R.string.load_failed, e.message ?: "")
                    info.setTextColor(Ui.RED)
                }
            }
        }
    }

    private fun updateSizeLabel() {
        sizeBtn.text = getString(R.string.point_size) + " " + "●".repeat(sizeIdx + 1)
    }

    override fun onResume() {
        super.onResume()
        view.colorMode = Settings.colorMode(this)
        view.onResume()
        view.requestRender()
    }
    override fun onPause() {
        view.onPause()
        super.onPause()
    }

    companion object { const val EXTRA_NAME = "name" }
}
