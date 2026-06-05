# CleanPDF Viewer — Phase 4 (검색) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (권장) 또는 superpowers:executing-plans. 단계는 체크박스(`- [ ]`).

**Goal:** PDF 전체에서 텍스트를 검색해 결과를 목록으로 보여주고, 항목을 탭하면 그 페이지로 점프한다 (스펙 §5.3). **하이라이트 + 순차 다음/이전은 Phase 4.5로 분리**(렌더 파이프라인 침습 최소화).

**Architecture:** 순수 `SearchHit` 모델 + `SearchHits.labels`(TDD) / fitz `Page.search`(렌더 스레드, `PageRenderer.searchBlocking`)로 전체 페이지 검색 → 히트 목록 → 검색 다이얼로그(쿼리 입력)+결과 다이얼로그(히트수 제목, 탭→`PdfReaderView.scrollToPage`). UI는 AlertDialog 2개라 레이아웃 변경·새 위젯 없음.

**Tech Stack:** Kotlin + Android Views, MuPDF fitz 1.27.1, AndroidX AppCompat. 순수 로직은 JVM 단위테스트. **org.json/Robolectric/Android-타입 테스트 금지.**

**Spec:** `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md` (§5.3 검색)
**필독:** `handoff/2026-06-05-cleanpdf-phase1-handoff.md` §2 불변조건, `handoff/2026-06-05-cleanpdf-phase3-handoff.md` §4(저용량-모델 교훈), `CLAUDE.md`. **선행:** Phase 3·3.5가 `main` 에 있어야 함(`scrollToPage` 사용).

---

## ⚠️ 실행자(특히 저용량 모델) 필독 — 작업 규칙
1. **수정 파일은 아래 "파일 전체 내용"을 그대로 덮어쓴다.** 부분 병합 금지. 메뉴/문자열만 "추가"(닫는 태그 앞 삽입).
2. **불변조건(절대 변경 금지):** 모든 `PdfDocument`/fitz 접근은 `PageRenderer` 의 단일 스레드(=`searchBlocking` 가 그렇게 함). 비트맵 recycle 추가 금지. `RenderScale`/`BitmapCache`/`PdfPageAdapter`/`ThumbnailAdapter`/줌 로직은 건드리지 않는다.
3. **테스트는 순수 JVM 만**(`org.json`/Robolectric/Android-타입 금지).
4. 각 명령의 "Expected" 와 다르면 멈추고 보고(BLOCKED). 추측 금지.
5. **디바이스 검증은 가볍게**(handoff §4 교훈): 빌드+설치+결과 다이얼로그 스크린샷 1장까지만 필수. 메뉴→다이얼로그 멀티스텝 탭 자동화가 안 되면 정직히 보고(빌드/설치/계측은 필수 성공). 탭→점프는 이미 검증된 `scrollToPage` 라 저위험.
6. 환경: 루트 `C:\dev\openPDF`(git-bash). 에뮬 `emulator-5554`, adb `/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb`. 저장공간 부족 시 `pm trim-caches 9999999999`(또는 `adb uninstall io.github.june690602_blip.cleanpdf` 후 재시도). 루트 `*.png` gitignore. LF→CRLF 무해. **브랜치 새로 만들어 작업.**

## fitz 검색 API (AAR 1.27.1 검증 — 추론 금지, 이대로)
- `Page.search(needle: String): com.artifex.mupdf.fitz.Quad[][]` (히트 배열, 각 히트 = `Quad[]`), 또는 `search(needle, flags: Int)`.
- 플래그(대소문자 무시): `com.artifex.mupdf.fitz.StructuredText.SEARCH_IGNORE_CASE`.
- `Quad{ float ul_x,ul_y,ur_x,ur_y,ll_x,ll_y,lr_x,lr_y }`, `Quad.toRect(): Rect`. `Rect{ float x0,y0,x1,y1 }` (PDF 포인트).

## 현재 시그니처(이미 존재 — 바꾸지 말 것)
- `PageRenderer(doc)`: `pageCount`, `sizesBlockingOnRenderThread()`, `loadOutlineBlocking()`, `submit(...)`, `shutdown()`. private `exec` 단일 스레드, private `doc: PdfDocument`.
- `PdfDocument`(private `doc: com.artifex.mupdf.fitz.Document`): `open/openResult`, `pageCount`, `pageSize`, `renderPage`, `loadOutline`, `needsPassword/authenticate`, `close`.
- `PdfReaderView.scrollToPage(index: Int)`, `pageCount`.
- `MainActivity`(현재 = Phase 3.5 버전): `renderer`(@Volatile), `reader`, `bg`, 메뉴(open/recent/outline/goto/thumbnails), `showDocument`, `currentSizes` 등.

## File Structure (Phase 4)
- Create: `app/src/main/java/.../pdf/SearchHit.kt` — 히트 모델 + 순수 `SearchHits.labels`
- Test: `app/src/test/java/.../pdf/SearchHitsTest.kt`
- Modify(전체 교체): `app/src/main/java/.../pdf/PdfDocument.kt` — `search()` 추가
- Modify(전체 교체): `app/src/main/java/.../pdf/PageRenderer.kt` — `searchBlocking()` 추가
- Modify(추가): `app/src/main/res/values/strings.xml` — 문자열 4개
- Modify(추가): `app/src/main/res/menu/reader_menu.xml` — "검색" 항목 1개
- Modify(전체 교체): `app/src/main/java/.../MainActivity.kt` — 검색 다이얼로그 + 결과
- Create: `app/src/androidTest/java/.../SearchSmokeTest.kt`

---

## Task 1: 순수 — `SearchHit` + `SearchHits.labels` (TDD)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/SearchHit.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/SearchHitsTest.kt`

- [ ] **Step 1: 실패 테스트**

`app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/SearchHitsTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHitsTest {
    private fun hit(page: Int) = SearchHit(page, 0f, 0f, 1f, 1f)

    @Test fun labelsAreOneBasedPageNumbers() {
        val labels = SearchHits.labels(listOf(hit(0), hit(2), hit(2)))
        assertEquals(listOf("1쪽", "3쪽", "3쪽"), labels)
    }

    @Test fun emptyHitsGiveEmptyLabels() {
        assertEquals(emptyList<String>(), SearchHits.labels(emptyList()))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "*SearchHitsTest"`
Expected: FAIL — `SearchHit`/`SearchHits` unresolved.

- [ ] **Step 3: 구현**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/SearchHit.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/** One search hit: its 0-based [page] and bounding box in PDF points. Immutable. */
data class SearchHit(val page: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Pure helpers for presenting search hits. */
object SearchHits {
    /** One label per hit: "<1-based page>쪽". */
    fun labels(hits: List<SearchHit>): List<String> = hits.map { "${it.page + 1}쪽" }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "*SearchHitsTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed, 0 failures.

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: add SearchHit model and pure SearchHits.labels with unit tests"
```

---

## Task 2: fitz 검색 — `PdfDocument.search` + `PageRenderer.searchBlocking` (전체 파일 교체)

> fitz 접근 → **렌더 스레드**. 각 페이지 `page.search(needle, SEARCH_IGNORE_CASE)` → `Quad[][]`, 히트별 quad 들의 bounding box 를 `SearchHit` 로. 대형 문서 보호용 `maxHits` 상한.

**Files:**
- Modify(전체 교체): `app/src/main/java/.../pdf/PdfDocument.kt`
- Modify(전체 교체): `app/src/main/java/.../pdf/PageRenderer.kt`

- [ ] **Step 1: `PdfDocument.kt` 전체 교체**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfDocument.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice

/**
 * Thin wrapper over MuPDF [Document]. NOT thread-safe: all calls must run on a
 * single dedicated thread (see PageRenderer). Always [close] when done.
 */
class PdfDocument private constructor(private val doc: Document) {

    val pageCount: Int = doc.countPages()

    fun needsPassword(): Boolean = doc.needsPassword()

    fun authenticate(password: String): Boolean = doc.authenticatePassword(password)

    fun pageSize(index: Int): PageSize {
        val page = doc.loadPage(index)
        val b = page.getBounds()
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

    /** Flattened outline (bookmarks). Empty if none. MUST run on the render thread. */
    fun loadOutline(): List<PdfOutlineItem> {
        val raw = doc.loadOutline() ?: return emptyList()
        return OutlineModel.flatten(convert(raw))
    }

    private fun convert(nodes: Array<Outline>): List<RawOutline> = nodes.map { n ->
        val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(n)) }.getOrDefault(-1)
        RawOutline(n.title ?: "", page, n.down?.let { convert(it) } ?: emptyList())
    }

    /**
     * Case-insensitive full-text search across all pages. Returns up to [maxHits] hits (each with a
     * 0-based page + bounding box in PDF points). MUST run on the render thread.
     */
    fun search(needle: String, maxHits: Int = 500): List<SearchHit> {
        if (needle.isBlank()) return emptyList()
        val out = ArrayList<SearchHit>()
        for (p in 0 until pageCount) {
            val page = doc.loadPage(p)
            val hits = page.search(needle, StructuredText.SEARCH_IGNORE_CASE)
            page.destroy()
            if (hits != null) for (quads in hits) {
                if (quads.isEmpty()) continue
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (q in quads) {
                    val r = q.toRect()
                    if (r.x0 < x0) x0 = r.x0; if (r.y0 < y0) y0 = r.y0
                    if (r.x1 > x1) x1 = r.x1; if (r.y1 > y1) y1 = r.y1
                }
                out.add(SearchHit(p, x0, y0, x1, y1))
                if (out.size >= maxHits) return out
            }
        }
        return out
    }

    fun close() = doc.destroy()

    companion object {
        fun open(path: String): PdfDocument = PdfDocument(Document.openDocument(path))

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
```

- [ ] **Step 2: `PageRenderer.kt` 전체 교체** (`searchBlocking` 추가)

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageRenderer.kt`:
```kotlin
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
 * removes a not-yet-started task from the queue and the [Thread.isInterrupted] guard skips a
 * render whose thread was interrupted before it began. Pages render fast, so this is acceptable.
 */
class PageRenderer(private val doc: PdfDocument) {
    private val exec = Executors.newSingleThreadExecutor()

    val pageCount: Int get() = doc.pageCount

    fun sizesBlockingOnRenderThread(): List<PageSize> =
        exec.submit<List<PageSize>> { (0 until doc.pageCount).map { doc.pageSize(it) } }.get()

    /** Load the document outline on the render thread (fitz access is single-threaded). Blocking. */
    fun loadOutlineBlocking(): List<PdfOutlineItem> =
        exec.submit<List<PdfOutlineItem>> { doc.loadOutline() }.get()

    /** Full-text search on the render thread (fitz access is single-threaded). Blocking. */
    fun searchBlocking(needle: String): List<SearchHit> =
        exec.submit<List<SearchHit>> { doc.search(needle) }.get()

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
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`Page.search(String,int)`/`StructuredText.SEARCH_IGNORE_CASE`/`Quad.toRect()` 미해결 시 멈추고 보고 — AAR 검증 시그니처라 맞아야 함.)

- [ ] **Step 4: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: full-text search on render thread (PdfDocument.search + searchBlocking)"
```

---

## Task 3: UI — 메뉴 "검색" + 검색/결과 다이얼로그 (추가/전체 교체)

**Files:**
- Modify(추가): `app/src/main/res/values/strings.xml`
- Modify(추가): `app/src/main/res/menu/reader_menu.xml`
- Modify(전체 교체): `app/src/main/java/.../MainActivity.kt`

- [ ] **Step 1: 문자열 추가** — `res/values/strings.xml` 의 `</resources>` 바로 위에(같은 name 있으면 건너뜀):

```xml
    <string name="search">검색</string>
    <string name="search_hint">검색어</string>
    <string name="search_none">검색 결과 없음</string>
    <string name="search_count">%d건</string>
```

- [ ] **Step 2: 메뉴 항목 추가** — `res/menu/reader_menu.xml` 의 `</menu>` 바로 위에:

```xml
    <item
        android:id="@+id/action_search"
        android:title="@string/search"
        android:showAsAction="never" />
```

- [ ] **Step 3: `MainActivity.kt` 전체 교체** (추가: `action_search` 분기, `showSearch()`/`showSearchResults()`)

`app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt`:
```kotlin
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
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import io.github.june690602_blip.cleanpdf.pdf.SearchHits
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
                    else runOnUiThread { promptPassword(doc, file) }
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
        val cell = (resources.displayMetrics.density * 96).toInt()
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
            renderer = r, sizes = currentSizes, cache = cache, cellWidthPx = cell,
        ) { page -> reader.scrollToPage(page); dialog.dismiss() }
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
                    runOnUiThread { showSearchResults(hits) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSearchResults(hits: List<SearchHit>) {
        if (hits.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.search_none, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = SearchHits.labels(hits).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_count, hits.size))
            .setItems(labels) { _, which -> reader.scrollToPage(hits[which].page) }
            .show()
    }

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
```

- [ ] **Step 4: 빌드 + 가벼운 실기 검증** (handoff §4 교훈 — 결과 다이얼로그까지만 필수)

```bash
cd /c/dev/openPDF && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug
ADB=/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb
$ADB -s emulator-5554 shell am force-stop io.github.june690602_blip.cleanpdf
$ADB -s emulator-5554 shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity
sleep 3
# 오버플로(⋮) -> "검색" -> 입력란에 "Page" 입력 -> 검색. (좌표는 스크린샷 보고 결정)
# 입력은 adb 로: 다이얼로그가 떴을 때 `$ADB -s emulator-5554 shell input text "Page"` 후 확인 버튼 탭.
$ADB -s emulator-5554 exec-out screencap -p > task3_search_results.png
$ADB -s emulator-5554 logcat -d | grep -iE "FATAL|AndroidRuntime|recycled bitmap" | head || echo "no crash"
```
Read `task3_search_results.png` (또는 검색 입력 후 결과 다이얼로그 스크린샷): 결과 다이얼로그 제목이 "N건"(샘플에서 "Page" 검색 시 ≥3건)이고 "1쪽/2쪽/3쪽" 항목이 보이는지 확인. **멀티스텝 탭이 안 되면**(handoff §4) 빌드+설치 성공 + 계측(Task 4)으로 갈음하고 정직히 보고. 크래시 로그 비어 있어야 함.

- [ ] **Step 5: 커밋** (스크린샷 스테이징 금지)

```bash
cd /c/dev/openPDF && git status --short   # *.png 없어야 함
cd /c/dev/openPDF && git add -A && git commit -m "feat: full-text search dialog with results list and jump"
```

---

## Task 4: 계측 검색 스모크 + 최종 검증

> 샘플 PDF엔 "CleanPDF - Page 1/2/3" 텍스트가 있어 **검색을 자동 계측으로 검증 가능**(목차와 달리).

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/SearchSmokeTest.kt`

- [ ] **Step 1: 계측 테스트**

`app/src/androidTest/java/io/github/june690602_blip/cleanpdf/SearchSmokeTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchSmokeTest {
    @Test fun findsTextInSample() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "search_sample.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val doc = PdfDocument.open(out.absolutePath)
        val hits = doc.search("Page")   // 샘플에 "CleanPDF - Page N" 존재
        doc.close()

        assertTrue("'Page' should be found in the sample", hits.isNotEmpty())
        assertTrue("hit page should be valid", hits.all { it.page in 0 until 3 })
    }
}
```
> 주: `PdfDocument` 는 단일 스레드 계약이지만, 이 테스트는 자체 스레드(계측 테스트 스레드) 하나에서만 open→search→close 하므로 안전(렌더러 없이 직접 사용).

- [ ] **Step 2: 실행**

Run: `cd /c/dev/openPDF && /c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb -s emulator-5554 shell pm trim-caches 9999999999 && ./gradlew :app:connectedDebugAndroidTest`
Expected: 모든 계측 PASS (기존 4 + SearchSmoke 1 = 5). (`--tests` 필터는 `connectedDebugAndroidTest` 가 안 받으니 전체 실행. 저장공간 부족 시 `adb uninstall io.github.june690602_blip.cleanpdf` 후 재시도.)

- [ ] **Step 3: 전체 단위 + 빌드**

```bash
cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest   # 기존 33 + SearchHits 2 = 35
cd /c/dev/openPDF && ./gradlew :app:assembleDebug
```
Expected: 단위 35 PASS(0 실패), BUILD SUCCESSFUL.

- [ ] **Step 4: 수동 체크리스트** (커밋 본문에 기록)
- [ ] 멀티페이지 PDF에서 "검색" → 단어 입력 → "N건" + 페이지 목록 → 탭하면 그 페이지로 점프
- [ ] 없는 단어 → "검색 결과 없음" 토스트
- [ ] 대용량 PDF 검색이 UI 블로킹/크래시 없이 끝남(렌더 스레드 + maxHits 상한)

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "test: add search instrumented smoke; verify Phase 4 manually"
```

**✅ Phase 4 done when:** 검색 → 결과(히트수) → 페이지 점프가 동작하고; 단위 35 + 계측 5 통과; 크래시 없음.

---

## Self-Review

**Spec coverage (스펙 §5.3):** 전체검색(fitz `Page.search`) + 히트수 + 결과에서 점프 → Task 2,3,4 ✓. **이 plan에 없음(의도적 분리, handoff §4 교훈):**
- **하이라이트 오버레이** + **순차 다음/이전(검색 바)** → **Phase 4.5** 별도 계획. 접근: `SearchHit` 의 rect(PDF 포인트)를 페이지 픽셀로 변환해 `PdfPageAdapter` 셀 위에 오버레이 뷰로 그림(또는 페이지 비트맵에 합성), `PdfReaderView` 에 `scrollToHit(page, fracY)` 추가, 하단 검색바(prev/next/count) 위젯. 렌더 어댑터 침습이 있어 분리.

**Placeholder scan:** `...` 없음(전체 파일/추가 줄). 순수 `SearchHits.labels` 단위테스트 2. 검색 자동 계측(샘플 텍스트 이용).

**Type consistency:** `SearchHit(page,x0,y0,x1,y1)`, `SearchHits.labels(List<SearchHit>): List<String>`, `PdfDocument.search(needle,maxHits): List<SearchHit>`, `PageRenderer.searchBlocking(needle): List<SearchHit>`, `MainActivity.showSearch/showSearchResults` — 일관. fitz: `Page.search(String,int)`, `StructuredText.SEARCH_IGNORE_CASE`, `Quad.toRect()`, `Rect.x0..y1` — AAR 검증.

**불변조건 준수:** 검색은 `searchBlocking`(=`exec` 스레드)으로만 — 단일 렌더 스레드 유지. 렌더/캐시/줌/썸네일 클래스 미변경. `MainActivity` 는 Phase 3.5 버전 + (action_search 분기, showSearch/showSearchResults, import SearchHit/SearchHits) 만 추가.

**저용량-모델 위험요소(대응):** ① fitz 검색 시그니처 → AAR 검증해 박음. ② 부분 병합 → 전체 교체. ③ org.json/Robolectric/API36 → 순수 JVM 테스트만. ④ 디바이스 멀티스텝 탭(handoff §4) → 검증을 가볍게 + 자동 계측이 검색을 직접 커버(샘플 텍스트). ⑤ 단일 스레드 계약 → 계측 테스트는 자체 스레드 단독 사용으로 안전.
