package io.github.june690602_blip.cleanpdf

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocFindSmokeTest {
    @Test fun findReportsMatches() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "find.docx")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:x"><w:body>
              <w:p><w:r><w:t>금액 금액 금액</w:t></w:r></w:p>
            </w:body></w:document>"""
        ZipOutputStream(docx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val intent = DocTextActivity.intent(ctx, docx, DocFormat.DOCX, "find.docx")
        var count = 0
        val latch = CountDownLatch(1)
        ActivityScenario.launch<DocTextActivity>(intent).use { scenario ->
            Thread.sleep(800) // let the WebView load the HTML
            scenario.onActivity { act ->
                val web = act.findViewById<WebView>(R.id.doc_web)
                web.setFindListener { _, c, done -> if (done) { count = c; latch.countDown() } }
                web.findAllAsync("금액")
            }
            assertTrue("find callback fired", latch.await(5, TimeUnit.SECONDS))
            assertTrue("expected >=3 matches, got $count", count >= 3)
        }
    }
}
