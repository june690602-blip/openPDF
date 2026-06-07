package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.DocxExtractor
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocxExtractorSmokeTest {
    @Test fun extractsParagraphsAndTable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "smoke.docx")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:x"><w:body>
              <w:p><w:r><w:t>계약서 본문</w:t></w:r></w:p>
              <w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl>
            </w:body></w:document>"""
        ZipOutputStream(docx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }

        val result = DocxExtractor.extract(docx)
        assertTrue("expected Success, got $result", result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertTrue(blocks.any { it is DocBlock.Para && it.text.contains("계약서 본문") })
        assertTrue("table row flattened to para", blocks.any { it is DocBlock.Para && it.text == "항목\t금액" })
    }
}
