package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderScaleTest {
    @Test fun usesRawScaleWhenUnderCap() {
        // small page + modest target: raw = 1000/500 = 2.0, well under the 32MB cap
        val s = RenderScale.forPage(targetWidthPx = 1000f, page = PageSize(500f, 700f))
        assertEquals(2f, s, 0.001f)
    }

    @Test fun capsScaleSoBitmapStaysWithinBudget() {
        val page = PageSize(595f, 842f) // A4 in points
        val cap = 32 * 1024 * 1024
        val s = RenderScale.forPage(targetWidthPx = 595f * 8f, page = page, maxBytes = cap)
        val bytes = (page.width * s) * (page.height * s) * 4f
        assertTrue("bitmap bytes ($bytes) must stay within cap", bytes <= cap + 1f)
        assertTrue("cap must reduce below the raw 8x scale", s < 8f)
    }

    @Test fun degeneratePageReturnsOne() {
        assertEquals(1f, RenderScale.forPage(1000f, PageSize(0f, 0f)), 0.001f)
        assertEquals(1f, RenderScale.forPage(0f, PageSize(595f, 842f)), 0.001f)
    }
}
