package io.github.june690602_blip.cleanpdf.view

import android.graphics.RectF
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.cache.PageKey
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.RenderScale
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import java.util.concurrent.Future

class PdfPageAdapter(
    private val renderer: PageRenderer,
    private val cache: BitmapCache,
    private val pageSizeProvider: (Int) -> PageSize,
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    private var layout: PageLayout? = null
    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var activeHit: SearchHit? = null

    fun submitLayout(layout: PageLayout) {
        this.layout = layout
        notifyDataSetChanged()
    }

    /** Set search highlights ([hits] grouped by page) with [active] drawn stronger. */
    fun setHighlights(hits: List<SearchHit>, active: SearchHit?) {
        hitsByPage = hits.groupBy { it.page }
        activeHit = active
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = layout?.pageCount ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val frame = FrameLayout(parent.context)
        val iv = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val overlay = HighlightOverlayView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(iv)
        frame.addView(overlay)   // drawn above the page bitmap
        return PageVH(frame, iv, overlay)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val l = layout ?: return
        val w = l.contentWidth.toInt()
        val h = l.pageHeight(position).toInt()
        holder.itemView.layoutParams = RecyclerView.LayoutParams(w, h)
        holder.pending?.cancel(true)
        holder.image.setImageBitmap(null)

        // Search highlights for this page — applied BEFORE the cache-hit early return below, so a
        // cached page still gets its overlay. Cell pixels via HighlightGeometry.
        val pageHits = hitsByPage[position].orEmpty()
        if (pageHits.isEmpty()) {
            holder.overlay.clear()
        } else {
            val pw = pageSizeProvider(position).width
            val rects = pageHits.map {
                val q = HighlightGeometry.toPixels(it, pw, l.contentWidth)
                RectF(q[0], q[1], q[2], q[3])
            }
            val a = activeHit
            val ai = if (a != null && a.page == position) pageHits.indexOf(a) else -1
            holder.overlay.setHighlights(rects, ai)
        }

        // Exact per-page render scale: render so the page's width == contentWidth (== fitWidthPx*zoom),
        // capped so the bitmap never exceeds RenderScale.MAX_BITMAP_BYTES.
        val renderScale = RenderScale.forPage(l.contentWidth, pageSizeProvider(position))
        val key = PageKey(position, BitmapCache.scaleMilli(renderScale))
        val cached = cache.get(key)
        if (cached != null) { holder.image.setImageBitmap(cached); return }

        holder.pending = renderer.submit(position, renderScale) { bmp ->
            holder.itemView.post {
                cache.put(key, bmp)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.pending?.cancel(true); holder.pending = null
        holder.image.setImageBitmap(null)
    }

    class PageVH(itemView: FrameLayout, val image: ImageView, val overlay: HighlightOverlayView) :
        RecyclerView.ViewHolder(itemView) {
        var pending: Future<*>? = null
    }
}
