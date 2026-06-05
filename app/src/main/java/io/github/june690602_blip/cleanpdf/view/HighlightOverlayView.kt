package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Transparent overlay that paints translucent rectangles over a page cell for search hits.
 * One rect may be the "active" hit (drawn stronger). Purely presentational; holds no PDF state.
 */
class HighlightOverlayView(context: Context) : View(context) {
    private val fill = Paint().apply { color = Color.argb(70, 255, 230, 0); isAntiAlias = true }
    private val active = Paint().apply { color = Color.argb(150, 255, 145, 0); isAntiAlias = true }
    private var rects: List<RectF> = emptyList()
    private var activeIndex: Int = -1

    /** [rects] are in this view's pixel space (same size as the page cell). */
    fun setHighlights(rects: List<RectF>, activeIndex: Int) {
        this.rects = rects
        this.activeIndex = activeIndex
        invalidate()
    }

    fun clear() {
        if (rects.isEmpty() && activeIndex == -1) return
        rects = emptyList(); activeIndex = -1; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (i in rects.indices) canvas.drawRect(rects[i], if (i == activeIndex) active else fill)
    }
}
