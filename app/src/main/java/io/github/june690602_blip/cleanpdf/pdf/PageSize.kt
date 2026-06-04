package io.github.june690602_blip.cleanpdf.pdf

/** PDF page size in PDF points (1/72 inch), at scale 1.0. Immutable. */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = if (height == 0f) 1f else width / height
}
