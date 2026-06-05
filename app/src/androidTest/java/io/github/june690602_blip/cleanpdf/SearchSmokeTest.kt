package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchSmokeTest {
    @Test fun findsTextInSample() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "search_sample.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val doc = PdfDocument.open(out.absolutePath)
        val hits = doc.search("Page")   // 샘플에 "CleanPDF - Page N" 존재
        val noHits = doc.search("zzqx_nonexistent_needle_zzqx")
        doc.close()

        assertTrue("'Page' should be found in the sample", hits.isNotEmpty())
        assertTrue("hit page should be valid", hits.all { it.page in 0 until 3 })
        assertTrue("a nonexistent needle yields no hits", noHits.isEmpty())
    }
}
