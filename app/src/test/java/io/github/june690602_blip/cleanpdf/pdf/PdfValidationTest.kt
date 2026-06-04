package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfValidationTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun acceptsByPdfExtension() {
        assertTrue(isLikelyPdf("report.pdf", bytes("garbage")))
    }

    @Test fun acceptsByMagicHeaderRegardlessOfName() {
        assertTrue(isLikelyPdf("blob_bin", bytes("%PDF-1.7\n...")))
    }

    @Test fun extensionIsCaseInsensitive() {
        assertTrue(isLikelyPdf("DRAWING.PDF", ByteArray(0)))
    }

    @Test fun rejectsNonPdf() {
        assertFalse(isLikelyPdf("photo.jpg", bytes("ÿØÿ")))
    }

    @Test fun rejectsNullNameWithoutMagic() {
        assertFalse(isLikelyPdf(null, bytes("nope")))
    }
}
