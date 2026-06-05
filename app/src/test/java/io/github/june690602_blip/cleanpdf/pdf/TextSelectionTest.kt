package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSelectionTest {

    /** "Hi" on line 0, "there" on line 0 after a space; bboxes are simple 12-tall glyphs. */
    private fun sample(): PageText = PageText(
        pageIndex = 0,
        chars = listOf(
            PageChar('H'.code, 0f, 0f, 10f, 12f, 0),
            PageChar('i'.code, 10f, 0f, 16f, 12f, 0),
            PageChar(' '.code, 16f, 0f, 20f, 12f, 0),
            PageChar('t'.code, 20f, 0f, 28f, 12f, 0),
            PageChar('h'.code, 28f, 0f, 36f, 12f, 0),
            PageChar('e'.code, 36f, 0f, 44f, 12f, 0),
            PageChar('r'.code, 44f, 0f, 50f, 12f, 0),
            PageChar('e'.code, 50f, 0f, 58f, 12f, 0),
        ),
    )

    @Test fun nearestPicksClosestCharByCenter() {
        val p = sample()
        assertEquals(0, TextSelection.nearestCharIndex(p, 5f, 6f))   // over 'H'
        assertEquals(3, TextSelection.nearestCharIndex(p, 24f, 6f))  // over 't'
    }

    @Test fun nearestOnEmptyPageIsMinusOne() {
        assertEquals(-1, TextSelection.nearestCharIndex(PageText(0, emptyList()), 5f, 6f))
    }
}
