# CleanPDF Viewer — Phase 4.5 (검색 하이라이트 + 순차 이동) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (권장) 또는 superpowers:executing-plans. 단계는 체크박스(`- [ ]`).

**Goal:** 검색 결과를 형광 하이라이트로 표시하고, 하단 검색바(◀ 현재/전체 ▶ + 닫기)로 히트 사이를 순차 이동하며, 선택된 히트가 보이도록 자동 스크롤한다 (스펙 §5.3 "찾기 바 → 다음/이전 → 히트수"의 나머지). Phase 4의 결과 목록 다이얼로그를 **검색바로 대체**.

**Architecture:** 순수 `SearchCursor`(히트목록+현재인덱스, wrapping next/prev) + 순수 `HighlightGeometry`(PDF 포인트 rect → 셀 픽셀, `FloatArray` 반환으로 android 타입 회피) / 뷰: 각 페이지 셀(`FrameLayout`)에 `HighlightOverlayView` 오버레이(ImageView 위) — **비트맵 캐시 미오염**(불변조건). `PdfReaderView.scrollToHit`/`setSearchHighlights`(현재 `PageLayout` 보관) + `PdfPageAdapter` 하이라이트 전달. UI: `activity_main.xml` 하단 검색바 + `MainActivity` 검색모드(다이얼로그 제거).

**Tech Stack:** Kotlin + Android Views, MuPDF fitz 1.27.1, AndroidX RecyclerView/AppCompat. 순수 로직은 JVM 단위테스트(**org.json/Robolectric/Android-타입 금지** — 좌표변환은 `FloatArray`로).

**Spec:** `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md` (§5.3)
**필독:** `handoff/2026-06-05-cleanpdf-phase1-handoff.md` §2 불변조건, `handoff/2026-06-05-cleanpdf-phase4-handoff.md`(§2 검색 API/컴포넌트, §4 IME/surgical, §6 다음), `CLAUDE.md`. **선행:** Phase 4가 `main`(`SearchHit`/`PdfDocument.search`/`PageRenderer.searchBlocking`/`scrollToPage`).

---

## ⚠️ 실행자 필독 — 작업 규칙
1. 수정 파일은 명시된 위치에 정확히 추가/교체. **불변조건(절대 변경 금지):** ① 모든 fitz 접근은 `PageRenderer` 단일 스레드. ② 비트맵 `recycle()` 추가 금지. ③ `BitmapCache`/`RenderScale`/렌더 제출 로직 불변 — 하이라이트는 **별도 오버레이 뷰**로만(비트맵에 합성 금지 = 캐시 scale별 키 오염 방지). ④ 줌 `cache.clear()` 금지.
2. 순수 로직(`SearchCursor`/`HighlightGeometry`)은 **android 타입 없이** JVM 단위테스트(좌표는 `FloatArray`).
3. 각 명령 "Expected"와 다르면 멈추고 보고(BLOCKED). 추측 금지.
4. **좌표계 가정**: fitz `Page.search` quad/Rect 는 page space에서 **y-down(top-left 원점)**, 렌더도 `Matrix(scale)` 동일계. 따라서 `pixel = pt * (contentWidth/pageWidthPts)`, 동일 스케일로 x·y. **하이라이트가 텍스트 위에 정확히 오는지 Task 6 실기에서 검증**(어긋나면 y축 뒤집기 후보).
5. 환경: 루트 `C:\dev\openPDF`(git-bash). 에뮬 `emulator-5554`, adb `/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb`. 저장공간 부족 시 `pm trim-caches 9999999999`. **adb 영문 입력 = Gboard 한글조합 깨짐** → `ime disable <id>` 후 입력, `ime enable`+`ime set <id>` 복구(슬래시 id라 `MSYS_NO_PATHCONV=1`; id=`settings get secure default_input_method`). 루트 `*.png` gitignore. **브랜치 새로 만들어 작업.**

## 현재 시그니처 (이미 존재 — 바꾸지 말 것)
- `PdfReaderView`: `scrollToPage(index)`, `pageCount`, `zoom`(get), `onPageChanged`, `setDocument(renderer,sizes)`, private `relayout()`(`PageLayout.compute(sizes, fitWidthPx=width, gapPx, zoom)` → `adapterImpl.submitLayout`), private `sizes`/`adapterImpl`/`gapPx`.
- `PageLayout`: `compute(sizes, fitWidthPx, gapPx, zoom)`, `pageTop(i)`, `pageHeight(i)`, `contentWidth`, `totalHeight`, `pageCount`, `visiblePages`.
- `PdfPageAdapter(renderer, cache, pageSizeProvider)`: `submitLayout(layout)`, `PageVH(itemView: FrameLayout, image: ImageView)`, onBind는 `itemView.layoutParams=(contentWidth,pageHeight)` + ImageView FIT_CENTER.
- `PageSize(width,height)`, `aspect`. `SearchHit(page,x0,y0,x1,y1)`(PDF pt). `PageRenderer.searchBlocking(needle): List<SearchHit>`.
- `MainActivity`(Phase 4): `bg`, `renderer`(@Volatile), `reader`, `showSearch()`, `showSearchResults()`(← 제거 대상), `action_search` 분기.

## File Structure (Phase 4.5)
- Create: `pdf/SearchCursor.kt` (+ test `SearchCursorTest.kt`)
- Create: `view/HighlightGeometry.kt` (+ test `HighlightGeometryTest.kt`)
- Create: `view/HighlightOverlayView.kt`
- Modify: `view/PdfPageAdapter.kt` (오버레이 추가 + 하이라이트 전달)
- Modify: `view/PdfReaderView.kt` (`lastLayout` 보관 + `setSearchHighlights`/`scrollToHit`/`clearSearchHighlights`)
- Modify(추가): `res/layout/activity_main.xml` (검색바), `res/values/strings.xml` (문자열)
- Modify: `MainActivity.kt` (검색모드: 다이얼로그→바, prev/next/close)
- Create: `androidTest/.../SearchHighlightSmokeTest.kt`

---

## Task 1: 순수 `SearchCursor` (TDD)

**Files:** Create `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/SearchCursor.kt`; Test `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/SearchCursorTest.kt`

- [ ] **Step 1: 실패 테스트**

```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCursorTest {
    private fun hit(p: Int) = SearchHit(p, 0f, 0f, 1f, 1f)
    private val three = listOf(hit(0), hit(1), hit(2))

    @Test fun positionIsOneBased() {
        assertEquals(1, SearchCursor(three).position)
        assertEquals(3, SearchCursor(three).size)
    }

    @Test fun nextWraps() {
        val c = SearchCursor(three).next().next()      // index 2
        assertEquals(3, c.position)
        assertEquals(1, c.next().position)             // wraps to 0 -> position 1
    }

    @Test fun prevWraps() {
        assertEquals(3, SearchCursor(three).prev().position)  // 0 -> wraps to 2 -> position 3
    }

    @Test fun currentTracksIndex() {
        assertEquals(1, SearchCursor(three).next().current!!.page)
    }

    @Test fun emptyIsSafe() {
        val e = SearchCursor(emptyList())
        assertEquals(0, e.position); assertEquals(0, e.size)
        assertNull(e.current)
        assertEquals(0, e.next().position); assertEquals(0, e.prev().position)
    }
}
```

- [ ] **Step 2: 실패 확인** — `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "*SearchCursorTest"` → FAIL (`SearchCursor` unresolved).

- [ ] **Step 3: 구현**

```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/** Immutable cursor over [hits] with wrapping next/prev. Empty-safe. */
data class SearchCursor(val hits: List<SearchHit>, val index: Int = 0) {
    val size: Int get() = hits.size
    /** 1-based position for display ("3 / 12"); 0 when empty. */
    val position: Int get() = if (hits.isEmpty()) 0 else index + 1
    val current: SearchHit? get() = hits.getOrNull(index)
    fun next(): SearchCursor = if (hits.isEmpty()) this else copy(index = (index + 1) % hits.size)
    fun prev(): SearchCursor = if (hits.isEmpty()) this else copy(index = (index - 1 + hits.size) % hits.size)
}
```

- [ ] **Step 4: 통과 확인** — 같은 명령 → BUILD SUCCESSFUL, 5 tests passed.
- [ ] **Step 5: 커밋** — `git add -A && git commit -m "feat: add pure SearchCursor with wrapping next/prev"`

---

## Task 2: 순수 `HighlightGeometry` (TDD)

> PDF 포인트 rect → 셀 픽셀. **android 타입 없이** `FloatArray[left,top,right,bottom]` 반환(순수 JVM 테스트).

**Files:** Create `app/src/main/java/io/github/june690602_blip/cleanpdf/view/HighlightGeometry.kt`; Test `app/src/test/java/io/github/june690602_blip/cleanpdf/view/HighlightGeometryTest.kt`

- [ ] **Step 1: 실패 테스트**

```kotlin
package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightGeometryTest {
    @Test fun scaleIsContentWidthOverPageWidth() {
        assertEquals(2f, HighlightGeometry.scale(pageWidthPts = 100f, contentWidth = 200f), 1e-4f)
    }

    @Test fun degeneratePageWidthGivesZeroScale() {
        assertEquals(0f, HighlightGeometry.scale(0f, 200f), 1e-4f)
    }

    @Test fun toPixelsScalesAllCorners() {
        val hit = SearchHit(0, x0 = 10f, y0 = 20f, x1 = 30f, y1 = 25f)
        // page 100pt wide rendered 200px wide -> scale 2
        val px = HighlightGeometry.toPixels(hit, pageWidthPts = 100f, contentWidth = 200f)
        assertArrayEquals(floatArrayOf(20f, 40f, 60f, 50f), px, 1e-4f)
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests "*HighlightGeometryTest"` → FAIL.

- [ ] **Step 3: 구현**

```kotlin
package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.SearchHit

/**
 * Pure PDF-point → cell-pixel conversion for search highlights. A page of [pageWidthPts] wide
 * (PDF points) is rendered [contentWidth] px wide; the cell keeps aspect, so x and y share one
 * scale. Returns pixels as [left, top, right, bottom] (no android types → JVM-unit-testable).
 */
object HighlightGeometry {
    fun scale(pageWidthPts: Float, contentWidth: Float): Float =
        if (pageWidthPts <= 0f) 0f else contentWidth / pageWidthPts

    fun toPixels(hit: SearchHit, pageWidthPts: Float, contentWidth: Float): FloatArray {
        val s = scale(pageWidthPts, contentWidth)
        return floatArrayOf(hit.x0 * s, hit.y0 * s, hit.x1 * s, hit.y1 * s)
    }
}
```

- [ ] **Step 4: 통과 확인** — 3 tests passed.
- [ ] **Step 5: 커밋** — `git commit -m "feat: add pure HighlightGeometry (PDF points -> cell pixels)"`

---

## Task 3: `HighlightOverlayView` + `PdfPageAdapter` 하이라이트

> 각 페이지 셀(`FrameLayout`)에 ImageView 위로 오버레이 뷰를 얹는다. adapter가 페이지별 hits + 활성 hit를 받아 onBind에서 셀 픽셀로 변환해 오버레이에 전달. **렌더/캐시/recycle 로직 불변.**

**Files:** Create `view/HighlightOverlayView.kt`; Modify `view/PdfPageAdapter.kt`

- [ ] **Step 1: `HighlightOverlayView.kt` 생성**

```kotlin
package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Transparent overlay that paints translucent rectangles over a page cell for search hits.
 * One rect may be the "active" hit (drawn stronger). Purely presentational; holds no PDF state.
 */
class HighlightOverlayView(context: Context) : View(context) {
    private val fill = Paint().apply { color = Color.argb(70, 255, 230, 0); isAntiAlias = true }
    private val active = Paint().apply { color = Color.argb(150, 255, 145, 0); isAntiAlias = true }
    private var rects: List<RectF> = emptyList()
    private var activeIndex: Int = -1

    /** [rects] are in this view's pixel space (same size as the page cell). */
    fun setHighlights(rects: List<RectF>, activeIndex: Int) {
        this.rects = rects
        this.activeIndex = activeIndex
        invalidate()
    }

    fun clear() {
        if (rects.isEmpty() && activeIndex == -1) return
        rects = emptyList(); activeIndex = -1; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (i in rects.indices) canvas.drawRect(rects[i], if (i == activeIndex) active else fill)
    }
}
```

- [ ] **Step 2: `PdfPageAdapter.kt` 수정** — (a) 셀에 오버레이 추가, (b) 하이라이트 상태 + setter, (c) onBind에서 변환·전달. 아래 4개 편집.

(a) import 추가 (파일 상단 import 블록에):
```kotlin
import android.graphics.RectF
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
```

(b) 클래스 본문 상단(`private var layout: PageLayout? = null` 아래)에 상태+setter 추가:
```kotlin
    /** Hits grouped by page (0-based), and the currently active hit (or null). */
    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var activeHit: SearchHit? = null

    fun setHighlights(hits: List<SearchHit>, active: SearchHit?) {
        hitsByPage = hits.groupBy { it.page }
        activeHit = active
        notifyDataSetChanged()
    }
```

(c) `onCreateViewHolder`의 `frame.addView(iv)` 다음에 오버레이를 ImageView 위에 추가하고 VH에 전달:
```kotlin
        val overlay = HighlightOverlayView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(iv)
        frame.addView(overlay)   // drawn above the page bitmap
        return PageVH(frame, iv, overlay)
```
(기존 `frame.addView(iv); return PageVH(frame, iv)` 두 줄을 위로 교체.)

(d) `onBindViewHolder` 끝(렌더 submit 뒤)에 하이라이트 적용 추가, 그리고 `PageVH`에 overlay 필드 추가:
```kotlin
        // search highlights for this page (cell pixels via HighlightGeometry)
        val pageHits = hitsByPage[position].orEmpty()
        if (pageHits.isEmpty()) {
            holder.overlay.clear()
        } else {
            val cw = l.contentWidth
            val pw = pageSizeProvider(position).width
            val rects = pageHits.map {
                val q = HighlightGeometry.toPixels(it, pw, cw)
                RectF(q[0], q[1], q[2], q[3])
            }
            val ai = activeHit?.let { a -> if (a.page == position) pageHits.indexOf(a) else -1 } ?: -1
            holder.overlay.setHighlights(rects, ai)
        }
```
그리고 `PageVH` 선언을 overlay 포함으로 교체:
```kotlin
    class PageVH(itemView: FrameLayout, val image: ImageView, val overlay: HighlightOverlayView) :
        RecyclerView.ViewHolder(itemView) {
        var pending: Future<*>? = null
    }
```

- [ ] **Step 3: 컴파일 확인** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4: 커밋** — `git commit -m "feat: per-page highlight overlay in PdfPageAdapter"`

---

## Task 4: `PdfReaderView` — `setSearchHighlights` / `scrollToHit` / `clearSearchHighlights`

> `relayout()`이 만든 `PageLayout`을 보관(`lastLayout`)해 scrollToHit의 픽셀 계산에 사용. 하이라이트는 adapter로 위임.

**Files:** Modify `view/PdfReaderView.kt`

- [ ] **Step 1: `lastLayout` 보관** — `private var adapterImpl: PdfPageAdapter? = null` 아래에 필드 추가:
```kotlin
    private var lastLayout: PageLayout? = null
```
그리고 `relayout()`의 `val layout = PageLayout.compute(...)` 다음 줄에 `lastLayout = layout` 추가:
```kotlin
        val layout = PageLayout.compute(sizes, fitWidthPx = width, gapPx = gapPx, zoom = zoom)
        lastLayout = layout
        adapterImpl?.submitLayout(layout)
```

- [ ] **Step 2: import + 검색 API 추가** — import 블록에 `import io.github.june690602_blip.cleanpdf.pdf.SearchHit` 추가. `scrollToPage` 메서드 다음에 추가:
```kotlin
    /** Show [hits] as highlights, with [active] drawn stronger. Empty list clears them. */
    fun setSearchHighlights(hits: List<SearchHit>, active: SearchHit?) {
        adapterImpl?.setHighlights(hits, active)
    }

    fun clearSearchHighlights() {
        adapterImpl?.setHighlights(emptyList(), null)
    }

    /**
     * Scroll so [hit] is comfortably in view (near the top quarter of the viewport).
     * Uses the current layout's scale to convert the hit's PDF-point top to cell pixels.
     */
    fun scrollToHit(hit: SearchHit) {
        if (sizes.isEmpty()) return
        val page = hit.page.coerceIn(0, sizes.size - 1)
        val cw = lastLayout?.contentWidth ?: return
        val s = HighlightGeometry.scale(sizes[page].width, cw)
        val hitTopPx = hit.y0 * s
        val offset = (height * 0.25f - hitTopPx).toInt()
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, offset)
        onPageChanged?.invoke(page, sizes.size)
    }
```

- [ ] **Step 3: 컴파일 확인** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4: 커밋** — `git commit -m "feat: PdfReaderView search highlights + scrollToHit"`

---

## Task 5: 검색바 레이아웃 + `MainActivity` 검색모드 (다이얼로그 → 바)

**Files:** Modify `res/values/strings.xml`, `res/layout/activity_main.xml`, `MainActivity.kt`

- [ ] **Step 1: 문자열 추가** — `strings.xml` 의 `</resources>` 앞에:
```xml
    <string name="search_prev">이전</string>
    <string name="search_next">다음</string>
    <string name="search_close">검색 닫기</string>
    <string name="search_position">%1$d / %2$d</string>
```

- [ ] **Step 2: 검색바 추가** — `activity_main.xml` 의 `error_view` `</FrameLayout>` 바로 앞(즉 reader/error_view 다음, FrameLayout 자식)에 삽입:
```xml
        <LinearLayout
            android:id="@+id/search_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:orientation="horizontal"
            android:background="?attr/colorPrimary"
            android:gravity="center_vertical"
            android:padding="4dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/search_position"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:paddingStart="12dp"
                android:textColor="#FFFFFFFF"
                android:textSize="14sp" />

            <Button
                android:id="@+id/search_prev_btn"
                style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/search_prev"
                android:textColor="#FFFFFFFF" />

            <Button
                android:id="@+id/search_next_btn"
                style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/search_next"
                android:textColor="#FFFFFFFF" />

            <Button
                android:id="@+id/search_close_btn"
                style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/search_close"
                android:textColor="#FFFFFFFF" />
        </LinearLayout>
```

- [ ] **Step 3: `MainActivity.kt` 수정** — (a) import `SearchCursor`/`SearchHit`(SearchHit는 이미 있음), (b) 필드 추가, (c) `onCreate`에서 검색바 버튼 바인딩, (d) `showSearch`를 검색모드 진입으로 변경 + `showSearchResults` 제거, (e) 검색모드 헬퍼 추가.

(a) import 블록에 추가:
```kotlin
import io.github.june690602_blip.cleanpdf.pdf.SearchCursor
import android.widget.Button
```

(b) 필드 추가 (`private var currentSizes` 아래):
```kotlin
    private var cursor: SearchCursor? = null
    private lateinit var searchBar: android.view.View
    private lateinit var searchPosition: android.widget.TextView
```

(c) `onCreate`의 `errorView = findViewById(...)` 다음에 검색바 바인딩:
```kotlin
        searchBar = findViewById(R.id.search_bar)
        searchPosition = findViewById(R.id.search_position)
        findViewById<Button>(R.id.search_prev_btn).setOnClickListener { stepSearch(-1) }
        findViewById<Button>(R.id.search_next_btn).setOnClickListener { stepSearch(+1) }
        findViewById<Button>(R.id.search_close_btn).setOnClickListener { closeSearch() }
```

(d) `showSearch()`의 검색 실행부를 결과 다이얼로그 대신 검색모드 진입으로. 기존 `showSearch` 의 positive 버튼 람다 안 `bg.execute { val hits = r.searchBlocking(q); runOnUiThread { showSearchResults(hits) } }` 를 다음으로 교체:
```kotlin
                bg.execute {
                    val hits = r.searchBlocking(q)
                    runOnUiThread { openSearch(hits) }
                }
```
그리고 `showSearchResults(...)` 메서드 **전체 삭제**.

(e) 검색모드 헬퍼 추가 (`showSearch()` 다음, `onDestroy` 앞):
```kotlin
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
```

- [ ] **Step 4: 빌드 + 실기 검증** (Task 6에서 자동 계측도 하지만 여기서 1차 시각 확인)
```bash
cd /c/dev/openPDF && ./gradlew :app:assembleDebug && ./gradlew :app:installDebug
ADB=/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb
$ADB -s emulator-5554 shell pm trim-caches 9999999999
$ADB -s emulator-5554 shell am force-stop io.github.june690602_blip.cleanpdf
$ADB -s emulator-5554 shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity
sleep 5
# IME 끄기(영문 입력 위해) — id 확인 후 disable
IME=$($ADB -s emulator-5554 shell settings get secure default_input_method | tr -d '\r')
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 shell ime disable "$IME"
# 오버플로 -> 검색 -> "Page" 입력 -> 검색 (좌표는 스크린샷 보고)
$ADB -s emulator-5554 exec-out screencap -p > p45_search_bar.png
```
Read `p45_search_bar.png`: 검색 후 (1) 페이지에 노란 하이라이트가 "Page" 글자 위에 보이고, (2) 하단 바에 "1 / 3" + 이전/다음/검색 닫기, (3) 첫 히트로 스크롤됐는지. 다음 버튼 탭 → "2 / 3" + 다음 히트로 이동·하이라이트. 닫기 → 바 사라지고 하이라이트 제거. **하이라이트가 글자와 어긋나면**(좌표계) 규칙 4의 y축 뒤집기를 적용하고 재빌드(멈추고 보고). 끝나면 `ime enable`+`ime set "$IME"` 복구.

- [ ] **Step 5: 커밋** — `git commit -m "feat: search bar (prev/next/count/close) replacing results dialog; highlight + scrollToHit"`

---

## Task 6: 계측 + 최종 검증

**Files:** Create `androidTest/.../SearchHighlightSmokeTest.kt`

- [ ] **Step 1: 계측 테스트** (순수 로직 + geometry를 디바이스에서도 재확인; 오버레이 렌더는 실기 스크린샷으로)

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.pdf.SearchCursor
import io.github.june690602_blip.cleanpdf.pdf.SearchHit
import io.github.june690602_blip.cleanpdf.view.HighlightGeometry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchHighlightSmokeTest {
    @Test fun cursorWrapsAndGeometryScales() {
        val hits = listOf(SearchHit(0, 10f, 20f, 30f, 25f), SearchHit(1, 0f, 0f, 5f, 5f))
        var c = SearchCursor(hits)
        assertEquals(1, c.position)
        c = c.prev()                              // wraps to last
        assertEquals(2, c.position)
        assertEquals(1, c.current!!.page)

        val px = HighlightGeometry.toPixels(hits[0], pageWidthPts = 100f, contentWidth = 200f)
        assertEquals(20f, px[0], 1e-4f); assertEquals(40f, px[1], 1e-4f)
    }
}
```

- [ ] **Step 2: 계측 실행** — `pm trim-caches` 후 `./gradlew :app:connectedDebugAndroidTest` → 기존 5 + SearchHighlight 1 = 6 PASS.
- [ ] **Step 3: 전체 단위 + 빌드** — `./gradlew :app:testDebugUnitTest`(35 + SearchCursor 5 + HighlightGeometry 3 = 43) + `./gradlew :app:assembleDebug`.
- [ ] **Step 4: 수동 체크리스트** (커밋 본문):
  - [ ] 검색 → 하이라이트가 글자 위에 정렬, 첫 히트로 스크롤, 바 "1 / N"
  - [ ] 다음/이전 → 활성 하이라이트 이동 + 스크롤 + 카운트 갱신(wrapping)
  - [ ] 줌 인/아웃 후에도 하이라이트가 글자에 따라붙음(relayout 재계산)
  - [ ] 닫기 → 하이라이트 제거 + 바 숨김
- [ ] **Step 5: 커밋** — `git commit -m "test: add SearchHighlight instrumented smoke; verify Phase 4.5"`

**✅ Phase 4.5 done when:** 검색 → 하이라이트 + 바 순차 이동 + 자동 스크롤 동작; 단위 43 + 계측 6 통과; 줌 후에도 정렬 유지; 크래시 없음.

---

## Self-Review

**Spec coverage (§5.3 나머지):** 찾기 바(prev/next/count) + 하이라이트 + 점프 → Task 3,4,5 ✓. Phase 4의 다이얼로그는 검색바로 대체.

**Placeholder scan:** 전체 코드/정확한 추가 줄. 순수 `SearchCursor`(5) + `HighlightGeometry`(3) 단위테스트. 좌표는 `FloatArray`(android 타입 회피).

**Type consistency:** `SearchCursor(hits,index)`/`.next/.prev/.current/.position/.size`, `HighlightGeometry.scale/.toPixels(FloatArray)`, `HighlightOverlayView.setHighlights(List<RectF>,Int)/.clear`, `PdfPageAdapter.setHighlights(List<SearchHit>,SearchHit?)`+`PageVH(frame,image,overlay)`, `PdfReaderView.setSearchHighlights/scrollToHit/clearSearchHighlights`+`lastLayout`, `MainActivity.openSearch/stepSearch/applyCursor/closeSearch`+`cursor` — 일관.

**불변조건 준수:** 하이라이트는 **오버레이 뷰**(비트맵/캐시 미변경, recycle 0). fitz 접근 추가 없음(검색은 Phase 4 `searchBlocking` 재사용). 줌 → `relayout`이 `lastLayout`/오버레이 좌표 재계산.

**리스크:** ① fitz quad y축(top-left 가정) — 어긋나면 Task 5 Step 4에서 y 뒤집기(`pageHeightPts - y`)로 보정, 실기 검증. ② `notifyDataSetChanged`로 하이라이트 갱신(간단·안전; 성능 이슈 시 부분 갱신은 후속). ③ 검색바와 시스템 IME 겹침 — 검색은 다이얼로그에서 입력 후 바가 뜨므로 무관.
