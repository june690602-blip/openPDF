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

    @Test fun dedupByNameMovesToFrontWithLatestPath() {
        // Re-opening the same file yields a NEW cache path but the SAME name → one entry, newest path.
        var l = RecentFilesLogic.add(emptyList(), RecentFile("/cache/1_x.pdf", "x.pdf", 1L), max = 5)
        l = RecentFilesLogic.add(l, RecentFile("/cache/2_y.pdf", "y.pdf", 2L), max = 5)
        l = RecentFilesLogic.add(l, RecentFile("/cache/3_x.pdf", "x.pdf", 3L), max = 5) // re-open x.pdf
        assertEquals(listOf("x.pdf", "y.pdf"), l.map { it.name }) // x.pdf shown once, at front
        assertEquals("/cache/3_x.pdf", l[0].path)                 // updated to the latest cache copy
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

    @Test fun serializesAndReadsFormat() {
        val list = listOf(RecentFile("/p/a.docx", "a.docx", 1L, "DOCX"))
        val round = RecentFilesLogic.deserialize(RecentFilesLogic.serialize(list))
        org.junit.Assert.assertEquals("DOCX", round[0].format)
    }

    @Test fun legacyEntryWithoutFormatDefaultsToPdf() {
        val legacy = "[{\"p\":\"/p/x.pdf\",\"n\":\"x.pdf\",\"t\":5}]"
        org.junit.Assert.assertEquals("PDF", RecentFilesLogic.deserialize(legacy)[0].format)
    }
}
