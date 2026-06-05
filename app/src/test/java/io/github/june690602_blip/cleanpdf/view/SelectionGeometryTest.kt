package io.github.june690602_blip.cleanpdf.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGeometryTest {

    @Test fun scaleIsContentWidthOverPageWidth() {
        assertEquals(3f, SelectionGeometry.scale(100f, 300f), 0.001f)
        assertEquals(0f, SelectionGeometry.scale(0f, 300f), 0.001f) // guard div-by-zero
    }

    @Test fun pixelToPdfIsInverseOfScale() {
        assertArrayEquals(floatArrayOf(10f, 20f), SelectionGeometry.toPdfPoint(30f, 60f, 3f), 0.001f)
    }

    @Test fun rectAndPointToPixelsApplyScale() {
        assertArrayEquals(
            floatArrayOf(30f, 60f, 90f, 120f),
            SelectionGeometry.rectToPixels(floatArrayOf(10f, 20f, 30f, 40f), 3f), 0.001f,
        )
        assertArrayEquals(floatArrayOf(30f, 60f), SelectionGeometry.pointToPixels(10f, 20f, 3f), 0.001f)
    }
}
