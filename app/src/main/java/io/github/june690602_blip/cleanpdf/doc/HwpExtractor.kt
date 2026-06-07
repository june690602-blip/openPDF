package io.github.june690602_blip.cleanpdf.doc

import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor
import java.io.File

/** HWP v5 바이너리 = hwplib 위임. 문단 사이 컨트롤(표 등) 텍스트까지 포함. */
object HwpExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        val hwp = file.inputStream().use { HWPReader.fromInputStream(it) }
        val raw = TextExtractor.extract(hwp, TextExtractMethod.InsertControlTextBetweenParagraphText)
        toResult(raw.split('\n'))
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwp parse error") }
}
