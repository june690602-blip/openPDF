package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.pdf.TextSelection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextExtractionSmokeTest {
    @Test fun extractsTextFromSample() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "extract_sample.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val doc = PdfDocument.open(out.absolutePath)
        val text = doc.extractText(0)
        doc.close()

        assertTrue("page 0 should yield chars", text.chars.isNotEmpty())
        val all = TextSelection.selectedText(text, 0..(text.chars.size - 1))
        assertTrue("extracted text should contain 'Page'", all.contains("Page"))
    }
}
