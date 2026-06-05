package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineModelTest {
    @Test fun flattensDepthFirstWithLevels() {
        val tree = listOf(
            RawOutline("A", 0, listOf(
                RawOutline("A.1", 2, emptyList()),
                RawOutline("A.2", 4, emptyList()),
            )),
            RawOutline("B", 7, emptyList()),
        )
        val flat = OutlineModel.flatten(tree)
        assertEquals(
            listOf(
                PdfOutlineItem("A", 0, 0),
                PdfOutlineItem("A.1", 2, 1),
                PdfOutlineItem("A.2", 4, 1),
                PdfOutlineItem("B", 7, 0),
            ),
            flat,
        )
    }

    @Test fun emptyInputGivesEmptyList() {
        assertEquals(emptyList<PdfOutlineItem>(), OutlineModel.flatten(emptyList()))
    }
}
