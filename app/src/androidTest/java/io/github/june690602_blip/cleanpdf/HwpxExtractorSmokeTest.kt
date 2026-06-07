package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpxExtractor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class HwpxExtractorSmokeTest {
    private val png = intArrayOf(
        0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,0x0D,0x49,0x48,0x44,0x52,0,0,0,1,0,0,0,1,
        8,6,0,0,0,0x1F,0x15,0xC4,0x89,0,0,0,0x0D,0x49,0x44,0x41,0x54,0x78,0x9C,0x62,0,1,0,0,5,
        0,1,0x0D,0x0A,0x2D,0xB4,0,0,0,0,0x49,0x45,0x4E,0x44,0xAE,0x42,0x60,0x82
    ).map { it.toByte() }.toByteArray()

    @Test fun extractsTableAndImage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hwpx = File(ctx.cacheDir, "structured.hwpx")
        val sec = """<?xml version="1.0" encoding="UTF-8"?>
            <hs:sec xmlns:hs="urn:s" xmlns:hp="urn:p"><hp:p><hp:run><hp:t>한글 본문</hp:t></hp:run></hp:p>
              <hp:tbl><hp:tr><hp:tc><hp:subList><hp:p><hp:run><hp:t>가</hp:t></hp:run></hp:p></hp:subList></hp:tc>
                            <hp:tc><hp:subList><hp:p><hp:run><hp:t>나</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr></hp:tbl>
              <hp:p><hp:run><hp:pic binaryItemIDRef="image1"/></hp:run></hp:p></hs:sec>"""
        val hpf = """<?xml version="1.0" encoding="UTF-8"?>
            <opf:package xmlns:opf="urn:opf"><opf:manifest>
              <opf:item id="image1" href="BinData/image1.png" media-type="image/png"/>
            </opf:manifest></opf:package>"""
        ZipOutputStream(hwpx.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Contents/section0.xml")); z.write(sec.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("Contents/content.hpf")); z.write(hpf.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("BinData/image1.png")); z.write(png); z.closeEntry()
        }
        val result = HwpxExtractor.extract(hwpx)
        assertTrue(result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertTrue("table", blocks.any { it is DocBlock.Table && it.rows == listOf(listOf("가","나")) })
        assertTrue("image", blocks.any { it is DocBlock.Image && it.mime == "image/png" })
        assertTrue("para", blocks.any { it is DocBlock.Para && it.text.contains("한글 본문") })
    }
}
