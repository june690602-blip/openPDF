package io.github.june690602_blip.cleanpdf.doc

import java.io.File

interface DocTextExtractor {
    fun extract(file: File): ExtractResult
}

/** 문단들이 모두 공백이면 Empty, 아니면 Success. */
fun toResult(paragraphs: List<String>): ExtractResult =
    if (paragraphs.none { it.isNotBlank() }) ExtractResult.Empty
    else ExtractResult.Success(DocText(paragraphs))

object Extractors {
    /** 포맷→추출기. 포맷별 구현은 이후 Task 에서 연결(지금은 전부 null). */
    fun forFormat(format: DocFormat): DocTextExtractor? = null
}
