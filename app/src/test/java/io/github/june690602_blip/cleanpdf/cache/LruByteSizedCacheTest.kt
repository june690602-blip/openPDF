package io.github.june690602_blip.cleanpdf.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruByteSizedCacheTest {
    // value = its own byte size for easy reasoning
    private fun cache(max: Int) = LruByteSizedCache<Int, Int>(max) { it }

    @Test fun returnsStoredValue() {
        val c = cache(100); c.put(1, 10); assertEquals(10, c.get(1))
    }

    @Test fun evictsLeastRecentlyUsedWhenOverBudget() {
        val c = cache(30)
        c.put(1, 10); c.put(2, 10); c.put(3, 10) // total 30, ok
        c.get(1)                                  // touch 1 -> 2 is now LRU
        c.put(4, 10)                              // over budget -> evict 2
        assertEquals(10, c.get(1))
        assertNull(c.get(2))
        assertEquals(10, c.get(4))
    }

    @Test fun overwritingKeyUpdatesTotalSize() {
        val c = cache(15)
        c.put(1, 10); c.put(1, 5) // same key, smaller value
        c.put(2, 9)               // 5 + 9 = 14 <= 15, nothing evicted
        assertEquals(5, c.get(1))
        assertEquals(9, c.get(2))
    }

    @Test fun evictionInvokesCallback() {
        val evicted = mutableListOf<Int>()
        val c = LruByteSizedCache<Int, Int>(10, sizeOf = { it }, onEvict = { _, v -> evicted.add(v) })
        c.put(1, 10); c.put(2, 10) // evicts 1
        assertEquals(listOf(10), evicted)
    }
}
