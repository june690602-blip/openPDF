package io.github.june690602_blip.cleanpdf.pdf

/**
 * One laid-out character on a page: its Unicode [codepoint], bounding box in PDF points
 * (y-down, top-left origin — same space as [SearchHit]), and the 0-based [lineIndex] of the text
 * line it belongs to (lines are in fitz reading order). Immutable, android-free.
 */
data class PageChar(
    val codepoint: Int,
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val lineIndex: Int,
)

/**
 * Immutable text content of one page: [chars] flattened in reading order. Holds no android/fitz
 * types, so the selection logic over it is JVM-unit-testable.
 */
data class PageText(val pageIndex: Int, val chars: List<PageChar>) {
    val isEmpty: Boolean get() = chars.isEmpty()
}
