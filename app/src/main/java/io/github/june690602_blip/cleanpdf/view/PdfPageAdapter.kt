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
    private val pageWidthPtsProvider: (Int) -> Float,
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    private var layout: PageLayout? = null

    fun submitLayout(layout: PageLayout) {
        this.layout = layout
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
        val w = l.contentWidth.toInt()
        val h = l.pageHeight(position).toInt()
        holder.itemView.layoutParams = RecyclerView.LayoutParams(w, h)
        holder.pending?.cancel(true)
        holder.image.setImageBitmap(null)

        // Exact per-page render scale: render so the page's width == contentWidth (== fitWidthPx*zoom).
        val targetWidthPx = l.contentWidth
        val pageWidthPts = pageWidthPtsProvider(position)
        val renderScale = if (pageWidthPts > 0f) targetWidthPx / pageWidthPts else 1f
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
}
