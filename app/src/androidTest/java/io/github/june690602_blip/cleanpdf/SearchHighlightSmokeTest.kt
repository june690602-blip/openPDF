package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.pdf.SearchCursor
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import io.github.june690602_blip.cleanpdf.view.HighlightGeometry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchHighlightSmokeTest {
    @Test fun cursorWrapsAndGeometryScales() {
        val hits = listOf(SearchHit(0, 10f, 20f, 30f, 25f), SearchHit(1, 0f, 0f, 5f, 5f))
        var c = SearchCursor(hits)
        assertEquals(1, c.position)
        c = c.prev()                              // wraps to last
        assertEquals(2, c.position)
        assertEquals(1, c.current!!.page)

        val px = HighlightGeometry.toPixels(hits[0], pageWidthPts = 100f, contentWidth = 200f)
        assertEquals(20f, px[0], 1e-4f); assertEquals(40f, px[1], 1e-4f)
    }
}
