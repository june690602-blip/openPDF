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
    @Test fun extractsKoreanTextIfFixturePresent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val present = runCatching { inst.context.assets.open("sample.hwp").close() }.isSuccess
        assumeTrue("place app/src/androidTest/assets/sample.hwp to run this", present)

        val out = File(inst.targetContext.cacheDir, "sample.hwp")
        inst.context.assets.open("sample.hwp").use { i -> out.outputStream().use { i.copyTo(it) } }

        val result = HwpExtractor.extract(out)
        assertTrue("expected Success, got $result", result is ExtractResult.Success)
        val text = (result as ExtractResult.Success).text.blocks
            .filterIsInstance<DocBlock.Para>()
            .joinToString("\n") { it.text }
        assertTrue("should contain Korean text", text.any { it in '가'..'힣' })
    }
}
