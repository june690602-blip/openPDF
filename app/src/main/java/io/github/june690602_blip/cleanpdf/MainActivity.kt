package io.github.june690602_blip.cleanpdf

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    private var renderer: PageRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val reader = findViewById<PdfReaderView>(R.id.reader)

        bg.execute {
            val cached = File(cacheDir, "sample.pdf").apply {
                assets.open("sample.pdf").use { i -> outputStream().use { i.copyTo(it) } }
            }
            val doc = PdfDocument.open(cached.absolutePath)
            val r = PageRenderer(doc)
            val sizes = r.sizesBlockingOnRenderThread()
            renderer = r
            runOnUiThread { reader.setDocument(r, sizes) }
        }
    }

    override fun onDestroy() {
        super.onDestroy(); renderer?.shutdown(); bg.shutdown()
    }
}
