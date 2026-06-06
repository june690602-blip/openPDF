// DocFormatTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Test

class DocFormatTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val pk = bytes(0x50, 0x4B, 0x03, 0x04)
    private val ole = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
    private val pdf = "%PDF-1.7".toByteArray(Charsets.US_ASCII)

    @Test fun byDocxExtension() = assertEquals(DocFormat.DOCX, detectFormat("a.DOCX", ByteArray(0)))
    @Test fun byHwpxExtension() = assertEquals(DocFormat.HWPX, detectFormat("a.hwpx", ByteArray(0)))
    @Test fun byHwpExtension() = assertEquals(DocFormat.HWP, detectFormat("a.hwp", ByteArray(0)))
    @Test fun byPdfExtensionOrMagic() {
        assertEquals(DocFormat.PDF, detectFormat("a.pdf", ByteArray(0)))
        assertEquals(DocFormat.PDF, detectFormat("blob", pdf))
    }
    @Test fun zipWithoutExtensionIsUnknown() = assertEquals(DocFormat.UNKNOWN, detectFormat("blob", pk))
    @Test fun oleWithoutExtensionIsUnknown() = assertEquals(DocFormat.UNKNOWN, detectFormat("blob", ole))
    @Test fun magicHelpers() {
        assertEquals(true, isZipMagic(pk)); assertEquals(true, isOleMagic(ole))
        assertEquals(false, isZipMagic(ole)); assertEquals(false, isOleMagic(pk))
    }
}
