package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View

/**
 * Transparent overlay that paints the text selection over a page cell: translucent rects plus two
 * round drag handles (start/end). Purely presentational; holds no PDF state. Sibling of
 * [HighlightOverlayView] (search highlights), kept separate so the two never fight over one view.
 */
class SelectionOverlayView(context: Context) : View(context) {
    private val fill = Paint().apply { color = Color.argb(80, 33, 150, 243); isAntiAlias = true }
    private val handlePaint = Paint().apply { color = Color.argb(255, 33, 150, 243); isAntiAlias = true }
    private val handleRadius = resources.displayMetrics.density * 8f

    private var rects: List<RectF> = emptyList()
    private var startHandle: PointF? = null
    private var endHandle: PointF? = null

    /** [rects] and handle points are in this view's pixel space (same size as the page cell). */
    fun setSelection(rects: List<RectF>, start: PointF?, end: PointF?) {
        this.rects = rects
        this.startHandle = start
        this.endHandle = end
        invalidate()
    }

    fun clear() {
        if (rects.isEmpty() && startHandle == null && endHandle == null) return
        rects = emptyList(); startHandle = null; endHandle = null; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (r in rects) canvas.drawRect(r, fill)
        startHandle?.let { canvas.drawCircle(it.x, it.y, handleRadius, handlePaint) }
        endHandle?.let { canvas.drawCircle(it.x, it.y, handleRadius, handlePaint) }
    }
}
