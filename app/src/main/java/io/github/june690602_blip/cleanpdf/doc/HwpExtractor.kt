package io.github.june690602_blip.cleanpdf.doc

import kr.dogfoot.hwplib.reader.HWPReader
import java.io.File

/** HWP v5 = hwplib 객체모델 → 구조 블록(표·이미지). */
object HwpExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        val hwp = file.inputStream().use { HWPReader.fromInputStream(it) }
        toResult(HwpBlocks.build(hwp))
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwp parse error") }
}
