package com.example.mobilendt

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** App information, privacy statement and third-party licenses. */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val pad = Ui.dpi(c, 12f)

        val back = Ui.button(c, "←", Ui.GREY, radius = 20f, textSize = 18f).apply { setOnClickListener { finish() } }
        val title = Ui.text(c, 20f, bold = true).apply { text = getString(R.string.about_title) }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(Ui.dpi(c, 40f), Ui.dpi(c, 40f)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
        }

        val info = packageManager.getPackageInfo(packageName, 0)
        val version = "${info.versionName} (${info.longVersionCode})"

        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(top)
            addView(block("Mobile SLAM", getString(R.string.about_version, version) + "\n" + getString(R.string.about_desc)))
            addView(block(getString(R.string.about_privacy), getString(R.string.about_privacy_text, Ui.deviceName(c))))
            addView(block(getString(R.string.about_impl), getString(R.string.about_impl_text)))
            addView(block(getString(R.string.about_license_mit), MIT, mono = true))
            addView(block(getString(R.string.about_license_eigen), EIGEN, mono = true))
            addView(block("Kotlin standard library / LLVM libc++", OTHERS, mono = true))
        }
        val root = ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(Ui.capWidth(c, column, 700f))
            Ui.padForSystemBars(this)
        }
        setContentView(root)
    }

    private fun block(title: String, text: String, mono: Boolean = false) = LinearLayout(this).apply {
        val c = context
        val p = Ui.dpi(c, 14f)
        orientation = LinearLayout.VERTICAL
        background = Ui.card(c)
        setPadding(p, p, p, p)
        addView(Ui.text(c, 13f, bold = true).apply { this.text = title })
        addView(Ui.text(c, if (mono) 11f else 13f, Ui.TEXT_DIM).apply {
            this.text = text
            if (mono) typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 8f) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 12f) }
    }

    private companion object {
        const val MIT = """MIT License

Copyright (c) 2026 schan-lab

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

        const val EIGEN = "Eigen is licensed under the MPL 2.0. A copy of the license is available at " +
            "https://mozilla.org/MPL/2.0/ and the unmodified source code at https://gitlab.com/libeigen/eigen " +
            "(tag 3.4.0). Built with EIGEN_MPL2_ONLY."

        const val OTHERS = "Licensed under the Apache License 2.0 (libc++: with LLVM Exceptions). " +
            "https://www.apache.org/licenses/LICENSE-2.0 / https://llvm.org/LICENSE.txt\n" +
            "Sources: https://github.com/JetBrains/kotlin, https://github.com/llvm/llvm-project"
    }
}
