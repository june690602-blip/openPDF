package io.github.june690602_blip.cleanpdf.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.cache.PageKey
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.RenderScale
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import java.util.concurrent.Future

/**
 * Continuous PDF reader. Pages live in **document space** (PDF points, [PageWorld]) and are drawn
 * through a single [matrix] (doc → screen). Pinch/pan/fling/double-tap just mutate the matrix and
 * invalidate — so zoom-in, zoom-out and pan are all uniformly smooth (the opendwg DrawingView
 * pattern). Bitmaps are MuPDF-rendered per page; when the scale settles, visible pages re-render at
 * the on-screen resolution for sharpness. Public API is PDF-point based so MainActivity / search /
 * selection are unaffected by the rendering strategy.
 *
 * Architecture invariants (see CLAUDE.md): all fitz access via [PageRenderer]'s single thread;
 * [PageRenderer.submit]'s onReady runs on that thread, so we post to main before touching cache/UI;
 * cached bitmaps are never recycle()d (LRU + [RenderScale] cap bound memory).
 */
class PdfReaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var renderer: PageRenderer? = null
    private var cache: BitmapCache? = null
    private var sizes: List<PageSize> = emptyList()
    private var world: PageWorld? = null

    private val matrix = Matrix()          // doc(PDF points) → screen
    private val inverse = Matrix()
    private val mv = FloatArray(9)

    private val gapPt = 16f                 // gap between pages, in PDF points
    private var fitScale = 1f               // scale at which the widest page fills the view width
    private val maxZoomFactor = 8f          // max zoom = fitScale * 8

    val pageCount: Int get() = sizes.size

    /** Exposed for tests/diagnostics: current zoom relative to fit (1.0 = fit width). */
    val currentZoom: Float get() = if (fitScale > 0f) currentScale() / fitScale else 1f

    var onPageChanged: ((current: Int, total: Int) -> Unit)? = null
    var onLongPressPdf: ((page: Int, xPt: Float, yPt: Float) -> Unit)? = null
    var onSelectionDragPdf: ((page: Int, xPt: Float, yPt: Float, isStart: Boolean) -> Unit)? = null

    /** A single tap landed while a selection was showing — the caller should clear the selection. */
    var onSelectionDismiss: (() -> Unit)? = null

    /** A single tap landed with no selection — the caller may toggle UI chrome (the toolbar). */
    var onToggleChrome: (() -> Unit)? = null

    // ---- paints (styling ported from the old Highlight/SelectionOverlayView) ----
    private val bgColor = Color.parseColor("#2B2B2B")  // == @color/reader_bg
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hlFill = Paint().apply { color = Color.argb(70, 255, 230, 0); isAntiAlias = true }
    private val hlActive = Paint().apply { color = Color.argb(150, 255, 145, 0); isAntiAlias = true }
    private val selFill = Paint().apply { color = Color.argb(80, 33, 150, 243); isAntiAlias = true }
    private val selHandle = Paint().apply { color = Color.argb(255, 33, 150, 243); isAntiAlias = true }
    private val handleRadiusPx = resources.displayMetrics.density * 8f
    private val grabRadiusPx = resources.displayMetrics.density * 24f

    // ---- search / selection state (PDF points) ----
    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var activeHit: SearchHit? = null
    private var selPage = -1
    private var selRectsPts: List<FloatArray> = emptyList()
    private var selStartPt: FloatArray? = null
    private var selEndPt: FloatArray? = null
    private enum class Handle { START, END }
    private var draggingHandle: Handle? = null
    // True from a long-press (word selected) until the finger lifts: keep dragging that finger to
    // grow/shrink the selection (no pan/zoom while doing so).
    private var longPressActive = false

    // ---- draggable vertical scrollbar: a fat, short grab handle that shows only while scrolling ----
    private val sbWidthPx = resources.displayMetrics.density * 10f       // fat
    private val sbThumbHpx = resources.displayMetrics.density * 52f      // short (fixed handle height)
    private val sbMarginPx = resources.displayMetrics.density * 3f
    private val sbGrabWidthPx = resources.displayMetrics.density * 36f   // touch zone from the right edge
    private val sbGrabSlopPx = resources.displayMetrics.density * 16f    // vertical slop around the thumb
    private val sbPaint = Paint().apply { color = Color.argb(255, 0x55, 0x55, 0x55); isAntiAlias = true }
    private var draggingScrollbar = false
    private var sbGrabDy = 0f
    private var sbAlpha = 0                                              // 0 = hidden; shown on scroll, fades out
    private var sbFadeAnimator: ValueAnimator? = null
    private val sbHide = Runnable { fadeScrollbarOut() }

    // ---- render bookkeeping (bitmaps live only in [cache]; here we track scales/in-flight) ----
    private val pending = HashSet<PageKey>()
    private val pendingFutures = HashMap<PageKey, Future<*>>()
    private val lastScaleMilli = HashMap<Int, Int>()   // page → last successfully rendered scale bucket
    private var lastReportedPage = -1

    // ---- scratch (avoid per-frame allocation) ----
    private val scratchM = Matrix()
    private val scratchSrc = RectF()
    private val scratchDst = RectF()

    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null
    private var prevFocusX = 0f
    private var prevFocusY = 0f
    private val settleRender = Runnable { requestVisibleRenders() }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                prevFocusX = d.focusX; prevFocusY = d.focusY
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                var f = d.scaleFactor
                val target = cur * f
                if (target < fitScale) f = fitScale / cur
                if (target > fitScale * maxZoomFactor) f = fitScale * maxZoomFactor / cur
                matrix.postScale(f, f, d.focusX, d.focusY)
                // Two-finger drag → pan by the focus movement (safe here: matrix + clamp, no fly-off).
                matrix.postTranslate(d.focusX - prevFocusX, d.focusY - prevFocusY)
                prevFocusX = d.focusX; prevFocusY = d.focusY
                onMatrixChanged()
                return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                if (scaleDetector.isInProgress) return false
                matrix.postTranslate(-dX, -dY)
                onMatrixChanged()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (scaleDetector.isInProgress) return false
                startFling(vX, vY)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentZoom > 1.5f) fitScale else fitScale * 2.5f
                animateZoomTo(target, e.x, e.y)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (selPage >= 0) onSelectionDismiss?.invoke()  // tap clears an active selection...
                else onToggleChrome?.invoke()                  // ...otherwise toggles the toolbar
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val w = world ?: return
                val d = screenToDoc(e.x, e.y)
                val r = w.docToPdf(d[0], d[1])
                val page = r[0].toInt()
                if (page in 0 until pageCount) {
                    onLongPressPdf?.invoke(page, r[1], r[2])
                    longPressActive = true  // subsequent drag of this finger extends the selection
                }
            }
        })

    // ===== public API (signatures unchanged from the RecyclerView version) =====

    fun setDocument(renderer: PageRenderer, sizes: List<PageSize>) {
        clearSelection()
        clearSearchHighlights()
        cancelRenders()
        lastScaleMilli.clear()
        lastReportedPage = -1
        this.renderer = renderer
        this.sizes = sizes
        this.cache = BitmapCache(maxBytes = 96 * 1024 * 1024)
        this.world = PageWorld.build(sizes, gapPt)
        if (width > 0 && height > 0) { fit(); requestVisibleRenders() }
        post { onPageChanged?.invoke(0, sizes.size) }
        invalidate()
    }

    /** Jump so page [index] (0-based) is at the top of the viewport. Clamped to a valid page. */
    fun scrollToPage(index: Int) {
        val w = world ?: return
        if (sizes.isEmpty()) return
        scroller.forceFinished(true)
        val i = index.coerceIn(0, sizes.size - 1)
        setTrans(transX(), -w.top(i) * currentScale())
        onMatrixChanged()
    }

    fun setSearchHighlights(hits: List<SearchHit>, active: SearchHit?) {
        hitsByPage = hits.groupBy { it.page }; activeHit = active; invalidate()
    }
    fun clearSearchHighlights() { hitsByPage = emptyMap(); activeHit = null; invalidate() }

    /** Scroll so [hit] sits near the top quarter of the viewport (tracks the current zoom). */
    fun scrollToHit(hit: SearchHit) {
        val w = world ?: return
        if (sizes.isEmpty()) return
        scroller.forceFinished(true)
        val page = hit.page.coerceIn(0, sizes.size - 1)
        val docY = w.top(page) + hit.y0
        setTrans(transX(), height * 0.25f - docY * currentScale())
        onMatrixChanged()
    }

    /** Show a text selection on [page]: rects + handle anchors in PDF points. */
    fun setSelection(page: Int, rectsPts: List<FloatArray>, startPt: FloatArray?, endPt: FloatArray?) {
        selPage = page; selRectsPts = rectsPts; selStartPt = startPt; selEndPt = endPt; invalidate()
    }
    fun clearSelection() {
        selPage = -1; selRectsPts = emptyList(); selStartPt = null; selEndPt = null
        draggingHandle = null; invalidate()
    }

    // ===== matrix accessors =====
    private fun currentScale(): Float { matrix.getValues(mv); return mv[Matrix.MSCALE_X] }
    private fun transX(): Float { matrix.getValues(mv); return mv[Matrix.MTRANS_X] }
    private fun transY(): Float { matrix.getValues(mv); return mv[Matrix.MTRANS_Y] }
    private fun setTrans(tx: Float, ty: Float) {
        matrix.getValues(mv); mv[Matrix.MTRANS_X] = tx; mv[Matrix.MTRANS_Y] = ty; matrix.setValues(mv)
    }

    private fun screenToDoc(x: Float, y: Float): FloatArray {
        matrix.invert(inverse)
        val p = floatArrayOf(x, y); inverse.mapPoints(p); return p
    }
    private fun pdfToScreen(page: Int, xPt: Float, yPt: Float): FloatArray {
        val d = world!!.pdfToDoc(page, xPt, yPt)
        val p = floatArrayOf(d[0], d[1]); matrix.mapPoints(p); return p
    }

    private fun fit() {
        val w = world ?: return
        if (w.maxWidth <= 0f || width == 0) return
        fitScale = width / w.maxWidth
        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate((width - w.maxWidth * fitScale) / 2f, 0f)  // h-center, top-align
        clampMatrix()
    }

    private fun onMatrixChanged() { clampMatrix(); reportPage(); scheduleSettle(); showScrollbar(); invalidate() }

    private fun clampMatrix() {
        val s = currentScale()
        if (s > 0f) {
            val cs = s.coerceIn(fitScale, fitScale * maxZoomFactor)
            if (cs != s) matrix.postScale(cs / s, cs / s, width / 2f, height / 2f)
        }
        clampPan()
    }

    private fun clampPan() {
        val w = world ?: return
        val s = currentScale()
        val cw = w.maxWidth * s
        val ch = w.totalHeight * s
        val tx = if (cw <= width) (width - cw) / 2f else transX().coerceIn(width - cw, 0f)
        val ty = if (ch <= height) 0f else transY().coerceIn(height - ch, 0f)
        setTrans(tx, ty)
    }

    private fun reportPage() {
        val w = world ?: return
        if (sizes.isEmpty()) return
        val d = screenToDoc(width / 2f, 0f)
        val p = w.pageAtDocY(d[1])
        if (p != lastReportedPage) { lastReportedPage = p; onPageChanged?.invoke(p, sizes.size) }
    }

    private fun scheduleSettle() { removeCallbacks(settleRender); postDelayed(settleRender, 120) }

    // ===== fling =====
    private fun startFling(vX: Float, vY: Float) {
        val w = world ?: return
        val s = currentScale()
        val cw = w.maxWidth * s
        val ch = w.totalHeight * s
        val minX = if (cw <= width) ((width - cw) / 2f).toInt() else (width - cw).toInt()
        val maxX = if (cw <= width) ((width - cw) / 2f).toInt() else 0
        val minY = if (ch <= height) 0 else (height - ch).toInt()
        scroller.forceFinished(true)
        scroller.fling(transX().toInt(), transY().toInt(), vX.toInt(), vY.toInt(), minX, maxX, minY, 0)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setTrans(scroller.currX.toFloat(), scroller.currY.toFloat())
            clampPan(); reportPage(); showScrollbar()
            if (scroller.isFinished) scheduleSettle()
            postInvalidateOnAnimation()
        }
    }

    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(currentScale(), target).apply {
            duration = 200
            addUpdateListener {
                val s = it.animatedValue as Float
                val cur = currentScale()
                if (cur > 0f) matrix.postScale(s / cur, s / cur, fx, fy)
                clampPan(); reportPage(); invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { requestVisibleRenders() }
            })
            start()
        }
    }

    // ===== rendering =====
    private fun visibleRange(): IntRange {
        val w = world ?: return IntRange.EMPTY
        val d0 = screenToDoc(0f, 0f); val d1 = screenToDoc(0f, height.toFloat())
        return w.visiblePages(minOf(d0[1], d1[1]), maxOf(d0[1], d1[1]))
    }

    private fun requestVisibleRenders() {
        val s = currentScale()
        for (i in visibleRange()) requestRender(i, s)
    }

    private fun requestRender(i: Int, scale: Float) {
        val r = renderer ?: return
        val c = cache ?: return
        val rs = RenderScale.forPage(sizes[i].width * scale, sizes[i])
        val key = PageKey(i, BitmapCache.scaleMilli(rs))
        if (c.get(key) != null) { lastScaleMilli[i] = key.scaleMilli; return }
        if (!pending.add(key)) return
        pendingFutures[key] = r.submit(i, rs) { bmp ->
            post {
                c.put(key, bmp); lastScaleMilli[i] = key.scaleMilli
                pending.remove(key); pendingFutures.remove(key); invalidate()
            }
        }
    }

    private fun cancelRenders() {
        pendingFutures.values.forEach { it.cancel(true) }
        pendingFutures.clear(); pending.clear()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val w = world ?: return
        val c = cache ?: return
        val range = visibleRange()
        if (range.isEmpty()) return
        val scaleChanging = scaleDetector.isInProgress || (zoomAnimator?.isRunning == true)
        val s = currentScale()
        canvas.save(); canvas.concat(matrix)
        for (i in range) {
            drawPage(canvas, c, i, s, scaleChanging)
            drawHighlights(canvas, w, i)
            drawSelectionRects(canvas, w, i)
        }
        canvas.restore()
        drawSelectionHandles(canvas)
        drawScrollbar(canvas)
    }

    private fun sbThumbHeight(): Float = minOf(sbThumbHpx, height * 0.5f)

    /** Vertical thumb [top, height] in screen px, or null when the document fits (not scrollable). */
    private fun scrollbarThumb(): FloatArray? {
        val w = world ?: return null
        if (height == 0) return null
        val contentH = w.totalHeight * currentScale()
        if (contentH <= height) return null
        val thumbH = sbThumbHeight()
        val frac = ((-transY()) / (contentH - height)).coerceIn(0f, 1f)
        return floatArrayOf(frac * (height - thumbH), thumbH)
    }

    /** Reveal the scrollbar; it fades out shortly after scrolling stops (stays up while grabbed). */
    private fun showScrollbar() {
        if (scrollbarThumb() == null) return  // not scrollable → never show
        sbFadeAnimator?.cancel()
        sbAlpha = 255
        removeCallbacks(sbHide)
        if (!draggingScrollbar) postDelayed(sbHide, 1100)
        invalidate()
    }
    private fun fadeScrollbarOut() {
        sbFadeAnimator?.cancel()
        sbFadeAnimator = ValueAnimator.ofInt(sbAlpha, 0).apply {
            duration = 350
            addUpdateListener { sbAlpha = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    private fun drawScrollbar(canvas: Canvas) {
        if (sbAlpha <= 0) return
        val t = scrollbarThumb() ?: return
        sbPaint.alpha = sbAlpha
        val right = width - sbMarginPx
        val r = sbWidthPx / 2f
        canvas.drawRoundRect(right - sbWidthPx, t[0], right, t[0] + t[1], r, r, sbPaint)
    }

    /** If the down-touch is on the (visible) scrollbar thumb, start dragging it. */
    private fun grabScrollbar(x: Float, y: Float): Boolean {
        if (sbAlpha <= 0) return false
        val t = scrollbarThumb() ?: return false
        if (x < width - sbGrabWidthPx) return false
        if (y < t[0] - sbGrabSlopPx || y > t[0] + t[1] + sbGrabSlopPx) return false
        draggingScrollbar = true
        sbGrabDy = y - t[0]
        showScrollbar()
        return true
    }

    /** Map a thumb drag to [y] (screen px) to an absolute vertical scroll position. */
    private fun dragScrollbarTo(y: Float) {
        val w = world ?: return
        val contentH = w.totalHeight * currentScale()
        if (contentH <= height) return
        val span = height - sbThumbHeight()
        val frac = if (span > 0f) ((y - sbGrabDy) / span).coerceIn(0f, 1f) else 0f
        setTrans(transX(), -frac * (contentH - height))
        clampPan(); reportPage(); showScrollbar(); invalidate()
    }

    private fun drawPage(canvas: Canvas, c: BitmapCache, i: Int, scale: Float, scaleChanging: Boolean) {
        val w = world!!
        val rs = RenderScale.forPage(sizes[i].width * scale, sizes[i])
        val key = PageKey(i, BitmapCache.scaleMilli(rs))
        var bmp = c.get(key)
        if (bmp != null) {
            lastScaleMilli[i] = key.scaleMilli
        } else {
            bmp = lastScaleMilli[i]?.let { c.get(PageKey(i, it)) }  // blurry placeholder at last scale
            if (!scaleChanging) requestRender(i, scale)
        }
        if (bmp != null) {
            scratchDst.set(w.offsetX(i), w.top(i), w.offsetX(i) + w.width(i), w.top(i) + w.height(i))
            scratchSrc.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            scratchM.setRectToRect(scratchSrc, scratchDst, Matrix.ScaleToFit.FILL)
            canvas.drawBitmap(bmp, scratchM, pagePaint)
        }
    }

    private fun drawHighlights(canvas: Canvas, w: PageWorld, i: Int) {
        val hits = hitsByPage[i] ?: return
        val ox = w.offsetX(i); val oy = w.top(i)
        for (h in hits) {
            val paint = if (h == activeHit) hlActive else hlFill
            canvas.drawRect(ox + h.x0, oy + h.y0, ox + h.x1, oy + h.y1, paint)
        }
    }

    private fun drawSelectionRects(canvas: Canvas, w: PageWorld, i: Int) {
        if (i != selPage) return
        val ox = w.offsetX(i); val oy = w.top(i)
        for (r in selRectsPts) canvas.drawRect(ox + r[0], oy + r[1], ox + r[2], oy + r[3], selFill)
    }

    /** Handles are drawn in screen space so they keep a constant size regardless of zoom. */
    private fun drawSelectionHandles(canvas: Canvas) {
        if (selPage < 0) return
        selStartPt?.let { val p = pdfToScreen(selPage, it[0], it[1]); canvas.drawCircle(p[0], p[1], handleRadiusPx, selHandle) }
        selEndPt?.let { val p = pdfToScreen(selPage, it[0], it[1]); canvas.drawCircle(p[0], p[1], handleRadiusPx, selHandle) }
    }

    // ===== touch =====
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            longPressActive = false
            if (grabScrollbar(e.x, e.y)) return true
            handleUnder(e.x, e.y)?.let { draggingHandle = it; return true }
        }
        if (draggingScrollbar) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragScrollbarTo(e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { draggingScrollbar = false; scheduleSettle(); showScrollbar() }
            }
            return true
        }
        val h = draggingHandle
        if (h != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragSelectionTo(e.x, e.y, h == Handle.START)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> draggingHandle = null
            }
            return true
        }
        // After a long-press selected a word, keep dragging the same finger to grow/shrink it
        // (moves the selection's end) — no pan/zoom while extending.
        if (longPressActive) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragSelectionTo(e.x, e.y, isStart = false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> longPressActive = false
            }
            return true
        }
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        return true
    }

    /** Report a drag to ([x],[y]) screen px as moving the [isStart] boundary of the selection. */
    private fun dragSelectionTo(x: Float, y: Float, isStart: Boolean) {
        val w = world ?: return
        if (selPage < 0) return
        val d = screenToDoc(x, y)
        onSelectionDragPdf?.invoke(selPage, d[0] - w.offsetX(selPage), d[1] - w.top(selPage), isStart)
    }

    private fun handleUnder(x: Float, y: Float): Handle? {
        if (selPage < 0) return null
        var best: Handle? = null; var bestD = grabRadiusPx
        selStartPt?.let {
            val p = pdfToScreen(selPage, it[0], it[1]); val d = dist(x, y, p[0], p[1])
            if (d <= bestD) { bestD = d; best = Handle.START }
        }
        selEndPt?.let {
            val p = pdfToScreen(selPage, it[0], it[1]); val d = dist(x, y, p[0], p[1])
            if (d <= bestD) { bestD = d; best = Handle.END }
        }
        return best
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by; return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private var lastW = 0
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (world != null && w > 0 && h > 0) {
            // Width change (rotation) → refit. Height-only change (toolbar show/hide) → keep the
            // current zoom/scroll; just re-clamp to the new bounds.
            if (w != lastW) fit() else clampMatrix()
            lastW = w
            reportPage(); requestVisibleRenders(); invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(settleRender); removeCallbacks(sbHide)
        scroller.forceFinished(true); zoomAnimator?.cancel(); sbFadeAnimator?.cancel()
    }
}
