package io.github.june690602_blip.cleanpdf

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import io.github.june690602_blip.cleanpdf.doc.DocHtml
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.Extractors
import io.github.june690602_blip.cleanpdf.store.RecentFilesStore
import java.io.File
import java.util.concurrent.Executors

class DocTextActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var web: WebView
    private lateinit var errorView: android.widget.TextView
    private lateinit var findBar: android.view.View
    private lateinit var findPosition: android.widget.TextView
    private val recents by lazy { RecentFilesStore(this) }
    private var format: DocFormat = DocFormat.UNKNOWN

    companion object {
        private const val EXTRA_PATH = "doc_path"
        private const val EXTRA_FORMAT = "doc_format"
        private const val EXTRA_NAME = "doc_name"
        fun intent(ctx: Context, file: File, format: DocFormat, name: String): Intent =
            Intent(ctx, DocTextActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_FORMAT, format.name)
                .putExtra(EXTRA_NAME, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doc_text)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.doc_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        web = findViewById(R.id.doc_web)
        errorView = findViewById(R.id.doc_error)
        findBar = findViewById(R.id.find_bar)
        findPosition = findViewById(R.id.find_position)
        configureWebView(web)

        val path = intent.getStringExtra(EXTRA_PATH)
        format = runCatching { DocFormat.valueOf(intent.getStringExtra(EXTRA_FORMAT) ?: "") }
            .getOrDefault(DocFormat.UNKNOWN)
        val name = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.app_name)
        supportActionBar?.title = name
        if (path == null) { showError(getString(R.string.error_open_doc)); return }

        val file = File(path)
        bg.execute {
            val result = Extractors.forFormat(format)?.extract(file)
                ?: ExtractResult.Failure("unsupported")
            runOnUiThread { render(result, file, name) }
        }
    }

    private fun configureWebView(w: WebView) {
        w.settings.javaScriptEnabled = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.setSupportZoom(true)
        w.settings.builtInZoomControls = true
        w.settings.displayZoomControls = false
    }

    private fun render(result: ExtractResult, file: File, name: String) {
        when (result) {
            is ExtractResult.Success -> {
                recents.add(file.absolutePath, name, format.name)
                web.loadDataWithBaseURL(null, DocHtml.toHtml(result.text), "text/html", "utf-8", null)
                web.visibility = android.view.View.VISIBLE
                errorView.visibility = android.view.View.GONE
            }
            is ExtractResult.Empty -> showError(getString(R.string.doc_empty))
            is ExtractResult.Failure -> showError(getString(R.string.error_open_doc))
        }
    }

    private fun showError(message: String) {
        web.visibility = android.view.View.GONE
        errorView.text = message
        errorView.visibility = android.view.View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.doc_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        // R.id.action_doc_find -> handled in Task D1
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
