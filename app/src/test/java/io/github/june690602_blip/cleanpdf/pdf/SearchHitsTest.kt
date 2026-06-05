package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHitsTest {
    private fun hit(page: Int) = SearchHit(page, 0f, 0f, 1f, 1f)

    @Test fun labelsAreOneBasedPageNumbers() {
        val labels = SearchHits.labels(listOf(hit(0), hit(2), hit(2)))
        assertEquals(listOf("1쪽", "3쪽", "3쪽"), labels)
    }

    @Test fun emptyHitsGiveEmptyLabels() {
        assertEquals(emptyList<String>(), SearchHits.labels(emptyList()))
    }
}
