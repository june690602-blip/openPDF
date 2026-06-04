package io.github.june690602_blip.cleanpdf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var renderer: PageRenderer? = null
    private lateinit var reader: PdfReaderView

    /** Test-only receiver: adb shell am broadcast -a io.github.june690602_blip.cleanpdf.TOGGLE_ZOOM */
    private val zoomTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reader.toggleZoomForTest()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        reader = findViewById(R.id.reader)

        ContextCompat.registerReceiver(
            this, zoomTestReceiver,
            IntentFilter("io.github.june690602_blip.cleanpdf.TOGGLE_ZOOM"),
            ContextCompat.RECEIVER_EXPORTED,
        )

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
        super.onDestroy()
        unregisterReceiver(zoomTestReceiver)
        renderer?.shutdown(); bg.shutdown()
    }
}
