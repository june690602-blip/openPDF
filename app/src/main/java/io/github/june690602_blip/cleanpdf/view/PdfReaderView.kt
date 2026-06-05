package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.SearchHit

class PdfReaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    private var renderer: PageRenderer? = null
    private var cache: BitmapCache? = null
    private var sizes: List<PageSize> = emptyList()
    private var adapterImpl: PdfPageAdapter? = null
    private var lastLayout: PageLayout? = null
    private val gapPx = (resources.displayMetrics.density * 8).toInt()
    var zoom: Float = 1f; private set

    /** Total page count of the attached document (0 if none). */
    val pageCount: Int get() = sizes.size

    /** Invoked with (0-based current page, total) when the first visible page changes. */
    var onPageChanged: ((current: Int, total: Int) -> Unit)? = null

    /** Invoked when the user long-presses on a page: (0-based page, x/y in PDF points). */
    var onLongPressPdf: ((page: Int, xPt: Float, yPt: Float) -> Unit)? = null

    private val minZoom = 1f
    private val maxZoom = 8f
    private var liveScale = 1f  // transient visual scale during an active pinch

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                liveScale = (liveScale * d.scaleFactor).coerceIn(minZoom / zoom, maxZoom / zoom)
                scaleX = liveScale; scaleY = liveScale  // cheap visual feedback only
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                commitZoom(zoom * liveScale)
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                commitZoom(if (zoom > 1.5f) 1f else 2.5f); return true
            }
            override fun onLongPress(e: MotionEvent) {
                val child = findChildViewUnder(e.x, e.y) ?: return
                val page = getChildAdapterPosition(child)
                if (page == NO_POSITION) return
                val cw = lastLayout?.contentWidth ?: return
                val s = SelectionGeometry.scale(sizes[page].width, cw)
                val pdf = SelectionGeometry.toPdfPoint(e.x - child.left, e.y - child.top, s)
                onLongPressPdf?.invoke(page, pdf[0], pdf[1])
            }
        })

    init {
        layoutManager = LinearLayoutManager(context)
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = layoutManager as? LinearLayoutManager ?: return
                val pos = lm.findFirstVisibleItemPosition()
                if (pos != NO_POSITION) onPageChanged?.invoke(pos, sizes.size)
            }
        })
    }

    /** Attach an opened document. [sizes] precomputed off-thread by the caller. */
    fun setDocument(renderer: PageRenderer, sizes: List<PageSize>) {
        clearSelection()
        this.renderer = renderer; this.sizes = sizes
        // ~96MB bitmap budget (tune later); guard against tiny heaps.
        this.cache = BitmapCache(maxBytes = 96 * 1024 * 1024)
        val a = PdfPageAdapter(renderer, cache!!, pageSizeProvider = { i -> sizes[i] })
        adapterImpl = a; adapter = a
        relayout()
        post { onPageChanged?.invoke(0, this.sizes.size) }
    }

    /** Jump so page [index] (0-based) is at the top of the viewport. Clamped to a valid page. */
    fun scrollToPage(index: Int) {
        if (sizes.isEmpty()) return
        val i = index.coerceIn(0, sizes.size - 1)
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0)
        onPageChanged?.invoke(i, sizes.size)
    }

    /** Show [hits] as highlights, with [active] drawn stronger. Empty list clears them. */
    fun setSearchHighlights(hits: List<SearchHit>, active: SearchHit?) {
        adapterImpl?.setHighlights(hits, active)
    }

    fun clearSearchHighlights() {
        adapterImpl?.setHighlights(emptyList(), null)
    }

    // Active selection mirror (PDF points) — kept so handle hit-testing can locate the handles
    // without reaching into the adapter. selPage = -1 means "no selection".
    private var selPage: Int = -1
    private var selStartPt: FloatArray? = null
    private var selEndPt: FloatArray? = null

    /** Show a text selection on [page]: rects + handle anchors in PDF points. */
    fun setSelection(page: Int, rectsPts: List<FloatArray>, startPt: FloatArray?, endPt: FloatArray?) {
        selPage = page; selStartPt = startPt; selEndPt = endPt
        adapterImpl?.setSelection(page, rectsPts, startPt, endPt)
    }

    /** Remove the text selection overlay and stop treating handles as grabbable. */
    fun clearSelection() {
        selPage = -1; selStartPt = null; selEndPt = null
        adapterImpl?.clearSelection()
    }

    /**
     * Scroll so [hit] sits near the top quarter of the viewport. Converts the hit's PDF-point top
     * to cell pixels using the current layout's scale (so it tracks the active zoom).
     */
    fun scrollToHit(hit: SearchHit) {
        if (sizes.isEmpty()) return
        val page = hit.page.coerceIn(0, sizes.size - 1)
        val cw = lastLayout?.contentWidth ?: return
        val s = HighlightGeometry.scale(sizes[page].width, cw)
        val hitTopPx = hit.y0 * s
        val offset = (height * 0.25f - hitTopPx).toInt()
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, offset)
        onPageChanged?.invoke(page, sizes.size)
    }

    private fun relayout() {
        val r = renderer ?: return
        if (width == 0) { post { relayout() }; return }
        val layout = PageLayout.compute(sizes, fitWidthPx = width, gapPx = gapPx, zoom = zoom)
        lastLayout = layout
        adapterImpl?.submitLayout(layout)
    }

    private fun commitZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(minZoom, maxZoom)
        liveScale = 1f; scaleX = 1f; scaleY = 1f
        if (clamped == zoom) return
        zoom = clamped
        // Do NOT cache.clear() here: PageKey includes the quantized scale, so stale-scale bitmaps are
        // simply cache misses (re-rendered at the new scale) and the LRU drops them by budget. Clearing
        // would also throw away pages the user may immediately zoom back to.
        relayout()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        return super.onTouchEvent(e) || scaleDetector.isInProgress
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); relayout()
    }
}
