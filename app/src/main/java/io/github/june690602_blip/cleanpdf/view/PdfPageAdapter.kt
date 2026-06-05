package io.github.june690602_blip.cleanpdf.view

import android.graphics.PointF
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

    // Selection (single page). Rects + handle points are stored in PDF points and projected to cell
    // pixels in onBind, so they follow zoom like search highlights. selPage = -1 means "no selection".
    private var selPage: Int = -1
    private var selRectsPts: List<FloatArray> = emptyList()
    private var selStartPt: FloatArray? = null
    private var selEndPt: FloatArray? = null

    /** Set the active text selection on [page] (rects + handle anchors in PDF points). */
    fun setSelection(page: Int, rectsPts: List<FloatArray>, startPt: FloatArray?, endPt: FloatArray?) {
        selPage = page; selRectsPts = rectsPts; selStartPt = startPt; selEndPt = endPt
        notifyDataSetChanged()
    }

    /** Clear any text selection overlay. */
    fun clearSelection() {
        if (selPage == -1) return
        selPage = -1; selRectsPts = emptyList(); selStartPt = null; selEndPt = null
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
        val selectionOverlay = SelectionOverlayView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(iv)
        frame.addView(overlay)           // search highlights, above the page bitmap
        frame.addView(selectionOverlay)  // text selection, above the highlights
        return PageVH(frame, iv, overlay, selectionOverlay)
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

        // Text selection for this page — also applied BEFORE the cache-hit early return so a cached
        // page keeps its selection overlay. PDF points → cell pixels via SelectionGeometry.
        if (position == selPage && selRectsPts.isNotEmpty()) {
            val pw = pageSizeProvider(position).width
            val s = SelectionGeometry.scale(pw, l.contentWidth)
            val selRects = selRectsPts.map {
                val q = SelectionGeometry.rectToPixels(it, s)
                RectF(q[0], q[1], q[2], q[3])
            }
            val start = selStartPt?.let { SelectionGeometry.pointToPixels(it[0], it[1], s).let { p -> PointF(p[0], p[1]) } }
            val end = selEndPt?.let { SelectionGeometry.pointToPixels(it[0], it[1], s).let { p -> PointF(p[0], p[1]) } }
            holder.selectionOverlay.setSelection(selRects, start, end)
        } else {
            holder.selectionOverlay.clear()
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

    class PageVH(
        itemView: FrameLayout,
        val image: ImageView,
        val overlay: HighlightOverlayView,
        val selectionOverlay: SelectionOverlayView,
    ) : RecyclerView.ViewHolder(itemView) {
        var pending: Future<*>? = null
    }
}
