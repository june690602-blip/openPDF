package io.github.june690602_blip.cleanpdf.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice

/**
 * Thin wrapper over MuPDF [Document]. NOT thread-safe: all calls must run on a
 * single dedicated thread (see PageRenderer in a later task). Always [close] when done.
 */
class PdfDocument private constructor(private val doc: Document) {

    val pageCount: Int = doc.countPages()

    fun needsPassword(): Boolean = doc.needsPassword()

    fun authenticate(password: String): Boolean = doc.authenticatePassword(password)

    fun pageSize(index: Int): PageSize {
        val page = doc.loadPage(index)
        val b = page.getBounds()       // fitz Rect: x0,y0,x1,y1 in points
        page.destroy()
        return PageSize(b.x1 - b.x0, b.y1 - b.y0)
    }

    /** Render [index] at [scale] (1.0 = 72dpi) into a new ARGB_8888 bitmap. */
    fun renderPage(index: Int, scale: Float): Bitmap {
        val page = doc.loadPage(index)
        val ctm = Matrix(scale, scale)
        val bmp = AndroidDrawDevice.drawPage(page, ctm)
        page.destroy()
        return bmp
    }

    fun close() = doc.destroy()

    companion object {
        /** Open a PDF from a local filesystem path. Throws on unreadable/corrupt files. */
        fun open(path: String): PdfDocument = PdfDocument(Document.openDocument(path))

        /** Open a PDF, returning a [PdfOpenResult] instead of throwing. NeedsPassword if encrypted. */
        fun openResult(path: String): PdfOpenResult =
            runCatching { PdfDocument(Document.openDocument(path)) }
                .fold(
                    onSuccess = { doc ->
                        if (doc.needsPassword()) PdfOpenResult.NeedsPassword(doc)
                        else PdfOpenResult.Success(doc)
                    },
                    onFailure = { PdfOpenResult.Error(it.message ?: "open failed") },
                )
    }
}
