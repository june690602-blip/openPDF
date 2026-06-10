package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PageWorldTest {
    // Page 0: 100x200 pts (widest), Page 1: 80x100 pts (narrower → centered). gap 10 pts.
    private val sizes = listOf(PageSize(100f, 200f), PageSize(80f, 100f))
    private fun world() = PageWorld.build(sizes, gap = 10f)

    @Test fun layoutDimensions() {
        val w = world()
        assertEquals(100f, w.maxWidth, 0.001f)
        assertEquals(310f, w.totalHeight, 0.001f) // 200 + 10 gap + 100
    }

    @Test fun narrowerPageIsCenteredAndTopsAccumulate() {
        val w = world()
        assertEquals(0f, w.offsetX(0), 0.001f)    // 100 wide, no offset
        assertEquals(10f, w.offsetX(1), 0.001f)   // (100-80)/2
        assertEquals(0f, w.top(0), 0.001f)
        assertEquals(210f, w.top(1), 0.001f)      // 200 + 10 gap
    }

    @Test fun pdfToDocAddsPageOffset() {
        val d = world().pdfToDoc(1, 5f, 5f)
        assertEquals(15f, d[0], 0.001f)  // offsetX(1)=10 + 5
        assertEquals(215f, d[1], 0.001f) // top(1)=210 + 5
    }

    @Test fun docToPdfRoundTrips() {
        val d = world().docToPdf(15f, 215f)
        assertEquals(1f, d[0], 0.001f)   // page 1
        assertEquals(5f, d[1], 0.001f)   // xPt
        assertEquals(5f, d[2], 0.001f)   // yPt
    }

    @Test fun pageAtDocY_bodyGapAndOutOfRange() {
        val w = world()
        assertEquals(0, w.pageAtDocY(100f))  // in page 0 body
        assertEquals(0, w.pageAtDocY(205f))  // gap between 0 and 1 → previous page
        assertEquals(1, w.pageAtDocY(250f))  // in page 1 body
        assertEquals(1, w.pageAtDocY(999f))  // below everything → last page
        assertEquals(0, w.pageAtDocY(-5f))   // above everything → first page
    }

    @Test fun visiblePagesCulling() {
        val w = world()
        assertEquals(0..0, w.visiblePages(0f, 50f))     // only page 0
        assertEquals(1..1, w.visiblePages(205f, 260f))  // only page 1 (page 0 ends at 200)
        assertEquals(0..1, w.visiblePages(150f, 250f))  // both
    }

    @Test fun emptyDocument() {
        val w = PageWorld.build(emptyList(), gap = 10f)
        assertEquals(0, w.pageCount)
        assertEquals(0f, w.totalHeight, 0.001f)
        assertEquals(IntRange.EMPTY, w.visiblePages(0f, 100f))
        assertEquals(-1f, w.docToPdf(0f, 0f)[0], 0.001f)
    }
}
