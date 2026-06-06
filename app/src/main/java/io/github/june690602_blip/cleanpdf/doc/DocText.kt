package io.github.june690602_blip.cleanpdf.doc

/** 추출된 텍스트(불변). 표는 이미 평탄화되어 한 행이 한 문자열. */
data class DocText(val paragraphs: List<String>)

sealed interface ExtractResult {
    data class Success(val text: DocText) : ExtractResult
    data object Empty : ExtractResult
    data class Failure(val reason: String) : ExtractResult
}
