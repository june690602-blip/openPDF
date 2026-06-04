package io.github.june690602_blip.cleanpdf.pdf

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Serializes ALL access to a [PdfDocument] onto one thread (fitz is not thread-safe),
 * and renders pages off the main thread. [submit] returns a [Future] you can cancel.
 *
 * Cancellation: fitz 1.27.1's AndroidDrawDevice.drawPage has no Cookie overload, so a render
 * that has already started cannot be aborted mid-flight. [Future.cancel] (mayInterrupt=true)
 * removes a not-yet-started task from the queue (the common case on fast scroll) and the
 * [Thread.isInterrupted] guard skips a render whose thread was interrupted before it began.
 * Pages render fast, so an occasional un-abortable in-flight render is acceptable.
 */
class PageRenderer(private val doc: PdfDocument) {
    private val exec = Executors.newSingleThreadExecutor()

    val pageCount: Int get() = doc.pageCount

    fun sizesBlockingOnRenderThread(): List<PageSize> =
        exec.submit<List<PageSize>> { (0 until doc.pageCount).map { doc.pageSize(it) } }.get()

    /**
     * Render [page] at [scale]; deliver the bitmap via [onReady].
     * NOTE: [onReady] is invoked on the render thread — callers MUST post to the main thread
     * (e.g. View.post {}) before touching any UI or main-thread-owned state (e.g. the cache).
     */
    fun submit(page: Int, scale: Float, onReady: (Bitmap) -> Unit): Future<*> =
        exec.submit {
            if (!Thread.currentThread().isInterrupted) {
                val bmp = doc.renderPage(page, scale)
                onReady(bmp)
            }
        }

    fun shutdown() {
        exec.submit { doc.close() }
        exec.shutdown()
    }
}
