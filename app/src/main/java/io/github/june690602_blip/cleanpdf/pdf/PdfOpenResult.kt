package io.github.june690602_blip.cleanpdf.pdf

/** Outcome of attempting to open a PDF. Immutable. */
sealed interface PdfOpenResult {
    data class Success(val document: PdfDocument) : PdfOpenResult
    data object NeedsPassword : PdfOpenResult
    data class Error(val reason: String) : PdfOpenResult
}
