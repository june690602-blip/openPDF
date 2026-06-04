package io.github.june690602_blip.cleanpdf

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val preview = findViewById<ImageView>(R.id.preview)

        io.execute {
            val cached = copyAssetToCache("sample.pdf")
            val doc = PdfDocument.open(cached.absolutePath)
            val bmp: Bitmap = doc.renderPage(0, scale = 2.0f)
            doc.close()
            runOnUiThread { preview.setImageBitmap(bmp) }
        }
    }

    private fun copyAssetToCache(name: String): File {
        val out = File(cacheDir, name)
        assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
