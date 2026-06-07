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
    // 1x1 PNG
    private val png = intArrayOf(
        0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,0x0D,0x49,0x48,0x44,0x52,0,0,0,1,0,0,0,1,
        8,6,0,0,0,0x1F,0x15,0xC4,0x89,0,0,0,0x0D,0x49,0x44,0x41,0x54,0x78,0x9C,0x62,0,1,0,0,5,
        0,1,0x0D,0x0A,0x2D,0xB4,0,0,0,0,0x49,0x45,0x4E,0x44,0xAE,0x42,0x60,0x82
    ).map { it.toByte() }.toByteArray()

    @Test fun extractsTableAndInlineImage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "structured.docx")
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:w" xmlns:r="urn:r" xmlns:a="urn:a"><w:body>
              <w:p><w:r><w:t>계약서 본문</w:t></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                          <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
            </w:body></w:document>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="urn:rel">
              <Relationship Id="rId1" Type="urn:image" Target="media/img1.png"/>
            </Relationships>"""
        ZipOutputStream(docx.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(doc.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("word/_rels/document.xml.rels")); z.write(rels.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("word/media/img1.png")); z.write(png); z.closeEntry()
        }
        val result = DocxExtractor.extract(docx)
        assertTrue(result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertTrue("table", blocks.any { it is DocBlock.Table && it.rows == listOf(listOf("항목","금액")) })
        assertTrue("image", blocks.any { it is DocBlock.Image && it.mime == "image/png" })
        assertTrue("para", blocks.any { it is DocBlock.Para && it.text.contains("계약서 본문") })
    }
}
