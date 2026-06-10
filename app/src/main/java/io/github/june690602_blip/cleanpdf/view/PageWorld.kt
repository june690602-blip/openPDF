package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.PageSize

/**
 * Vertical-stack layout of pages in "document space" (units = PDF points). Pages keep their own
 * point size, are centered horizontally within [maxWidth], and stacked top-to-bottom with [gap]
 * points between them. The reader maps document space → screen with a single Matrix, so this class
 * holds **no Android types** and is JVM-unit-testable (the Matrix mapping lives in the view).
 *
 * PDF-point coords of page i map to doc coords by a pure offset (scale 1):
 *   docX = offsetX(i) + xPt,  docY = top(i) + yPt.
 */
class PageWorld private constructor(
    private val offsetsX: FloatArray,
    private val tops: FloatArray,
    private val sizes: List<PageSize>,
    /** Width (pts) of the widest page — the document-space content width. */
    val maxWidth: Float,
    /** Total document height (pts), including gaps between pages. */
    val totalHeight: Float,
) {
    val pageCount: Int get() = sizes.size

    fun offsetX(i: Int): Float = offsetsX[i]
    fun top(i: Int): Float = tops[i]
    fun width(i: Int): Float = sizes[i].width
    fun height(i: Int): Float = sizes[i].height

    /** Page [i]'s rect in doc space as [left, top, right, bottom]. */
    fun pageRect(i: Int): FloatArray =
        floatArrayOf(offsetsX[i], tops[i], offsetsX[i] + sizes[i].width, tops[i] + sizes[i].height)

    /** PDF point ([xPt],[yPt]) on page [i] → doc coords [docX, docY]. */
    fun pdfToDoc(i: Int, xPt: Float, yPt: Float): FloatArray =
        floatArrayOf(offsetsX[i] + xPt, tops[i] + yPt)

    /**
     * Doc coords → [page, xPt, yPt]. Picks the page whose vertical band contains [docY]; a point in
     * a gap (or out of range) snaps to the nearest page. [xPt]/[yPt] may fall outside the page rect
     * (callers like TextSelection snap to the nearest content). Returns page = -1 if empty.
     */
    fun docToPdf(docX: Float, docY: Float): FloatArray {
        if (sizes.isEmpty()) return floatArrayOf(-1f, 0f, 0f)
        val i = pageAtDocY(docY)
        return floatArrayOf(i.toFloat(), docX - offsetsX[i], docY - tops[i])
    }

    /** Index of the page whose vertical span contains [docY], else the nearest page. */
    fun pageAtDocY(docY: Float): Int {
        for (i in sizes.indices) {
            if (docY < tops[i]) return if (i == 0) 0 else i - 1  // in a gap above page i → previous
            if (docY <= tops[i] + sizes[i].height) return i
        }
        return sizes.size - 1
    }

    /** Inclusive [first,last] page indices whose body intersects the doc-Y range [[docTop],[docBottom]]. */
    fun visiblePages(docTop: Float, docBottom: Float): IntRange {
        if (sizes.isEmpty()) return IntRange.EMPTY
        var first = -1
        var last = -1
        for (i in sizes.indices) {
            val t = tops[i]
            val b = t + sizes[i].height
            if (b >= docTop && t <= docBottom) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    companion object {
        /** Build the layout for [sizes] with [gap] points between consecutive pages. */
        fun build(sizes: List<PageSize>, gap: Float): PageWorld {
            if (sizes.isEmpty()) return PageWorld(FloatArray(0), FloatArray(0), sizes, 0f, 0f)
            val maxW = sizes.maxOf { it.width }
            val offsetsX = FloatArray(sizes.size)
            val tops = FloatArray(sizes.size)
            var y = 0f
            for (i in sizes.indices) {
                offsetsX[i] = (maxW - sizes[i].width) / 2f   // center narrower pages within maxWidth
                tops[i] = y
                y += sizes[i].height + (if (i < sizes.size - 1) gap else 0f)
            }
            return PageWorld(offsetsX, tops, sizes, maxW, y)
        }
    }
}
