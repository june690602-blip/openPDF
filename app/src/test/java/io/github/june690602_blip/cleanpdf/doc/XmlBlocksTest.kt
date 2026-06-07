package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmlBlocksTest {
    private fun parse(xml: String, resolve: (String) -> ByteArray? = { null }): List<DocBlock> {
        val p: XmlPullParser = Xml.newPullParser()
        p.setInput(StringReader(xml))
        return XmlBlocks.parse(p, resolve)
    }
    private val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test fun paragraphsAndTable() {
        val out = parse(
            """<w:document xmlns:w="x"><w:body>
                 <w:p><w:r><w:t>머리말</w:t></w:r></w:p>
                 <w:tbl>
                   <w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                         <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr>
                 </w:tbl>
               </w:body></w:document>"""
        )
        assertEquals(DocBlock.Para("머리말"), out[0])
        assertEquals(DocBlock.Table(listOf(listOf("항목", "금액"))), out[1])
    }

    @Test fun imageRefResolvedToImageBlock() {
        val out = parse(
            """<w:p xmlns:w="x" xmlns:r="y" xmlns:a="z"><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>""",
            resolve = { id -> if (id == "rId7") pngHeader + ByteArray(20) else null }
        )
        assertTrue("expected an Image block", out.any { it is DocBlock.Image && it.mime == "image/png" })
    }

    @Test fun unsupportedImageOmitted() {
        val out = parse(
            """<w:p xmlns:w="x" xmlns:r="y" xmlns:a="z"><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:p>""",
            resolve = { byteArrayOf(0xD7.toByte(), 0xCD.toByte(), 0xC6.toByte(), 0x9A.toByte()) } // WMF
        )
        assertTrue("WMF must be omitted", out.none { it is DocBlock.Image })
    }
}
