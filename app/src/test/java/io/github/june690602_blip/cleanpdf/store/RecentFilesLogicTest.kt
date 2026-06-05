package io.github.june690602_blip.cleanpdf.store

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is only a non-functional stub in plain JVM unit tests, so the serialize round-trip
// needs the real impl Robolectric provides. Pin sdk=34: Robolectric 4.14.1 predates API 36.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RecentFilesLogicTest {
    private fun rf(path: String) = RecentFile(path, path.substringAfterLast('/'), 0L)

    @Test fun newestFirst() {
        val list = RecentFilesLogic.add(emptyList(), rf("/a"), max = 5)
        val list2 = RecentFilesLogic.add(list, rf("/b"), max = 5)
        assertEquals(listOf("/b", "/a"), list2.map { it.path })
    }

    @Test fun dedupByPathMovesToFront() {
        var l = RecentFilesLogic.add(emptyList(), rf("/a"), max = 5)
        l = RecentFilesLogic.add(l, rf("/b"), max = 5)
        l = RecentFilesLogic.add(l, rf("/a"), max = 5) // re-add /a
        assertEquals(listOf("/a", "/b"), l.map { it.path })
    }

    @Test fun capsToMax() {
        var l = emptyList<RecentFile>()
        for (c in listOf("/a", "/b", "/c", "/d")) l = RecentFilesLogic.add(l, rf(c), max = 2)
        assertEquals(listOf("/d", "/c"), l.map { it.path })
    }

    @Test fun serializeRoundTrip() {
        val l = listOf(RecentFile("/x", "x.pdf", 7L), RecentFile("/y", "y.pdf", 8L))
        assertEquals(l, RecentFilesLogic.deserialize(RecentFilesLogic.serialize(l)))
    }
}
