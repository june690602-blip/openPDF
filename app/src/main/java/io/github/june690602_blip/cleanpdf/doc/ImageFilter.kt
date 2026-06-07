package io.github.june690602_blip.cleanpdf.doc

/** 매직바이트로 웹 렌더 가능한 래스터만 통과 + 용량 캡(순수). */
object ImageFilter {
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024

    sealed interface Outcome {
        data class Ok(val image: DocBlock.Image) : Outcome
        data object Oversized : Outcome
        data object Unsupported : Outcome
    }

    /** PNG/JPEG/GIF/BMP → mime, 그 외(WMF/EMF/OLE/unknown) → null. */
    fun mimeOf(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            else -> null
        }
    }

    /** 포맷·용량 판정. usedSoFar = 지금까지 인라인한 누적 바이트. */
    fun classify(bytes: ByteArray, usedSoFar: Int): Outcome {
        val mime = mimeOf(bytes) ?: return Outcome.Unsupported
        if (bytes.size > MAX_IMAGE_BYTES || usedSoFar.toLong() + bytes.size > MAX_TOTAL_BYTES) return Outcome.Oversized
        return Outcome.Ok(DocBlock.Image(mime, bytes))
    }
}
