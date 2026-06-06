// DocFormat.kt
package io.github.june690602_blip.cleanpdf.doc

import io.github.june690602_blip.cleanpdf.pdf.isLikelyPdf

enum class DocFormat { PDF, DOCX, HWP, HWPX, UNKNOWN }

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private val OLE_MAGIC = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
)

/** Pure classify: extension first, PDF magic second. ZIP/OLE without a known extension -> UNKNOWN (refined later by DocProbe). */
fun detectFormat(name: String?, head: ByteArray): DocFormat {
    val lower = name?.lowercase()
    when {
        lower == null -> {}
        lower.endsWith(".docx") -> return DocFormat.DOCX
        lower.endsWith(".hwpx") -> return DocFormat.HWPX
        lower.endsWith(".hwp") -> return DocFormat.HWP
    }
    if (isLikelyPdf(name, head)) return DocFormat.PDF
    return DocFormat.UNKNOWN
}

fun isZipMagic(head: ByteArray) = startsWith(head, ZIP_MAGIC)
fun isOleMagic(head: ByteArray) = startsWith(head, OLE_MAGIC)

private fun startsWith(head: ByteArray, magic: ByteArray): Boolean {
    if (head.size < magic.size) return false
    for (i in magic.indices) if (head[i] != magic[i]) return false
    return true
}
