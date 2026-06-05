package io.github.june690602_blip.cleanpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.io.PdfSource
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.pdf.PdfOpenResult
import io.github.june690602_blip.cleanpdf.pdf.SearchCursor
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import io.github.june690602_blip.cleanpdf.view.PageJump
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import io.github.june690602_blip.cleanpdf.view.ThumbnailAdapter
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var renderer: PageRenderer? = null
    private lateinit var reader: PdfReaderView
    private lateinit var errorView: android.widget.TextView
    private var currentSizes: List<PageSize> = emptyList()
    private var cursor: SearchCursor? = null
    private lateinit var searchBar: android.view.View
    private lateinit var searchPosition: android.widget.TextView
    private val recents by lazy { io.github.june690602_blip.cleanpdf.store.RecentFilesStore(this) }

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        reader = findViewById(R.id.reader)
        errorView = findViewById(R.id.error_view)
        searchBar = findViewById(R.id.search_bar)
        searchPosition = findViewById(R.id.search_position)
        findViewById<android.widget.Button>(R.id.search_prev_btn).setOnClickListener { stepSearch(-1) }
        findViewById<android.widget.Button>(R.id.search_next_btn).setOnClickListener { stepSearch(+1) }
        findViewById<android.widget.Button>(R.id.search_close_btn).setOnClickListener { closeSearch() }
        reader.onPageChanged = { cur, total ->
            supportActionBar?.subtitle = if (total > 0) "${cur + 1} / $total" else null
        }
        val incoming = io.github.june690602_blip.cleanpdf.io.Intents.incomingUri(
            intent.action,
            intent.data,
            androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        if (incoming != null) {
            loadFromUri(incoming)
        } else {
            // Dev 편의: 인입이 없으면 번들 샘플 자동 오픈.
            bg.execute {
                val f = File(cacheDir, "sample.pdf").apply {
                    assets.open("sample.pdf").use { i -> outputStream().use { i.copyTo(it) } }
                }
                openFile(f)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> { openDoc.launch(arrayOf("application/pdf")); true }
        R.id.action_recent -> { showRecent(); true }
        R.id.action_outline -> { showOutline(); true }
        R.id.action_goto -> { promptGoto(); true }
        R.id.action_thumbnails -> { showThumbnails(); true }
        R.id.action_search -> { showSearch(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadFromUri(uri: Uri) = bg.execute {
        if (!PdfSource.looksLikePdf(this, uri)) {
            runOnUiThread { showError(getString(R.string.error_not_pdf)) }
            return@execute
        }
        runCatching { openFile(PdfSource.copyToCache(this, uri)) }
            .onFailure { runOnUiThread { showError(getString(R.string.error_open)) } }
    }

    private fun showError(message: String) {
        reader.visibility = android.view.View.GONE
        errorView.text = message
        errorView.visibility = android.view.View.VISIBLE
    }

    private fun showDocument(doc: PdfDocument, file: File) {
        // Build the new renderer fully before touching the old one, so a failed open leaves the
        // current document intact. Shut the old renderer down only after the adapter has been
        // swapped on the UI thread — shutting it earlier could make the still-installed old
        // adapter submit a render to an already-shutdown executor (RejectedExecutionException).
        val r = PageRenderer(doc)
        val sizes = r.sizesBlockingOnRenderThread()
        val old = renderer
        renderer = r
        recents.add(file.absolutePath, file.name)
        runOnUiThread {
            currentSizes = sizes
            errorView.visibility = android.view.View.GONE
            reader.visibility = android.view.View.VISIBLE
            reader.setDocument(r, sizes)
            old?.shutdown()
        }
    }

    /** Open [file] off the bg thread, surfacing errors/password via the UI. Call from [bg]. */
    private fun openFile(file: File) {
        when (val result = PdfDocument.openResult(file.absolutePath)) {
            is PdfOpenResult.Success -> showDocument(result.document, file)
            is PdfOpenResult.NeedsPassword -> runOnUiThread { promptPassword(result.document, file) }
            is PdfOpenResult.Error -> runOnUiThread { showError(getString(R.string.error_open)) }
        }
    }

    private fun promptPassword(doc: PdfDocument, file: File) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.password_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                bg.execute {
                    if (doc.authenticate(input.text.toString())) showDocument(doc, file)
                    else runOnUiThread { promptPassword(doc, file) /* 재시도 */ }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> bg.execute { doc.close() } }
            .show()
    }

    private fun showRecent() {
        val items = recents.list()
        if (items.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_recent, android.widget.Toast.LENGTH_SHORT).show(); return
        }
        val names = items.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.recent_files)
            .setItems(names) { _, which ->
                val f = File(items[which].path)
                if (f.exists()) bg.execute { openFile(f) }
                else {
                    recents.remove(items[which].path)
                    android.widget.Toast.makeText(this, R.string.recent_missing, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showOutline() {
        val r = renderer ?: return
        bg.execute {
            val items = r.loadOutlineBlocking()
            runOnUiThread {
                if (items.isEmpty()) {
                    android.widget.Toast.makeText(this, R.string.no_outline, android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = items.map { "    ".repeat(it.level) + it.title }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.outline)
                    .setItems(labels) { _, which ->
                        val page = items[which].page
                        if (page >= 0) reader.scrollToPage(page)
                    }
                    .show()
            }
        }
    }

    private fun promptGoto() {
        val total = reader.pageCount
        if (total <= 0) return
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.goto_hint, total)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.goto_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val idx = PageJump.parse(input.text.toString(), total)
                if (idx != null) reader.scrollToPage(idx)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showThumbnails() {
        val r = renderer ?: return
        if (currentSizes.isEmpty()) return
        val cell = (resources.displayMetrics.density * 96).toInt() // ~96dp thumbnail width
        val cache = BitmapCache(maxBytes = 32 * 1024 * 1024)
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.thumbnails)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
            .create()
        grid.adapter = ThumbnailAdapter(
            renderer = r,
            sizes = currentSizes,
            cache = cache,
            cellWidthPx = cell,
        ) { page ->
            reader.scrollToPage(page)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showSearch() {
        val r = renderer ?: return
        val input = android.widget.EditText(this).apply { hint = getString(R.string.search_hint) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.search)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val q = input.text.toString()
                bg.execute {
                    val hits = r.searchBlocking(q)
                    runOnUiThread { openSearch(hits) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Enter search mode: show the bar, highlight all hits, jump to the first. */
    private fun openSearch(hits: List<SearchHit>) {
        if (hits.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.search_none, android.widget.Toast.LENGTH_SHORT).show()
            closeSearch()
            return
        }
        val c = SearchCursor(hits)
        cursor = c
        searchBar.visibility = android.view.View.VISIBLE
        applyCursor(c)
    }

    /** Move the active hit by [delta] (+1 next / -1 prev), wrapping. */
    private fun stepSearch(delta: Int) {
        val c = cursor ?: return
        val moved = if (delta >= 0) c.next() else c.prev()
        cursor = moved
        applyCursor(moved)
    }

    private fun applyCursor(c: SearchCursor) {
        reader.setSearchHighlights(c.hits, c.current)
        c.current?.let { reader.scrollToHit(it) }
        searchPosition.text = getString(R.string.search_position, c.position, c.size)
    }

    private fun closeSearch() {
        cursor = null
        searchBar.visibility = android.view.View.GONE
        reader.clearSearchHighlights()
    }

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
