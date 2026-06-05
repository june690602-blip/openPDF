package io.github.june690602_blip.cleanpdf.io

import android.content.Context
import android.net.Uri
import io.github.june690602_blip.cleanpdf.pdf.isLikelyPdf
import java.io.File

/** Copies a content:// (or file://) PDF into app cache and returns the local file. */
object PdfSource {
    fun copyToCache(context: Context, uri: Uri): File {
        val name = queryName(context, uri) ?: "document.pdf"
        val out = File(context.cacheDir, "opened_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open input stream for $uri" }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** True if [uri] looks like a PDF by display name (.pdf) or %PDF- magic header. */
    fun looksLikePdf(context: Context, uri: Uri): Boolean {
        val name = queryName(context, uri)
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ByteArray(8).let { buf -> val n = input.read(buf); if (n <= 0) ByteArray(0) else buf.copyOf(n) }
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        return isLikelyPdf(name, head)
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
}
