package io.github.june690602_blip.cleanpdf.pdf

private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

/** True if [name] ends in .pdf (case-insensitive) OR [head] starts with the %PDF- magic. */
fun isLikelyPdf(name: String?, head: ByteArray): Boolean {
    if (name != null && name.lowercase().endsWith(".pdf")) return true
    if (head.size < PDF_MAGIC.size) return false
    for (i in PDF_MAGIC.indices) if (head[i] != PDF_MAGIC[i]) return false
    return true
}
