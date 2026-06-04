package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RenderSmokeTest {

    @Test fun rendersSamplePageWithVisibleInk() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "sample.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val doc = PdfDocument.open(out.absolutePath)
        assertTrue("PDF should have >= 1 page", doc.pageCount >= 1)

        val bmp = doc.renderPage(0, scale = 1.5f)
        doc.close()

        // A real page must contain at least one non-white pixel.
        var hasInk = false
        loop@ for (y in 0 until bmp.height step 7) {
            for (x in 0 until bmp.width step 7) {
                if (bmp.getPixel(x, y) and 0x00FFFFFF != 0x00FFFFFF) { hasInk = true; break@loop }
            }
        }
        assertTrue("rendered page should have visible content", hasInk)
    }
}
