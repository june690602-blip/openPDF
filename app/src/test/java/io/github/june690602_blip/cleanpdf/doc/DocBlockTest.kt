package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DocBlockTest {
    @Test fun imageEqualityByContent() {
        val a = DocBlock.Image("image/png", byteArrayOf(1, 2, 3))
        val b = DocBlock.Image("image/png", byteArrayOf(1, 2, 3))
        val c = DocBlock.Image("image/png", byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
    @Test fun paraAndTable() {
        assertEquals(DocBlock.Para("x"), DocBlock.Para("x"))
        assertEquals(DocBlock.Table(listOf(listOf("a", "b"))), DocBlock.Table(listOf(listOf("a", "b"))))
    }
}
