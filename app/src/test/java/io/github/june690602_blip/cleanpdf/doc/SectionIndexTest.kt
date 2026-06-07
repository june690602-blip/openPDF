package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionIndexTest {
    @Test fun parsesNumber() {
        assertEquals(0, sectionIndex("Contents/section0.xml"))
        assertEquals(12, sectionIndex("Contents/section12.xml"))
    }
    @Test fun sortsNumericallyNotLexically() {
        val names = listOf("Contents/section10.xml", "Contents/section2.xml", "Contents/section1.xml")
        assertEquals(
            listOf("Contents/section1.xml", "Contents/section2.xml", "Contents/section10.xml"),
            names.sortedBy { sectionIndex(it) }
        )
    }
}
