package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocProbeTest {
    private val zipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private fun zipWith(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("probe", ".zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(ZipEntry(n)); zos.write(c.toByteArray()); zos.closeEntry()
            }
        }
        return f
    }

    @Test fun zipWithDocumentXmlIsDocx() {
        val f = zipWith("word/document.xml" to "<x/>")
        assertEquals(DocFormat.DOCX, DocProbe.refine(f, zipHead))
    }
    @Test fun zipWithHwpMimetypeIsHwpx() {
        val f = zipWith("mimetype" to "application/hwp+zip")
        assertEquals(DocFormat.HWPX, DocProbe.refine(f, zipHead))
    }
    @Test fun zipWithSectionIsHwpx() {
        val f = zipWith("Contents/section0.xml" to "<x/>")
        assertEquals(DocFormat.HWPX, DocProbe.refine(f, zipHead))
    }
    @Test fun unknownZipIsUnknown() {
        val f = zipWith("random.txt" to "hi")
        assertEquals(DocFormat.UNKNOWN, DocProbe.refine(f, zipHead))
    }
}
