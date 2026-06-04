package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.PageSize

/**
 * Vertical stack layout for continuous scroll. All values in pixels at the given [zoom].
 * Each page is scaled so its width == fitWidthPx*zoom; heights follow aspect; gaps scale too.
 */
class PageLayout private constructor(
    private val tops: FloatArray,
    private val heights: FloatArray,
    val totalHeight: Float,
    val contentWidth: Float,
) {
    val pageCount: Int get() = heights.size
    fun pageTop(i: Int): Float = tops[i]
    fun pageHeight(i: Int): Float = heights[i]

    /** Inclusive range of page indices intersecting [scrollY, scrollY+viewportH). */
    fun visiblePages(scrollY: Float, viewportH: Float): IntRange {
        if (pageCount == 0) return IntRange.EMPTY
        val bottom = scrollY + viewportH
        var first = pageCount - 1
        var last = 0
        var found = false
        for (i in 0 until pageCount) {
            val t = tops[i]; val b = t + heights[i]
            if (b > scrollY && t < bottom) {
                if (i < first) first = i
                if (i > last) last = i
                found = true
            }
        }
        return if (found) first..last else IntRange.EMPTY
    }

    companion object {
        fun compute(sizes: List<PageSize>, fitWidthPx: Int, gapPx: Int, zoom: Float): PageLayout {
            val w = fitWidthPx * zoom
            val gap = gapPx * zoom
            val tops = FloatArray(sizes.size)
            val heights = FloatArray(sizes.size)
            var y = 0f
            for (i in sizes.indices) {
                val h = if (sizes[i].aspect == 0f) w else w / sizes[i].aspect
                tops[i] = y
                heights[i] = h
                y += h + (if (i < sizes.size - 1) gap else 0f)
            }
            return PageLayout(tops, heights, y, w)
        }
    }
}
