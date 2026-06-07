package io.github.june690602_blip.cleanpdf.doc

/** 추출 결과(불변). 문단/표/이미지 블록 순서 목록. */
data class DocText(val blocks: List<DocBlock>)

sealed interface ExtractResult {
    data class Success(val text: DocText) : ExtractResult
    data object Empty : ExtractResult
    data class Failure(val reason: String) : ExtractResult
}
