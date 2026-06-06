package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmlFlowTextTest {
    private fun parse(xml: String): List<String> {
        val p: XmlPullParser = Xml.newPullParser()
        p.setInput(StringReader(xml))
        return XmlFlowText.parse(p)
    }

    @Test fun paragraphsBecomeLines() {
        val out = parse(
            """<w:document xmlns:w="x"><w:body>
                 <w:p><w:r><w:t>첫 문단</w:t></w:r></w:p>
                 <w:p><w:r><w:t>둘째</w:t></w:r></w:p>
               </w:body></w:document>"""
        )
        assertEquals(listOf("첫 문단", "둘째"), out)
    }

    @Test fun runsConcatAndTabAndBreak() {
        val out = parse(
            """<w:p xmlns:w="x"><w:r><w:t>A</w:t></w:r><w:r><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>"""
        )
        assertEquals(listOf("A\tB\nC"), out)
    }

    @Test fun tableFlattensCellsTabRowNewline() {
        val out = parse(
            """<w:tbl xmlns:w="x">
                 <w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                       <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr>
                 <w:tr><w:tc><w:p><w:r><w:t>철근</w:t></w:r></w:p></w:tc>
                       <w:tc><w:p><w:r><w:t>1000</w:t></w:r></w:p></w:tc></w:tr>
               </w:tbl>"""
        )
        assertEquals(listOf("항목\t금액", "철근\t1000"), out)
    }

    @Test fun preservesPreservedSpacesNotIndentation() {
        val out = parse("""<w:p xmlns:w="x">
              <w:r><w:t>가 </w:t></w:r><w:r><w:t>나</w:t></w:r>
            </w:p>""")
        assertEquals(listOf("가 나"), out)
    }
}
