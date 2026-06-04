package io.github.june690602_blip.cleanpdf.cache

import android.graphics.Bitmap

/** Cache key: a page rendered at a quantized scale. */
data class PageKey(val page: Int, val scaleMilli: Int)

class BitmapCache(maxBytes: Int) {
    // No recycle() on eviction: an evicted bitmap may still be attached to a visible — or a
    // RecyclerView-cached, off-screen — ImageView, and recycling it would crash on the next draw
    // ("trying to use a recycled bitmap"). Dropping the cache's reference is enough; the GC reclaims
    // the bitmap once the view releases it too. Render scale is capped (see RenderScale) so each
    // bitmap stays small and total memory stays bounded by the LRU byte budget.
    private val lru = LruByteSizedCache<PageKey, Bitmap>(
        maxBytes = maxBytes,
        sizeOf = { it.allocationByteCount },
    )
    fun get(key: PageKey): Bitmap? = lru.get(key)
    fun put(key: PageKey, bmp: Bitmap) = lru.put(key, bmp)
    fun clear() = lru.clear()

    companion object {
        /** Quantize a float scale to an int key bucket (3 decimal places). */
        fun scaleMilli(scale: Float): Int = (scale * 1000f).toInt()
    }
}
