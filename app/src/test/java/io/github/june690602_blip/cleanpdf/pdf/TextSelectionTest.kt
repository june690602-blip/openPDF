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

    @Test fun wordRangeSnapsToSurroundingNonWhitespaceRun() {
        val p = sample()
        assertEquals(0..1, TextSelection.wordRangeAt(p, 5f, 6f))   // "Hi"
        assertEquals(3..7, TextSelection.wordRangeAt(p, 40f, 6f))  // "there"
    }

    @Test fun wordRangeOnWhitespaceSelectsThatChar() {
        assertEquals(2..2, TextSelection.wordRangeAt(sample(), 18f, 6f)) // the space
    }

    @Test fun wordRangeOnEmptyPageIsNull() {
        assertNull(TextSelection.wordRangeAt(PageText(0, emptyList()), 0f, 0f))
    }

    @Test fun wordRangeStopsAtLineBoundaryEvenWithoutWhitespace() {
        // "AB" on line 0, "CD" on line 1, all bboxes contiguous with no whitespace between.
        val p = PageText(
            pageIndex = 0,
            chars = listOf(
                PageChar('A'.code, 0f, 0f, 10f, 12f, 0),
                PageChar('B'.code, 10f, 0f, 20f, 12f, 0),
                PageChar('C'.code, 20f, 0f, 30f, 12f, 1),
                PageChar('D'.code, 30f, 0f, 40f, 12f, 1),
            ),
        )
        // Over 'C' (center x = 25): the word must not cross into line 0.
        assertEquals(2..3, TextSelection.wordRangeAt(p, 25f, 6f))
    }

    /** "AB" on line 0, "C" on line 1. */
    private fun twoLines(): PageText = PageText(
        pageIndex = 0,
        chars = listOf(
            PageChar('A'.code, 0f, 0f, 10f, 12f, 0),
            PageChar('B'.code, 10f, 0f, 20f, 12f, 0),
            PageChar('C'.code, 0f, 20f, 10f, 32f, 1),
        ),
    )

    @Test fun selectionRectsMergePerLine() {
        val rects = TextSelection.selectionRects(twoLines(), 0..2)
        assertEquals(2, rects.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 20f, 12f), rects[0], 0.001f)
        assertArrayEquals(floatArrayOf(0f, 20f, 10f, 32f), rects[1], 0.001f)
    }

    @Test fun selectionRectsOnEmptyPageIsEmpty() {
        assertEquals(0, TextSelection.selectionRects(PageText(0, emptyList()), 0..0).size)
    }

    @Test fun selectedTextJoinsCharsAndBreaksLines() {
        assertEquals("AB\nC", TextSelection.selectedText(twoLines(), 0..2))
        assertEquals("B\nC", TextSelection.selectedText(twoLines(), 1..2))
    }

    @Test fun selectedTextOnEmptyPageIsBlank() {
        assertEquals("", TextSelection.selectedText(PageText(0, emptyList()), 0..0))
    }

    @Test fun handlePointsAreFirstBottomLeftAndLastBottomRight() {
        val (s, e) = TextSelection.handlePoints(twoLines(), 0..2)!!
        assertArrayEquals(floatArrayOf(0f, 12f), s, 0.001f)   // 'A' bottom-left
        assertArrayEquals(floatArrayOf(10f, 32f), e, 0.001f)  // 'C' bottom-right
    }

    @Test fun handlePointsOnEmptyPageIsNull() {
        assertNull(TextSelection.handlePoints(PageText(0, emptyList()), 0..0))
    }
}
