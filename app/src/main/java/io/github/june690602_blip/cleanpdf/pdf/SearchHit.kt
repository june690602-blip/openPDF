package io.github.june690602_blip.cleanpdf.pdf

/** One search hit: its 0-based [page] and bounding box in PDF points. Immutable. */
data class SearchHit(val page: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Pure helpers for presenting search hits. */
object SearchHits {
    /** One label per hit: "<1-based page>쪽". */
    fun labels(hits: List<SearchHit>): List<String> = hits.map { "${it.page + 1}쪽" }
}
