package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocHtmlRenderTest {
    @Test fun rendersParaTableImage() {
        val html = DocHtml.render(
            listOf(
                DocBlock.Para("본문 <글자>"),
                DocBlock.Table(listOf(listOf("항목", "금액"), listOf("철근", "1000"))),
                DocBlock.Image("image/png", byteArrayOf(1, 2, 3))
            )
        )
        assertTrue(html.contains("<p>본문 &lt;글자&gt;</p>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>항목</td><td>금액</td>"))
        assertTrue(html.contains("<img src=\"data:image/png;base64,"))
        assertFalse(html.contains("<글자>"))           // escaped
        assertTrue(html.contains("charset=\"utf-8\""))
    }
}
