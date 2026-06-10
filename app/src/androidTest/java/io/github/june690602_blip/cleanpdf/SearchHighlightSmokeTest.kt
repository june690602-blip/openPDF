package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.SearchCursor
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import io.github.june690602_blip.cleanpdf.view.PageWorld
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchHighlightSmokeTest {
    @Test fun cursorWrapsAndPageWorldMapsHit() {
        val hits = listOf(SearchHit(0, 10f, 20f, 30f, 25f), SearchHit(1, 0f, 0f, 5f, 5f))
        var c = SearchCursor(hits)
        assertEquals(1, c.position)
        c = c.prev()                              // wraps to last
        assertEquals(2, c.position)
        assertEquals(1, c.current!!.page)

        // The hit's PDF point maps into document space by the page's offset (matrix → screen later).
        val world = PageWorld.build(listOf(PageSize(100f, 200f), PageSize(100f, 50f)), gap = 10f)
        val doc = world.pdfToDoc(1, hits[1].x0, hits[1].y0)
        assertEquals(0f, doc[0], 1e-4f)           // page 1 same width → no x offset
        assertEquals(210f, doc[1], 1e-4f)         // page 1 top = 200 + 10 gap
    }
}
