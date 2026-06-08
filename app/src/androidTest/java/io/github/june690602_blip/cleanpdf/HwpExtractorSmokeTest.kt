package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpExtractor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HwpExtractorSmokeTest {
    @Test fun extractsStructureIfFixturePresent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val present = runCatching { inst.context.assets.open("sample.hwp").close() }.isSuccess
        assumeTrue("place app/src/androidTest/assets/sample.hwp (table+image) to run", present)
        val out = File(inst.targetContext.cacheDir, "sample.hwp")
        inst.context.assets.open("sample.hwp").use { i -> out.outputStream().use { i.copyTo(it) } }

        val result = HwpExtractor.extract(out)
        assertTrue("expected Success, got $result", result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks

        // has korean text — either in a Para at top level or inside a Table cell
        fun hasKorean(text: String) = text.any { c -> c in '가'..'힣' }
        val hasKoreanPara = blocks.any { it is DocBlock.Para && hasKorean(it.text) }
        val hasKoreanInTable = blocks.filterIsInstance<DocBlock.Table>()
            .any { t -> t.rows.any { row -> row.any { cell -> hasKorean(cell) } } }
        assertTrue("has korean text (para or table cell)", hasKoreanPara || hasKoreanInTable)
        assertTrue("has table", blocks.any { it is DocBlock.Table })
    }
}
