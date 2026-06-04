package io.github.june690602_blip.cleanpdf.pdf

import kotlin.math.sqrt

/** Pure helper for choosing a memory-safe page render scale. */
object RenderScale {
    /** ARGB_8888 ceiling per page bitmap (~32MB); stays well under the cache byte budget. */
    const val MAX_BITMAP_BYTES: Int = 32 * 1024 * 1024
    private const val BYTES_PER_PX = 4f

    /**
     * Scale to render [page] (size in PDF points) so its bitmap width ≈ [targetWidthPx],
     * capped so the bitmap never exceeds [maxBytes]. Above the cap the page is shown upscaled
     * (pixel-sharp high zoom requires tiling — a later phase). Returns 1f for degenerate input.
     */
    fun forPage(targetWidthPx: Float, page: PageSize, maxBytes: Int = MAX_BITMAP_BYTES): Float {
        if (page.width <= 0f || page.height <= 0f || targetWidthPx <= 0f) return 1f
        val raw = targetWidthPx / page.width
        val maxScale = sqrt(maxBytes / (page.width * page.height * BYTES_PER_PX))
        return minOf(raw, maxScale)
    }
}
