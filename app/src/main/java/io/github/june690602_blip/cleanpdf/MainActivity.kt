package io.github.june690602_blip.cleanpdf

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleanpdf.io.PdfSource
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var renderer: PageRenderer? = null
    private lateinit var reader: PdfReaderView

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        reader = findViewById(R.id.reader)
        // Dev convenience: open bundled sample on first launch.
        bg.execute {
            val f = File(cacheDir, "sample.pdf").apply {
                assets.open("sample.pdf").use { i -> outputStream().use { i.copyTo(it) } }
            }
            openFile(f)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> { openDoc.launch(arrayOf("application/pdf")); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadFromUri(uri: Uri) = bg.execute {
        runCatching { openFile(PdfSource.copyToCache(this, uri)) }
    }

    private fun openFile(file: File) {
        renderer?.shutdown()
        val doc = PdfDocument.open(file.absolutePath)
        val r = PageRenderer(doc)
        val sizes = r.sizesBlockingOnRenderThread()
        renderer = r
        runOnUiThread { reader.setDocument(r, sizes) }
    }

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
