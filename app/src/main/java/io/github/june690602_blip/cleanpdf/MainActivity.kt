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
import io.github.june690602_blip.cleanpdf.pdf.PdfOpenResult
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var renderer: PageRenderer? = null
    private lateinit var reader: PdfReaderView
    private lateinit var errorView: android.widget.TextView

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        reader = findViewById(R.id.reader)
        errorView = findViewById(R.id.error_view)
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

    private fun showError(message: String) {
        reader.visibility = android.view.View.GONE
        errorView.text = message
        errorView.visibility = android.view.View.VISIBLE
    }

    private fun showDocument(doc: PdfDocument) {
        // Build the new renderer fully before touching the old one, so a failed open leaves the
        // current document intact. Shut the old renderer down only after the adapter has been
        // swapped on the UI thread — shutting it earlier could make the still-installed old
        // adapter submit a render to an already-shutdown executor (RejectedExecutionException).
        val r = PageRenderer(doc)
        val sizes = r.sizesBlockingOnRenderThread()
        val old = renderer
        renderer = r
        runOnUiThread {
            errorView.visibility = android.view.View.GONE
            reader.visibility = android.view.View.VISIBLE
            reader.setDocument(r, sizes)
            old?.shutdown()
        }
    }

    /** Open [file] off the bg thread, surfacing errors/password via the UI. Call from [bg]. */
    private fun openFile(file: File) {
        when (val result = PdfDocument.openResult(file.absolutePath)) {
            is PdfOpenResult.Success -> showDocument(result.document)
            is PdfOpenResult.NeedsPassword -> runOnUiThread { promptPassword(result.document) }
            is PdfOpenResult.Error -> runOnUiThread { showError(getString(R.string.error_open)) }
        }
    }

    private fun promptPassword(doc: PdfDocument) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.password_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                bg.execute {
                    if (doc.authenticate(input.text.toString())) showDocument(doc)
                    else runOnUiThread { promptPassword(doc) /* 재시도 */ }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> bg.execute { doc.close() } }
            .show()
    }

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
