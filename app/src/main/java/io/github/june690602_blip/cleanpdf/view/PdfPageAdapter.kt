package io.github.june690602_blip.cleanpdf.view

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.cache.PageKey
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import java.util.concurrent.Future

class PdfPageAdapter(
    private val renderer: PageRenderer,
    private val cache: BitmapCache,
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    private var layout: PageLayout? = null
    private var zoom: Float = 1f
    private var fitWidthPx: Int = 0

    fun submitLayout(layout: PageLayout, zoom: Float, fitWidthPx: Int) {
        this.layout = layout; this.zoom = zoom; this.fitWidthPx = fitWidthPx
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
        frame.addView(iv)
        return PageVH(frame, iv)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val l = layout ?: return
        val h = l.pageHeight(position).toInt()
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, h,
        )
        holder.pending?.cancel(true)
        holder.image.setImageBitmap(null)

        val renderScale = (fitWidthPx * zoom) / PDF_BASE_WIDTH_HINT // see note below
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

    class PageVH(itemView: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(itemView) {
        var pending: Future<*>? = null
    }

    companion object {
        // fitz scale 1.0 == 72dpi. We want page width (in px) == fitWidthPx*zoom.
        // renderScale = targetWidthPx / pageWidthPts. PdfReaderView passes exact per-page scale
        // in Task 8 refinement; for Task 7 we approximate via a base hint and FIT_CENTER.
        const val PDF_BASE_WIDTH_HINT = 595f // A4 width in pts; refined in Task 8
    }
}
