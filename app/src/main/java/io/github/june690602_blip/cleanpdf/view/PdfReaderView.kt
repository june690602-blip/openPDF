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

class PdfReaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    private var renderer: PageRenderer? = null
    private var cache: BitmapCache? = null
    private var sizes: List<PageSize> = emptyList()
    private var adapterImpl: PdfPageAdapter? = null
    private val gapPx = (resources.displayMetrics.density * 8).toInt()
    var zoom: Float = 1f; private set

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
        })

    init { layoutManager = LinearLayoutManager(context) }

    /** Attach an opened document. [sizes] precomputed off-thread by the caller. */
    fun setDocument(renderer: PageRenderer, sizes: List<PageSize>) {
        this.renderer = renderer; this.sizes = sizes
        // ~96MB bitmap budget (tune later); guard against tiny heaps.
        this.cache = BitmapCache(maxBytes = 96 * 1024 * 1024)
        val a = PdfPageAdapter(renderer, cache!!, pageSizeProvider = { i -> sizes[i] })
        adapterImpl = a; adapter = a
        relayout()
    }

    private fun relayout() {
        val r = renderer ?: return
        if (width == 0) { post { relayout() }; return }
        val layout = PageLayout.compute(sizes, fitWidthPx = width, gapPx = gapPx, zoom = zoom)
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
