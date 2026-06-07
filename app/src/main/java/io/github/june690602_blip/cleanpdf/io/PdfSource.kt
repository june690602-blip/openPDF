package io.github.june690602_blip.cleanpdf.io

import android.content.Context
import android.net.Uri
import io.github.june690602_blip.cleanpdf.pdf.isLikelyPdf
import java.io.File

/** content:// (또는 file://) 문서를 앱 캐시로 복사하고 이름/헤더를 들여다보는 헬퍼(포맷 중립). */
object PdfSource {
    fun copyToCache(context: Context, uri: Uri): File {
        val name = displayName(context, uri) ?: "document"
        val out = File(context.cacheDir, "opened_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open input stream for $uri" }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** 콘텐츠 표시 이름(.확장자 포함)을 조회. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 매직 판별용 선두 [n]바이트. 실패 시 빈 배열. */
    fun peekHead(context: Context, uri: Uri, n: Int = 16): ByteArray = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(n); val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        } ?: ByteArray(0)
    }.getOrDefault(ByteArray(0))

    /** True if [uri] looks like a PDF by display name (.pdf) or %PDF- magic. (A9 에서 제거 예정) */
    fun looksLikePdf(context: Context, uri: Uri): Boolean =
        isLikelyPdf(displayName(context, uri), peekHead(context, uri, 8))
}
