package io.github.june690602_blip.cleanpdf.doc

import java.io.File

interface DocTextExtractor {
    fun extract(file: File): ExtractResult
}

/** 의미있는 블록이 하나도 없으면 Empty, 아니면 Success. */
fun toResult(blocks: List<DocBlock>): ExtractResult =
    if (blocks.none { it.hasContent() }) ExtractResult.Empty
    else ExtractResult.Success(DocText(blocks))

private fun DocBlock.hasContent(): Boolean = when (this) {
    is DocBlock.Para -> text.isNotBlank()
    is DocBlock.Table -> rows.any { r -> r.any { it.isNotBlank() } }
    is DocBlock.Image -> true
}

object Extractors {
    /** 포맷→추출기. 포맷별 구현은 이후 Task 에서 연결(지금은 전부 null). */
    fun forFormat(format: DocFormat): DocTextExtractor? = when (format) {
        DocFormat.DOCX -> DocxExtractor
        DocFormat.HWPX -> HwpxExtractor
        DocFormat.HWP -> HwpExtractor
        else -> null
    }
}
