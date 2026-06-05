package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCursorTest {
    private fun hit(p: Int) = SearchHit(p, 0f, 0f, 1f, 1f)
    private val three = listOf(hit(0), hit(1), hit(2))

    @Test fun positionIsOneBased() {
        assertEquals(1, SearchCursor(three).position)
        assertEquals(3, SearchCursor(three).size)
    }

    @Test fun nextWraps() {
        val c = SearchCursor(three).next().next()      // index 2
        assertEquals(3, c.position)
        assertEquals(1, c.next().position)             // wraps to 0 -> position 1
    }

    @Test fun prevWraps() {
        assertEquals(3, SearchCursor(three).prev().position)  // 0 -> wraps to 2 -> position 3
    }

    @Test fun currentTracksIndex() {
        assertEquals(1, SearchCursor(three).next().current!!.page)
    }

    @Test fun emptyIsSafe() {
        val e = SearchCursor(emptyList())
        assertEquals(0, e.position); assertEquals(0, e.size)
        assertNull(e.current)
        assertEquals(0, e.next().position); assertEquals(0, e.prev().position)
    }
}
