package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    // two pages: 100x200 and 100x100 pts; fit width 100px, gap 10px, zoom 1.0
    private val sizes = listOf(PageSize(100f, 200f), PageSize(100f, 100f))

    @Test fun heightsScaleToFitWidth() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        assertEquals(200f, l.pageHeight(0), 0.001f) // 100->100 wide, 200->200 tall
        assertEquals(100f, l.pageHeight(1), 0.001f)
    }

    @Test fun topsAccumulateWithGaps() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        assertEquals(0f, l.pageTop(0), 0.001f)
        assertEquals(210f, l.pageTop(1), 0.001f) // 200 + 10 gap
    }

    @Test fun totalHeightIncludesTrailingPages() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        assertEquals(310f, l.totalHeight, 0.001f) // 200 + 10 + 100
    }

    @Test fun zoomMultipliesEverything() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 2f)
        assertEquals(400f, l.pageHeight(0), 0.001f)
        assertEquals(420f, l.pageTop(1), 0.001f) // page0 height (400) + scaled gap (20); gap scales with zoom
    }

    @Test fun visibleRangeFindsOverlappingPages() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        // viewport [205,260): page0 ends at 200 (<205, not visible), page1 is [210,310] (visible)
        val r = l.visiblePages(scrollY = 205f, viewportH = 55f)
        assertEquals(1, r.first)
        assertEquals(1, r.last)
    }
}
