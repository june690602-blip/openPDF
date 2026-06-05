package io.github.june690602_blip.cleanpdf.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageJumpTest {
    @Test fun parsesOneBasedToZeroBasedIndex() {
        assertEquals(2, PageJump.parse("3", total = 5)) // 3쪽 -> index 2
    }
    @Test fun clampsAboveTotal() {
        assertEquals(4, PageJump.parse("99", total = 5)) // 마지막(5쪽)=index 4
    }
    @Test fun clampsZeroAndNegativeToFirst() {
        assertEquals(0, PageJump.parse("0", total = 5))
        assertEquals(0, PageJump.parse("-7", total = 5))
    }
    @Test fun trimsWhitespace() {
        assertEquals(1, PageJump.parse("  2 ", total = 5))
    }
    @Test fun rejectsNonNumberOrEmptyOrNoPages() {
        assertNull(PageJump.parse("abc", total = 5))
        assertNull(PageJump.parse("", total = 5))
        assertNull(PageJump.parse("3", total = 0))
    }
}
