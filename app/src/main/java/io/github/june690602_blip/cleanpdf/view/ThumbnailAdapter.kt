package io.github.june690602_blip.cleanpdf.view

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.cache.PageKey
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.RenderScale
import java.util.concurrent.Future

/**
 * Grid of low-resolution page thumbnails. Shares [renderer] (single render thread) and uses its own
 * small [cache]. Tapping a cell calls [onPick] with the 0-based page index. Mirrors PdfPageAdapter's
 * async-render pattern and does NOT recycle bitmaps (BitmapCache already drops references for the GC).
 */
class ThumbnailAdapter(
    private val renderer: PageRenderer,
    private val sizes: List<PageSize>,
    private val cache: BitmapCache,
    private val cellWidthPx: Int,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ThumbnailAdapter.ThumbVH>() {

    override fun getItemCount(): Int = sizes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbVH {
        val iv = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                (cellWidthPx * 1.3f).toInt(),
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(6, 6, 6, 6)
        }
        return ThumbVH(iv)
    }

    override fun onBindViewHolder(holder: ThumbVH, position: Int) {
        holder.pending?.cancel(true)
        holder.image.setImageBitmap(null)
        holder.image.setOnClickListener { onPick(position) }

        val scale = RenderScale.forPage(cellWidthPx.toFloat(), sizes[position])
        val key = PageKey(position, BitmapCache.scaleMilli(scale))
        val cached = cache.get(key)
        if (cached != null) { holder.image.setImageBitmap(cached); return }

        holder.pending = renderer.submit(position, scale) { bmp ->
            holder.image.post {
                cache.put(key, bmp)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: ThumbVH) {
        holder.pending?.cancel(true); holder.pending = null
        holder.image.setImageBitmap(null)
    }

    class ThumbVH(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var pending: Future<*>? = null
    }
}
