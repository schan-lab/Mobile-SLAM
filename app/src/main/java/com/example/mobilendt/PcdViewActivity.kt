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
        val uri = intent.data ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "PCD"
        val pad = Ui.dpi(c, 12f)

        view = PcdView(c)

        val back = Ui.button(c, "←", Ui.GREY, radius = 20f, textSize = 18f).apply { setOnClickListener { finish() } }
        val title = Ui.text(c, 15f, bold = true).apply { text = name; isSingleLine = true }
        info = Ui.text(c, 12f, Ui.TEXT_DIM).apply { text = "読み込み中…" }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(info) }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(c)
            setPadding(pad, pad, pad, pad)
            addView(back, LinearLayout.LayoutParams(Ui.dpi(c, 40f), Ui.dpi(c, 40f)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
        }

        val fit = Ui.button(c, "全体表示", Ui.GREY, textSize = 14f).apply { setOnClickListener { view.fit() } }
        sizeBtn = Ui.button(c, "点サイズ ●", Ui.GREY, textSize = 14f).apply {
            setOnClickListener {
                sizeIdx = (sizeIdx + 1) % sizes.size
                view.pointSize = sizes[sizeIdx]; view.requestRender(); updateSizeLabel()
            }
        }
        val share = Ui.button(c, "共有", Ui.ACCENT, textSize = 14f).apply {
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "PCDを共有"))
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
            text = "1本指: 回転   2本指: 移動   ピンチ: ズーム   ダブルタップ: 全体表示"
        }
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(c, 6f) })
            addView(controls)
        }
        val root = FrameLayout(c).apply {
            setBackgroundColor(Ui.BG)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(pad, pad, pad, 0) })
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(pad, 0, pad, pad) })
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
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
                    val sx = d.max[0] - d.min[0]; val sy = d.max[1] - d.min[1]; val sz = d.max[2] - d.min[2]
                    val sub = if (d.n < d.totalPoints) "（%,d点を表示）".format(d.n) else ""
                    info.text = "%,d点%s   %.1f × %.1f × %.1f m   %s   %dms".format(
                        d.totalPoints, sub, sx, sy, sz, d.format, ms)
                }.onFailure { e ->
                    info.text = "読み込み失敗: ${e.message}"
                    info.setTextColor(Ui.RED)
                }
            }
        }
    }

    private fun updateSizeLabel() {
        sizeBtn.text = "点サイズ " + "●".repeat(sizeIdx + 1)
    }

    override fun onResume() { super.onResume(); view.onResume() }
    override fun onPause() { view.onPause(); super.onPause() }

    companion object { const val EXTRA_NAME = "name" }
}
