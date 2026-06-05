package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.SearchHit

/**
 * Pure PDF-point → cell-pixel conversion for search highlights. A page [pageWidthPts] wide
 * (PDF points) is rendered [contentWidth] px wide; the cell keeps aspect, so x and y share one
 * scale. Returns pixels as [left, top, right, bottom] (no android types → JVM-unit-testable).
 */
object HighlightGeometry {
    fun scale(pageWidthPts: Float, contentWidth: Float): Float =
        if (pageWidthPts <= 0f) 0f else contentWidth / pageWidthPts

    fun toPixels(hit: SearchHit, pageWidthPts: Float, contentWidth: Float): FloatArray {
        val s = scale(pageWidthPts, contentWidth)
        return floatArrayOf(hit.x0 * s, hit.y0 * s, hit.x1 * s, hit.y1 * s)
    }
}
