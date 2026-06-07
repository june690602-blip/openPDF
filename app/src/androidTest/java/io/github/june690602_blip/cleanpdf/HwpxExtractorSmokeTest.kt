package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpxExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class HwpxExtractorSmokeTest {
    private fun section(text: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <hs:sec xmlns:hs="urn:s" xmlns:hp="urn:p">
             <hp:p><hp:run><hp:t>$text</hp:t></hp:run></hp:p>
           </hs:sec>"""

    @Test fun extractsSectionsInOrder() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hwpx = File(ctx.cacheDir, "smoke.hwpx")
        ZipOutputStream(hwpx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("Contents/section1.xml")); zos.write(section("둘째장").toByteArray(Charsets.UTF_8)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("Contents/section0.xml")); zos.write(section("첫장").toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val result = HwpxExtractor.extract(hwpx)
        assertTrue(result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertEquals(
            listOf("첫장", "둘째장"),
            blocks.filterIsInstance<DocBlock.Para>().map { it.text }
        )
    }
}
