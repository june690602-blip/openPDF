package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFilterTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun detectsRasterMimes() {
        assertEquals("image/png", ImageFilter.mimeOf(b(0x89, 0x50, 0x4E, 0x47, 0, 0)))
        assertEquals("image/jpeg", ImageFilter.mimeOf(b(0xFF, 0xD8, 0xFF, 0)))
        assertEquals("image/gif", ImageFilter.mimeOf(b(0x47, 0x49, 0x46, 0x38)))
        assertEquals("image/bmp", ImageFilter.mimeOf(b(0x42, 0x4D, 0, 0)))
    }
    @Test fun rejectsNonRaster() {
        assertNull(ImageFilter.mimeOf(b(0xD7, 0xCD, 0xC6, 0x9A)))   // WMF
        assertNull(ImageFilter.mimeOf(b(0x01, 0x00, 0x00, 0x00)))   // EMF
        assertNull(ImageFilter.mimeOf(b(0x00, 0x01, 0x02)))         // unknown
    }
    @Test fun classifyOkOversizedUnsupported() {
        val png = b(0x89, 0x50, 0x4E, 0x47) + ByteArray(10)
        val ok = ImageFilter.classify(png, 0)
        assertTrue(ok is ImageFilter.Outcome.Ok)
        assertEquals("image/png", (ok as ImageFilter.Outcome.Ok).image.mime)
        // unsupported
        assertTrue(ImageFilter.classify(b(0xD7, 0xCD, 0xC6, 0x9A), 0) is ImageFilter.Outcome.Unsupported)
        // oversized: cumulative over total cap
        val big = b(0x89, 0x50, 0x4E, 0x47) + ByteArray(10)
        assertTrue(ImageFilter.classify(big, ImageFilter.MAX_TOTAL_BYTES) is ImageFilter.Outcome.Oversized)
    }
}
