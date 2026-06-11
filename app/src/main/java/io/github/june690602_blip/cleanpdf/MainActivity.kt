package io.github.june690602_blip.cleanpdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import io.github.june690602_blip.cleanpdf.doc.DocProbe
import io.github.june690602_blip.cleanpdf.doc.detectFormat
import io.github.june690602_blip.cleanpdf.io.PdfSource
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.pdf.PdfOpenResult
import io.github.june690602_blip.cleanpdf.pdf.PageText
import io.github.june690602_blip.cleanpdf.pdf.SearchCursor
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import io.github.june690602_blip.cleanpdf.pdf.TextSelection
import io.github.june690602_blip.cleanpdf.view.PageJump
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import io.github.june690602_blip.cleanpdf.view.ThumbnailAdapter
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var renderer: PageRenderer? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var reader: PdfReaderView
    private lateinit var errorView: android.widget.TextView
    private lateinit var pageIndicator: android.widget.TextView
    private var currentSizes: List<PageSize> = emptyList()
    private var cursor: SearchCursor? = null
    private lateinit var searchBar: android.view.View
    private lateinit var searchPosition: android.widget.TextView
    private var selText: PageText? = null
    private var selRange: IntRange? = null
    private lateinit var selectionBar: android.view.View
    private lateinit var selectionInfo: android.widget.TextView
    private val recents by lazy { io.github.june690602_blip.cleanpdf.store.RecentFilesStore(this) }
    private val hideIndicator = Runnable {
        pageIndicator.animate().alpha(0f).setDuration(250).withEndAction {
            pageIndicator.visibility = android.view.View.GONE
        }
    }

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        reader = findViewById(R.id.reader)
        errorView = findViewById(R.id.error_view)
        pageIndicator = findViewById(R.id.page_indicator)
        searchBar = findViewById(R.id.search_bar)
        searchPosition = findViewById(R.id.search_position)
        findViewById<android.widget.Button>(R.id.search_prev_btn).setOnClickListener { stepSearch(-1) }
        findViewById<android.widget.Button>(R.id.search_next_btn).setOnClickListener { stepSearch(+1) }
        findViewById<android.widget.Button>(R.id.search_close_btn).setOnClickListener { closeSearch() }
        selectionBar = findViewById(R.id.selection_bar)
        selectionInfo = findViewById(R.id.selection_info)
        applySystemBarInsets(toolbar)
        findViewById<android.widget.Button>(R.id.selection_copy_btn).setOnClickListener { copySelection() }
        findViewById<android.widget.Button>(R.id.selection_close_btn).setOnClickListener { clearSelection() }
        reader.onLongPressPdf = { page, x, y -> beginSelection(page, x, y) }
        reader.onSelectionDragPdf = { page, x, y, isStart -> dragSelection(page, x, y, isStart) }
        reader.onSelectionDismiss = { clearSelection() }
        reader.onToggleChrome = { toggleChrome() }
        reader.onPageChanged = { cur, total -> showPageIndicator(cur, total) }
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
                openFile(f, f.name)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> { openDoc.launch(arrayOf("*/*")); true }
        R.id.action_recent -> { showRecent(); true }
        R.id.action_outline -> { showOutline(); true }
        R.id.action_goto -> { promptGoto(); true }
        R.id.action_thumbnails -> { showThumbnails(); true }
        R.id.action_search -> { showSearch(); true }
        R.id.action_about -> { showAbout(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** In-app AGPL notice + source link (license compliance) — links are tappable. */
    private fun showAbout() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.about_text)
            .setPositiveButton(R.string.ok, null)
            .show()
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.let {
            android.text.util.Linkify.addLinks(it, android.text.util.Linkify.WEB_URLS)
            it.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    private fun loadFromUri(uri: Uri) = bg.execute {
        val name = PdfSource.displayName(this, uri)
        val head = PdfSource.peekHead(this, uri)
        runCatching {
            val file = PdfSource.copyToCache(this, uri)
            var format = detectFormat(name, head)
            if (format == DocFormat.UNKNOWN) format = DocProbe.refine(file, head)
            route(format, file, name ?: file.name)
        }.onFailure { runOnUiThread { showError(getString(R.string.error_open)) } }
    }

    /**
     * 포맷별 화면으로 보냄. 현재 **PDF 전용** — 문서(docx/hwp/hwpx)는 진입 비활성화(미지원 안내).
     * 복구하려면 DOCX/HWP/HWPX 분기를 `startActivity(DocTextActivity.intent(this, file, format, name))`로 되돌리면 됨.
     */
    private fun route(format: DocFormat, file: File, name: String) {
        when (format) {
            DocFormat.PDF -> openFile(file, name)
            DocFormat.DOCX, DocFormat.HWP, DocFormat.HWPX, DocFormat.UNKNOWN ->
                runOnUiThread { showError(getString(R.string.error_unsupported)) }
        }
    }

    /** Edge-to-edge: 툴바는 상태바 아래로, 하단 바(검색·선택)는 제스처바 위로 패딩. */
    private fun applySystemBarInsets(toolbar: MaterialToolbar) {
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        for (bar in listOf(searchBar, selectionBar)) {
            ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
                insets
            }
        }
    }

    private fun showError(message: String) {
        reader.visibility = android.view.View.GONE
        errorView.text = message
        errorView.visibility = android.view.View.VISIBLE
    }

    private fun showDocument(doc: PdfDocument, file: File, title: String) {
        // Build the new renderer fully before touching the old one, so a failed open leaves the
        // current document intact. Shut the old renderer down only after the adapter has been
        // swapped on the UI thread — shutting it earlier could make the still-installed old
        // adapter submit a render to an already-shutdown executor (RejectedExecutionException).
        val r = PageRenderer(doc)
        val sizes = r.sizesBlockingOnRenderThread()
        val old = renderer
        renderer = r
        recents.add(file.absolutePath, file.name, DocFormat.PDF.name)
        runOnUiThread {
            currentSizes = sizes
            supportActionBar?.title = title          // 연 PDF 파일명을 제목으로
            toolbar.visibility = android.view.View.VISIBLE   // 새 문서를 열면 툴바는 다시 보이게
            errorView.visibility = android.view.View.GONE
            reader.visibility = android.view.View.VISIBLE
            reader.setDocument(r, sizes)
            selText = null; selRange = null
            selectionBar.visibility = android.view.View.GONE
            old?.shutdown()
        }
    }

    /** Open [file] off the bg thread, surfacing errors/password via the UI. Call from [bg]. */
    private fun openFile(file: File, title: String) {
        when (val result = PdfDocument.openResult(file.absolutePath)) {
            is PdfOpenResult.Success -> showDocument(result.document, file, title)
            is PdfOpenResult.NeedsPassword -> runOnUiThread { promptPassword(result.document, file, title) }
            is PdfOpenResult.Error -> runOnUiThread { showError(getString(R.string.error_open)) }
        }
    }

    private fun promptPassword(doc: PdfDocument, file: File, title: String) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.password_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                bg.execute {
                    if (doc.authenticate(input.text.toString())) showDocument(doc, file, title)
                    else runOnUiThread { promptPassword(doc, file, title) /* 재시도 */ }
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
                val item = items[which]
                val f = File(item.path)
                if (f.exists()) {
                    val fmt = runCatching { DocFormat.valueOf(item.format) }.getOrDefault(DocFormat.PDF)
                    bg.execute { route(fmt, f, item.name) }
                } else {
                    recents.remove(item.path)
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
        clearSelection()
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

    /** Long-press: extract the page's text off the UI thread, then select the word under the finger. */
    private fun beginSelection(page: Int, xPt: Float, yPt: Float) {
        closeSearch()
        val r = renderer ?: return
        bg.execute {
            val text = r.extractTextBlocking(page)
            runOnUiThread {
                val range = TextSelection.wordRangeAt(text, xPt, yPt)
                if (range == null) {
                    android.widget.Toast.makeText(this, R.string.selection_none, android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                selText = text; selRange = range
                selectionBar.visibility = android.view.View.VISIBLE
                applySelection()
            }
        }
    }

    /** Push the current [selRange] to the reader as overlay rects + handles, and update the count. */
    private fun applySelection() {
        val text = selText ?: return
        val range = selRange ?: return
        val rects = TextSelection.selectionRects(text, range)
        val handles = TextSelection.handlePoints(text, range)
        reader.setSelection(text.pageIndex, rects, handles?.first, handles?.second)
        selectionInfo.text = getString(R.string.selection_chars, range.last - range.first + 1)
    }

    private fun copySelection() {
        val text = selText ?: return
        val range = selRange ?: return
        val s = TextSelection.selectedText(text, range)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pdf", s))
        android.widget.Toast.makeText(this, R.string.selection_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clearSelection() {
        selText = null; selRange = null
        selectionBar.visibility = android.view.View.GONE
        reader.clearSelection()
    }

    /** Drag a handle: snap it to the char under the finger, clamping so start <= end. */
    private fun dragSelection(page: Int, xPt: Float, yPt: Float, isStart: Boolean) {
        val text = selText ?: return
        val range = selRange ?: return
        if (text.pageIndex != page) return
        val idx = TextSelection.nearestCharIndex(text, xPt, yPt)
        if (idx < 0) return
        selRange = TextSelection.adjustRange(range, idx, isStart)
        applySelection()
    }

    /** Tap on the page toggles the title bar (more reading space). */
    private fun toggleChrome() {
        toolbar.visibility =
            if (toolbar.visibility == android.view.View.VISIBLE) android.view.View.GONE
            else android.view.View.VISIBLE
    }

    /** Briefly show "current / total" at the bottom, then fade it out. */
    private fun showPageIndicator(cur: Int, total: Int) {
        if (total <= 0) return
        pageIndicator.text = "${cur + 1} / $total"
        pageIndicator.removeCallbacks(hideIndicator)
        pageIndicator.animate().cancel()
        pageIndicator.alpha = 1f
        pageIndicator.visibility = android.view.View.VISIBLE
        pageIndicator.postDelayed(hideIndicator, 1200)
    }

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
