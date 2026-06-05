package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightGeometryTest {
    @Test fun scaleIsContentWidthOverPageWidth() {
        assertEquals(2f, HighlightGeometry.scale(pageWidthPts = 100f, contentWidth = 200f), 1e-4f)
    }

    @Test fun degeneratePageWidthGivesZeroScale() {
        assertEquals(0f, HighlightGeometry.scale(0f, 200f), 1e-4f)
    }

    @Test fun toPixelsScalesAllCorners() {
        val hit = SearchHit(0, x0 = 10f, y0 = 20f, x1 = 30f, y1 = 25f)
        // page 100pt wide rendered 200px wide -> scale 2
        val px = HighlightGeometry.toPixels(hit, pageWidthPts = 100f, contentWidth = 200f)
        assertArrayEquals(floatArrayOf(20f, 40f, 60f, 50f), px, 1e-4f)
    }
}
