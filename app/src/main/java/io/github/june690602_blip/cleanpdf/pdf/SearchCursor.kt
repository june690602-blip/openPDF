package io.github.june690602_blip.cleanpdf.pdf

/** Immutable cursor over [hits] with wrapping next/prev. Empty-safe. */
data class SearchCursor(val hits: List<SearchHit>, val index: Int = 0) {
    val size: Int get() = hits.size

    /** 1-based position for display ("3 / 12"); 0 when empty. */
    val position: Int get() = if (hits.isEmpty()) 0 else index + 1

    val current: SearchHit? get() = hits.getOrNull(index)

    fun next(): SearchCursor = if (hits.isEmpty()) this else copy(index = (index + 1) % hits.size)

    fun prev(): SearchCursor = if (hits.isEmpty()) this else copy(index = (index - 1 + hits.size) % hits.size)
}
