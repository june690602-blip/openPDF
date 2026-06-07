package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocHtmlTest {
    @Test fun escapesDangerousChars() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", DocHtml.escape("<b> & \"x\" 'y'"))
    }
    @Test fun noScriptInjection() {
        val html = DocHtml.toHtml(DocText(listOf("<script>alert(1)</script>")))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test fun wrapsInPreAndKeepsLines() {
        val html = DocHtml.toHtml(DocText(listOf("줄1", "줄2")))
        assertTrue(html.contains("<pre"))
        assertTrue(html.contains("줄1\n줄2"))
        assertTrue(html.contains("charset=\"utf-8\""))
    }
}
