package io.github.june690602_blip.cleanpdf.doc

/** 구조 블록. 표 셀은 평탄 텍스트, 이미지는 래스터 바이트. */
sealed interface DocBlock {
    data class Para(val text: String) : DocBlock
    data class Table(val rows: List<List<String>>) : DocBlock
    class Image(val mime: String, val bytes: ByteArray) : DocBlock {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return mime == other.mime && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()
        override fun toString(): String = "Image(mime=$mime, bytes=${bytes.size})"
    }
}
