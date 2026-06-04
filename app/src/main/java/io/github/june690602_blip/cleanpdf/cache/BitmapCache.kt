package io.github.june690602_blip.cleanpdf.cache

import android.graphics.Bitmap

/** Cache key: a page rendered at a quantized scale. */
data class PageKey(val page: Int, val scaleMilli: Int)

class BitmapCache(maxBytes: Int) {
    private val lru = LruByteSizedCache<PageKey, Bitmap>(
        maxBytes = maxBytes,
        sizeOf = { it.allocationByteCount },
        onEvict = { _, bmp -> if (!bmp.isRecycled) bmp.recycle() },
    )
    fun get(key: PageKey): Bitmap? = lru.get(key)?.takeIf { !it.isRecycled }
    fun put(key: PageKey, bmp: Bitmap) = lru.put(key, bmp)
    fun clear() = lru.clear()

    companion object {
        /** Quantize a float scale to an int key bucket (3 decimal places). */
        fun scaleMilli(scale: Float): Int = (scale * 1000f).toInt()
    }
}
