package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToResultTest {
    @Test fun blankParasAreEmpty() =
        assertEquals(ExtractResult.Empty, toResult(listOf(DocBlock.Para(""), DocBlock.Para("  "))))
    @Test fun emptyIsEmpty() = assertEquals(ExtractResult.Empty, toResult(emptyList()))
    @Test fun tableIsContent() {
        val r = toResult(listOf(DocBlock.Table(listOf(listOf("a")))))
        assertTrue(r is ExtractResult.Success)
    }
    @Test fun paraIsContent() {
        val r = toResult(listOf(DocBlock.Para("본문")))
        assertTrue(r is ExtractResult.Success)
        assertEquals(listOf(DocBlock.Para("본문")), (r as ExtractResult.Success).text.blocks)
    }
}
