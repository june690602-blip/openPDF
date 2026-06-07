package io.github.june690602_blip.cleanpdf

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocTextActivitySmokeTest {
    @Test fun launchesAndRendersDocx() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "activity_smoke.docx")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:x"><w:body>
              <w:p><w:r><w:t>액티비티 스모크</w:t></w:r></w:p>
            </w:body></w:document>"""
        ZipOutputStream(docx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val intent = DocTextActivity.intent(ctx, docx, DocFormat.DOCX, "activity_smoke.docx")
        ActivityScenario.launch<DocTextActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
