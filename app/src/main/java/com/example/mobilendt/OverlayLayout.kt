package com.example.mobilendt

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Places the status card ([top]) and the controls ([bottom]) over the map.
 *
 * - Portrait: card at the top, controls at the bottom, at most 600 dp wide and centred (tablets).
 * - Landscape (wider than tall): card on the left, controls on the right, each at most 420 dp wide,
 *   so the map stays visible in between.
 *
 * [top] should be scrollable (e.g. a ScrollView) so that it fits when the details are shown on a short screen.
 */
class OverlayLayout(context: Context, private val top: View, private val bottom: View) : ViewGroup(context) {

    private val gap = Ui.dpi(context, 12f)
    private val maxPortrait = Ui.dpi(context, 600f)
    private val maxSide = Ui.dpi(context, 420f)

    init {
        addView(top)
        addView(bottom)
    }

    private fun landscape(w: Int, h: Int) = w > h

    private fun columnWidth(w: Int, h: Int) =
        if (landscape(w, h)) minOf(maxSide, (w - gap) / 2) else minOf(maxPortrait, w)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = MeasureSpec.makeMeasureSpec(columnWidth(w, h), MeasureSpec.EXACTLY)
        bottom.measure(cw, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        // portrait: the card gets what the controls leave; landscape: the full height
        val topMax = if (landscape(w, h)) h else maxOf(0, h - bottom.measuredHeight - gap)
        top.measure(cw, MeasureSpec.makeMeasureSpec(topMax, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val cw = columnWidth(w, h)
        if (landscape(w, h)) {
            top.layout(0, 0, cw, top.measuredHeight)
            bottom.layout(w - cw, h - bottom.measuredHeight, w, h)
        } else {
            val x = (w - cw) / 2
            top.layout(x, 0, x + cw, top.measuredHeight)
            bottom.layout(x, h - bottom.measuredHeight, x + cw, h)
        }
    }
}
