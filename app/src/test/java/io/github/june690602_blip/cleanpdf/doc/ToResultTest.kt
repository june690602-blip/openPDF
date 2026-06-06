package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToResultTest {
    @Test fun blankOnlyIsEmpty() {
        assertEquals(ExtractResult.Empty, toResult(listOf("", "   ", "\t")))
    }
    @Test fun emptyListIsEmpty() {
        assertEquals(ExtractResult.Empty, toResult(emptyList()))
    }
    @Test fun anyTextIsSuccess() {
        val r = toResult(listOf("", "본문"))
        assertTrue(r is ExtractResult.Success)
        assertEquals(listOf("", "본문"), (r as ExtractResult.Success).text.paragraphs)
    }
}
