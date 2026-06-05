package io.github.june690602_blip.cleanpdf.view

/**
 * Pure PDF-point ↔ cell-pixel transforms for text selection. Mirrors [HighlightGeometry] but adds
 * the inverse (pixel→point) needed to map touches back to PDF space. android-free → JVM-testable.
 * scale = contentWidth / pageWidthPts (x and y share it; the cell keeps the page aspect ratio).
 */
object SelectionGeometry {
    fun scale(pageWidthPts: Float, contentWidth: Float): Float =
        if (pageWidthPts <= 0f) 0f else contentWidth / pageWidthPts

    /** Cell pixel ([px],[py]) → PDF point, as FloatArray[xPt, yPt]. */
    fun toPdfPoint(px: Float, py: Float, scale: Float): FloatArray =
        if (scale <= 0f) floatArrayOf(0f, 0f) else floatArrayOf(px / scale, py / scale)

    /** PDF-point rect[x0,y0,x1,y1] → cell-pixel rect[x0,y0,x1,y1]. */
    fun rectToPixels(rectPts: FloatArray, scale: Float): FloatArray =
        floatArrayOf(rectPts[0] * scale, rectPts[1] * scale, rectPts[2] * scale, rectPts[3] * scale)

    /** PDF point ([xPt],[yPt]) → cell-pixel point, as FloatArray[x, y]. */
    fun pointToPixels(xPt: Float, yPt: Float, scale: Float): FloatArray =
        floatArrayOf(xPt * scale, yPt * scale)
}
