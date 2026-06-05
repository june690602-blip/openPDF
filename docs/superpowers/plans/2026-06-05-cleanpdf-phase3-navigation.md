# CleanPDF Viewer — Phase 3 (목차 + 페이지 점프 + 페이지번호 이동) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (권장) 또는 superpowers:executing-plans. 단계는 체크박스(`- [ ]`)로 추적.

**Goal:** 큰 PDF를 구조(목차)와 번호로 빠르게 이동한다 — PDF 목차(outline) 패널에서 항목 탭으로 점프, 현재 "N / 전체" 표시, 페이지 번호 입력 점프.

**Architecture:** 기존 Phase 1–2 뷰어 위에 (1) `PdfReaderView.scrollToPage` + 현재페이지 콜백, (2) fitz `loadOutline`→`resolveLink`→`pageNumberFromLocation` 로 목차를 평탄화한 불변 모델, (3) 목차/이동 다이얼로그를 얹는다. fitz 접근은 **반드시 렌더 스레드**에서(단일스레드 불변조건), 순수 로직(평탄화·번호파싱)은 JVM 단위테스트.

**Tech Stack:** Kotlin + Android Views, MuPDF fitz 1.27.1, AndroidX RecyclerView/AppCompat, JUnit4(순수 JVM). **org.json·Robolectric·Android 타입을 테스트에 쓰지 않는다.**

**Spec:** `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md` (§5.5 목차, §5.6 썸네일/번호점프, §11 Phase 3)
**필독:** `docs/superpowers/handoff/2026-06-05-cleanpdf-phase1-handoff.md` §2 불변조건, `CLAUDE.md`.

---

## ⚠️ 실행자(특히 저용량 모델) 필독 — 작업 규칙
1. **수정 파일은 아래 "파일 전체 내용"을 그대로 덮어쓴다.** "메서드를 추가/병합"하지 말 것 — 제공된 전체 파일로 교체. (기존 코드를 머지하려다 깨는 게 1순위 실패.)
2. **테스트는 순수 JVM 만.** `org.json`, Robolectric, `android.*` 타입을 단위테스트에 쓰지 않는다(쓰면 에뮬/SDK 호환 함정에 빠짐). 순수 함수만 테스트한다.
3. **불변조건(절대 변경 금지):** 모든 `PdfDocument`/fitz 호출은 `PageRenderer` 의 단일 executor 스레드에서만(아래 `loadOutlineBlocking` 가 그렇게 함). 비트맵 `recycle()` 추가 금지. `RenderScale` 캡·`BitmapCache`·`commitZoom`·`PdfPageAdapter` 는 이 계획에서 건드리지 않는다.
4. **각 명령의 "Expected" 와 출력이 다르면 멈추고 보고**(BLOCKED). 추측해서 진행 금지.
5. 빌드/테스트/실행 환경: 프로젝트 루트 `C:\dev\openPDF`(git-bash). 에뮬 `emulator-5554`. adb `/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb`. 저장공간 부족 시 `adb -s emulator-5554 shell pm trim-caches 9999999999`. LF→CRLF git 경고는 무해. 브랜치는 새로 만들어 작업(main 직접 커밋 금지).

## 현재 시그니처(참고 — 이미 존재, 바꾸지 말 것)
- `PageRenderer(doc)`: `pageCount`, `sizesBlockingOnRenderThread(): List<PageSize>`, `submit(page, scale, onReady): Future<*>`, `shutdown()`. 단일 `exec` 스레드.
- `PdfDocument`(private val `doc: com.artifex.mupdf.fitz.Document`): `open/openResult`, `pageCount`, `pageSize`, `renderPage`, `needsPassword/authenticate`, `close`.
- `PdfReaderView : RecyclerView`(`LinearLayoutManager`): `setDocument(renderer, sizes)`, `zoom`, private `sizes: List<PageSize>`.
- fitz 1.27.1 (AAR 에서 확인됨): `Document.loadOutline(): Outline[]?`(없으면 null), `Outline{ String title; String uri; Outline[] down }`, `Document.resolveLink(Outline): Location`, `Document.pageNumberFromLocation(Location): Int`(절대 0-based, 미해결 시 -1). `Location{ int chapter; int page }`.

## File Structure (Phase 3)
- Create: `app/src/main/java/.../view/PageJump.kt` — 순수 페이지번호 파싱
- Create: `app/src/test/java/.../view/PageJumpTest.kt`
- Create: `app/src/main/java/.../pdf/OutlineModel.kt` — `PdfOutlineItem`, `RawOutline`, 순수 평탄화
- Create: `app/src/test/java/.../pdf/OutlineModelTest.kt`
- Modify(전체 교체): `app/src/main/java/.../pdf/PdfDocument.kt` — `loadOutline()` 추가
- Modify(전체 교체): `app/src/main/java/.../pdf/PageRenderer.kt` — `loadOutlineBlocking()` 추가
- Modify(전체 교체): `app/src/main/java/.../view/PdfReaderView.kt` — `scrollToPage`, `pageCount`, `onPageChanged`
- Modify(전체 교체): `app/src/main/java/.../MainActivity.kt` — 인디케이터 + 목차/이동 메뉴·다이얼로그
- Modify(추가): `app/src/main/res/menu/reader_menu.xml` — 항목 2개
- Modify(추가): `app/src/main/res/values/strings.xml` — 문자열 4개
- Create: `app/src/androidTest/java/.../NavigationSmokeTest.kt`

---

## Task 1: 순수 로직 — `PageJump` + `OutlineModel` (TDD, 순수 JVM)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/PageJump.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/view/PageJumpTest.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/OutlineModel.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/OutlineModelTest.kt`

- [ ] **Step 1: 실패 테스트 — PageJump**

`app/src/test/java/io/github/june690602_blip/cleanpdf/view/PageJumpTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageJumpTest {
    @Test fun parsesOneBasedToZeroBasedIndex() {
        assertEquals(2, PageJump.parse("3", total = 5)) // 3쪽 -> index 2
    }
    @Test fun clampsAboveTotal() {
        assertEquals(4, PageJump.parse("99", total = 5)) // 마지막(5쪽)=index 4
    }
    @Test fun clampsZeroAndNegativeToFirst() {
        assertEquals(0, PageJump.parse("0", total = 5))
        assertEquals(0, PageJump.parse("-7", total = 5))
    }
    @Test fun trimsWhitespace() {
        assertEquals(1, PageJump.parse("  2 ", total = 5))
    }
    @Test fun rejectsNonNumberOrEmptyOrNoPages() {
        assertNull(PageJump.parse("abc", total = 5))
        assertNull(PageJump.parse("", total = 5))
        assertNull(PageJump.parse("3", total = 0))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "*PageJumpTest"`
Expected: FAIL — `PageJump` unresolved.

- [ ] **Step 3: 구현 — PageJump**

`app/src/main/java/io/github/june690602_blip/cleanpdf/view/PageJump.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

/** Pure page-number parsing for "go to page" input. */
object PageJump {
    /** Parse a 1-based [input] into a 0-based page index clamped to [1, total]; null if invalid. */
    fun parse(input: String, total: Int): Int? {
        if (total <= 0) return null
        val n = input.trim().toIntOrNull() ?: return null
        val oneBased = n.coerceIn(1, total)
        return oneBased - 1
    }
}
```

- [ ] **Step 4: 실패 테스트 — OutlineModel**

`app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/OutlineModelTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineModelTest {
    @Test fun flattensDepthFirstWithLevels() {
        val tree = listOf(
            RawOutline("A", 0, listOf(
                RawOutline("A.1", 2, emptyList()),
                RawOutline("A.2", 4, emptyList()),
            )),
            RawOutline("B", 7, emptyList()),
        )
        val flat = OutlineModel.flatten(tree)
        assertEquals(
            listOf(
                PdfOutlineItem("A", 0, 0),
                PdfOutlineItem("A.1", 2, 1),
                PdfOutlineItem("A.2", 4, 1),
                PdfOutlineItem("B", 7, 0),
            ),
            flat,
        )
    }

    @Test fun emptyInputGivesEmptyList() {
        assertEquals(emptyList<PdfOutlineItem>(), OutlineModel.flatten(emptyList()))
    }
}
```

- [ ] **Step 5: 실패 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "*OutlineModelTest"`
Expected: FAIL — `RawOutline`/`OutlineModel` unresolved.

- [ ] **Step 6: 구현 — OutlineModel**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/OutlineModel.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/** A flattened outline (bookmark) entry. [page] is a 0-based page index, or -1 if it has no target. */
data class PdfOutlineItem(val title: String, val page: Int, val level: Int)

/** A raw outline tree node (engine-agnostic), used to keep the flatten logic pure/testable. */
data class RawOutline(val title: String, val page: Int, val children: List<RawOutline>)

/** Pure depth-first flatten of an outline tree into an indented list. */
object OutlineModel {
    fun flatten(roots: List<RawOutline>): List<PdfOutlineItem> {
        val out = ArrayList<PdfOutlineItem>()
        fun walk(nodes: List<RawOutline>, level: Int) {
            for (n in nodes) {
                out.add(PdfOutlineItem(n.title, n.page, level))
                walk(n.children, level + 1)
            }
        }
        walk(roots, 0)
        return out
    }
}
```

- [ ] **Step 7: 통과 확인 (둘 다)**

Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "*PageJumpTest" --tests "*OutlineModelTest"`
Expected: BUILD SUCCESSFUL, 7 tests passed (PageJump 5 + OutlineModel 2), 0 failures.

- [ ] **Step 8: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: add pure PageJump parsing and OutlineModel flatten with unit tests"
```

---

## Task 2: fitz 목차 추출 — `PdfDocument.loadOutline` + `PageRenderer.loadOutlineBlocking` (전체 파일 교체)

> fitz 접근이라 **렌더 스레드**에서 실행해야 한다(불변조건). `PdfDocument.loadOutline()` 가 fitz 트리를 `RawOutline` 로 변환 후 순수 `OutlineModel.flatten` 호출. `PageRenderer.loadOutlineBlocking()` 가 그것을 `exec` 스레드에서 돌린다.

**Files:**
- Modify(전체 교체): `app/src/main/java/.../pdf/PdfDocument.kt`
- Modify(전체 교체): `app/src/main/java/.../pdf/PageRenderer.kt`

- [ ] **Step 1: `PdfDocument.kt` 를 아래 전체 내용으로 교체**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfDocument.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
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
        val b = page.getBounds()       // fitz Rect: x0,y0,x1,y1 in points
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

    /**
     * Flattened outline (bookmarks). Empty if the PDF has none. Each item's page is a 0-based
     * index (or -1 if the bookmark has no resolvable destination).
     * MUST be called on the render thread (it touches the fitz Document).
     */
    fun loadOutline(): List<PdfOutlineItem> {
        val raw = doc.loadOutline() ?: return emptyList()
        return OutlineModel.flatten(convert(raw))
    }

    private fun convert(nodes: Array<Outline>): List<RawOutline> = nodes.map { n ->
        val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(n)) }.getOrDefault(-1)
        RawOutline(n.title ?: "", page, n.down?.let { convert(it) } ?: emptyList())
    }

    fun close() = doc.destroy()

    companion object {
        /** Open a PDF from a local filesystem path. Throws on unreadable/corrupt files. */
        fun open(path: String): PdfDocument = PdfDocument(Document.openDocument(path))

        /** Open a PDF, returning a [PdfOpenResult] instead of throwing. NeedsPassword if encrypted. */
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

- [ ] **Step 2: `PageRenderer.kt` 를 아래 전체 내용으로 교체** (`loadOutlineBlocking` 추가)

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
 * removes a not-yet-started task from the queue (the common case on fast scroll) and the
 * [Thread.isInterrupted] guard skips a render whose thread was interrupted before it began.
 * Pages render fast, so an occasional un-abortable in-flight render is acceptable.
 */
class PageRenderer(private val doc: PdfDocument) {
    private val exec = Executors.newSingleThreadExecutor()

    val pageCount: Int get() = doc.pageCount

    fun sizesBlockingOnRenderThread(): List<PageSize> =
        exec.submit<List<PageSize>> { (0 until doc.pageCount).map { doc.pageSize(it) } }.get()

    /** Load the document outline on the render thread (fitz access is single-threaded). Blocking. */
    fun loadOutlineBlocking(): List<PdfOutlineItem> =
        exec.submit<List<PdfOutlineItem>> { doc.loadOutline() }.get()

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
Expected: BUILD SUCCESSFUL. (fitz `Outline`/`resolveLink`/`pageNumberFromLocation` 미해결로 실패하면 멈추고 보고 — 시그니처는 AAR 에서 확인된 것이라 맞아야 함.)

- [ ] **Step 4: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: extract PDF outline on render thread (loadOutline + loadOutlineBlocking)"
```

---

## Task 3: `PdfReaderView` — `scrollToPage` + `pageCount` + `onPageChanged` (전체 파일 교체)

> 한 행=한 페이지라 `LinearLayoutManager.scrollToPositionWithOffset(i, 0)` 가 페이지 i 상단으로 점프. 스크롤 시 첫 가시 페이지를 `onPageChanged(current0, total)` 로 통지. `setDocument` 직후 초기값 1회 발사.

**Files:**
- Modify(전체 교체): `app/src/main/java/.../view/PdfReaderView.kt`

- [ ] **Step 1: `PdfReaderView.kt` 를 아래 전체 내용으로 교체**

`app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize

class PdfReaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    private var renderer: PageRenderer? = null
    private var cache: BitmapCache? = null
    private var sizes: List<PageSize> = emptyList()
    private var adapterImpl: PdfPageAdapter? = null
    private val gapPx = (resources.displayMetrics.density * 8).toInt()
    var zoom: Float = 1f; private set

    /** Total page count of the attached document (0 if none). */
    val pageCount: Int get() = sizes.size

    /** Invoked with (0-based current page, total) when the first visible page changes. */
    var onPageChanged: ((current: Int, total: Int) -> Unit)? = null

    private val minZoom = 1f
    private val maxZoom = 8f
    private var liveScale = 1f  // transient visual scale during an active pinch

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                liveScale = (liveScale * d.scaleFactor).coerceIn(minZoom / zoom, maxZoom / zoom)
                scaleX = liveScale; scaleY = liveScale  // cheap visual feedback only
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                commitZoom(zoom * liveScale)
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                commitZoom(if (zoom > 1.5f) 1f else 2.5f); return true
            }
        })

    init {
        layoutManager = LinearLayoutManager(context)
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = layoutManager as? LinearLayoutManager ?: return
                val pos = lm.findFirstVisibleItemPosition()
                if (pos != NO_POSITION) onPageChanged?.invoke(pos, sizes.size)
            }
        })
    }

    /** Attach an opened document. [sizes] precomputed off-thread by the caller. */
    fun setDocument(renderer: PageRenderer, sizes: List<PageSize>) {
        this.renderer = renderer; this.sizes = sizes
        // ~96MB bitmap budget (tune later); guard against tiny heaps.
        this.cache = BitmapCache(maxBytes = 96 * 1024 * 1024)
        val a = PdfPageAdapter(renderer, cache!!, pageSizeProvider = { i -> sizes[i] })
        adapterImpl = a; adapter = a
        relayout()
        post { onPageChanged?.invoke(0, this.sizes.size) }
    }

    /** Jump so page [index] (0-based) is at the top of the viewport. Clamped to a valid page. */
    fun scrollToPage(index: Int) {
        if (sizes.isEmpty()) return
        val i = index.coerceIn(0, sizes.size - 1)
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, 0)
        onPageChanged?.invoke(i, sizes.size)
    }

    private fun relayout() {
        val r = renderer ?: return
        if (width == 0) { post { relayout() }; return }
        val layout = PageLayout.compute(sizes, fitWidthPx = width, gapPx = gapPx, zoom = zoom)
        adapterImpl?.submitLayout(layout)
    }

    private fun commitZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(minZoom, maxZoom)
        liveScale = 1f; scaleX = 1f; scaleY = 1f
        if (clamped == zoom) return
        zoom = clamped
        // Do NOT cache.clear() here: PageKey includes the quantized scale, so stale-scale bitmaps are
        // simply cache misses (re-rendered at the new scale) and the LRU drops them by budget. Clearing
        // would also throw away pages the user may immediately zoom back to.
        relayout()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        return super.onTouchEvent(e) || scaleDetector.isInProgress
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); relayout()
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: PdfReaderView scrollToPage + current-page callback + pageCount"
```

---

## Task 4: UI 배선 — 인디케이터 + 목차/페이지이동 메뉴·다이얼로그 (전체/추가)

**Files:**
- Modify(추가): `app/src/main/res/values/strings.xml`
- Modify(추가): `app/src/main/res/menu/reader_menu.xml`
- Modify(전체 교체): `app/src/main/java/.../MainActivity.kt`

- [ ] **Step 1: 문자열 추가** — `res/values/strings.xml` 의 `</resources>` 바로 위에 아래 4줄 추가(같은 name 이 이미 있으면 그 줄은 건너뜀):

```xml
    <string name="outline">목차</string>
    <string name="no_outline">목차 없음</string>
    <string name="goto_title">페이지 이동</string>
    <string name="goto_hint">1–%d 페이지</string>
```

- [ ] **Step 2: 메뉴 항목 추가** — `res/menu/reader_menu.xml` 의 `</menu>` 바로 위에 아래 2개 추가:

```xml
    <item
        android:id="@+id/action_outline"
        android:title="@string/outline"
        android:showAsAction="never" />
    <item
        android:id="@+id/action_goto"
        android:title="@string/goto_title"
        android:showAsAction="never" />
```

- [ ] **Step 3: `MainActivity.kt` 를 아래 전체 내용으로 교체**

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
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleanpdf.io.PdfSource
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.pdf.PdfOpenResult
import io.github.june690602_blip.cleanpdf.view.PageJump
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var renderer: PageRenderer? = null
    private lateinit var reader: PdfReaderView
    private lateinit var errorView: android.widget.TextView
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

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
```

- [ ] **Step 4: 빌드 + 실기 검증**

```bash
cd /c/dev/openPDF && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug
ADB=/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb
$ADB -s emulator-5554 shell am force-stop io.github.june690602_blip.cleanpdf
$ADB -s emulator-5554 shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity
sleep 3
$ADB -s emulator-5554 exec-out screencap -p > task4_indicator.png
# 페이지 이동: 오버플로(⋮) -> "페이지 이동" -> 3 입력 -> 확인. (좌표는 task4_indicator.png 보고 결정)
```
Read `task4_indicator.png`: 툴바에 "1 / 3"(샘플 3페이지) 부제가 보이는지 확인. 오버플로에 "목차","페이지 이동" 항목이 있는지 확인. (샘플 PDF 는 목차가 없어 "목차" 탭 시 "목차 없음" 토스트가 정상.)
Expected: 인디케이터 표시, 메뉴 항목 존재, 크래시 없음(`$ADB -s emulator-5554 logcat -d | grep -iE "FATAL|AndroidRuntime"` 비어 있음).

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: page indicator, outline panel, and go-to-page dialog"
```

---

## Task 5: 계측 스모크 + 최종 검증

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/NavigationSmokeTest.kt`

- [ ] **Step 1: 계측 테스트 작성** (scrollToPage + loadOutline 이 크래시 없이 동작)

`app/src/androidTest/java/io/github/june690602_blip/cleanpdf/NavigationSmokeTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @Test fun scrollToLastPageDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500) // async open + first render (dev sample, 3 pages)
            scenario.onActivity { a ->
                val reader = a.findViewById<PdfReaderView>(R.id.reader)
                assertTrue("sample should have pages", reader.pageCount >= 1)
                reader.scrollToPage(reader.pageCount - 1) // jump to last page
            }
            Thread.sleep(500)
            scenario.onActivity { a ->
                val reader = a.findViewById<PdfReaderView>(R.id.reader)
                assertTrue("still has pages after jump", reader.pageCount >= 1)
            }
        }
    }
}
```

- [ ] **Step 2: 실행**

Run: `cd /c/dev/openPDF && /c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb -s emulator-5554 shell pm trim-caches 9999999999 && ./gradlew :app:connectedDebugAndroidTest --tests "*NavigationSmokeTest"`
Expected: PASS.

- [ ] **Step 3: 전체 스위트 + 빌드 (최종 게이트)**

```bash
cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest   # 기존 26 + PageJump 5 + OutlineModel 2 = 33
cd /c/dev/openPDF && ./gradlew :app:assembleDebug
```
Expected: 모든 단위테스트 PASS(33), BUILD SUCCESSFUL.

- [ ] **Step 4: 수동 검증 체크리스트** (커밋 본문에 결과 기록; 실기/실파일)
- [ ] 멀티페이지 PDF에서 스크롤 시 툴바 "N / 전체" 가 갱신됨
- [ ] "페이지 이동" → 번호 입력 → 해당 페이지로 점프 (범위 밖 입력은 양끝으로 클램프)
- [ ] **목차 있는 PDF**(예: 보고서/매뉴얼)에서 "목차" → 항목 탭 → 해당 페이지로 점프
- [ ] 목차 없는 PDF → "목차" 탭 시 "목차 없음" 토스트, 크래시 없음
- [ ] 줌/스크롤/열기(기존 기능) 회귀 없음

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "test: add navigation instrumented smoke; verify Phase 3 manually"
```

**✅ Phase 3 done when:** 목차 패널·페이지 점프·페이지번호 이동·현재페이지 표시가 동작하고; 단위 33 + 계측(neviagtion) 통과; 수동 체크리스트 클린.

---

## Self-Review

**Spec coverage (이 plan = 스펙 §5.5 목차 + §5.6 페이지번호 점프 + §11 Phase 3 의 "목차/페이지 점프" 부분):**
- 목차(outline) 패널 + 항목 점프 → Task 2,4 ✓ (`loadOutline`/`resolveLink`/`pageNumberFromLocation`)
- 현재 페이지 표시 + 페이지 점프 → Task 3,4 ✓ (`scrollToPage`/`onPageChanged`)
- 페이지 번호 입력 점프 → Task 1,4 ✓ (`PageJump` 순수 파싱)
- **이 plan에 없음(의도적 분리):** **썸네일 점프**(스펙 §5.6) — 비동기 렌더 그리드라 독립 서브시스템. 별도 plan `2026-06-XX-cleanpdf-phase3_5-thumbnails.md` 로. 접근 스케치: 새 `ThumbnailAdapter`(PdfPageAdapter 패턴 복제, 고정 소형 스케일, 별도 작은 `BitmapCache`, **recycle 금지 동일**) + GridLayoutManager RecyclerView 오버레이(activity_main 에 `thumb_grid` 추가, 토글) → 셀 탭 시 `reader.scrollToPage(pos)` + 오버레이 숨김. 렌더러는 메인과 공유(단일스레드 직렬화). 또한 §5.7 야간반전·§4 검색·§5 선택은 Phase 6/4/5.

**Placeholder scan:** `...` 없음. 모든 파일은 전체 내용 제공(수정 4파일은 통째 교체, 메뉴/문자열은 정확한 추가 줄). org.json/Robolectric/Android-타입 단위테스트 없음(순수만).

**Type consistency:** `PageJump.parse(input, total): Int?`, `OutlineModel.flatten(List<RawOutline>): List<PdfOutlineItem>`, `RawOutline(title, page, children)`, `PdfOutlineItem(title, page, level)`, `PdfDocument.loadOutline(): List<PdfOutlineItem>`, `PageRenderer.loadOutlineBlocking()`, `PdfReaderView.scrollToPage(index)/pageCount/onPageChanged(cur,total)` — 태스크 간 일관. fitz: `Document.loadOutline()`, `resolveLink(Outline)`, `pageNumberFromLocation(Location)` — AAR 검증 시그니처.

**불변조건 준수:** `loadOutline` 은 `loadOutlineBlocking`(=`exec` 스레드)으로만 호출 — 단일 렌더 스레드 유지. 비트맵 recycle/캐시/렌더캡/줌 클래스는 미변경. `showDocument` 의 렌더러 교체 순서 유지.

**저용량 모델 위험요소(대응):**
1. fitz 시그니처 추론 불가 → 정확한 코드를 박아넣음(AAR 검증). 컴파일 실패 시 멈추라고 명시.
2. 부분 병합 실패 → 수정 파일은 전체 교체로 제공.
3. 테스트 환경 함정(org.json/Robolectric/API36) → 순수 JVM 테스트만.
4. 목차 픽스처 생성 난이도 → 자동 테스트는 "loadOutline 크래시 없음 + 평탄화 순수 단위테스트"로, 실제 목차 점프는 수동 체크리스트(실파일).
