package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.util.AttributeSet
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

    init { layoutManager = LinearLayoutManager(context) }

    /** Attach an opened document. [sizes] precomputed off-thread by the caller. */
    fun setDocument(renderer: PageRenderer, sizes: List<PageSize>) {
        this.renderer = renderer; this.sizes = sizes
        // ~96MB bitmap budget (tune later); guard against tiny heaps.
        this.cache = BitmapCache(maxBytes = 96 * 1024 * 1024)
        val a = PdfPageAdapter(renderer, cache!!)
        adapterImpl = a; adapter = a
        relayout()
    }

    private fun relayout() {
        val r = renderer ?: return
        if (width == 0) { post { relayout() }; return }
        val layout = PageLayout.compute(sizes, fitWidthPx = width, gapPx = gapPx, zoom = zoom)
        adapterImpl?.submitLayout(layout, zoom, width)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); relayout()
    }
}
