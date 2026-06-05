# CleanPDF Viewer — Phase 3.5 (썸네일 점프) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (권장) 또는 superpowers:executing-plans. 단계는 체크박스(`- [ ]`)로 추적.

**Goal:** PDF 페이지 썸네일 그리드를 다이얼로그로 띄우고, 썸네일을 탭하면 그 페이지로 점프한다 (스펙 §5.6 "썸네일 점프"; Phase 3에서 분리해 둔 마지막 "탐색" 조각).

**Architecture:** `ThumbnailAdapter`(RecyclerView 그리드)가 메인 뷰어의 **렌더러를 공유**(단일 렌더 스레드)하고 **자체 작은 BitmapCache**로 저해상도 썸네일을 비동기 렌더한다 — `PdfPageAdapter` 패턴을 그대로 복제(제출→메인 재포스트→캐시, 재활용 시 취소, **recycle 금지**). UI는 풀스크린 오버레이 대신 **AlertDialog**(닫기/뒤로가기를 다이얼로그가 처리)라 레이아웃 변경·뒤로가기 처리가 없다. 셀 탭 → `PdfReaderView.scrollToPage` + 다이얼로그 dismiss.

**Tech Stack:** Kotlin + Android Views, MuPDF fitz 1.27.1, AndroidX RecyclerView(`GridLayoutManager`)/AppCompat. 새 순수 로직 없음(이건 UI/렌더 태스크) → 단위테스트 추가 없음, 컴파일+실기 검증.

**Spec:** `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md` (§5.6, §4 `ThumbnailStrip`)
**필독:** `docs/superpowers/handoff/2026-06-05-cleanpdf-phase1-handoff.md` §2 불변조건, `CLAUDE.md`. **선행:** Phase 3(목차/페이지점프)가 `main` 에 병합돼 있어야 함(`PdfReaderView.scrollToPage` 사용).

---

## ⚠️ 실행자(특히 저용량 모델) 필독 — 작업 규칙
1. **수정 파일은 아래 "파일 전체 내용"을 그대로 덮어쓴다.** 부분 병합 금지. 메뉴/문자열만 "추가"(닫는 태그 앞 삽입).
2. **불변조건(절대 변경 금지):** 모든 `PdfDocument`/fitz 접근은 `PageRenderer` 의 단일 스레드(=`renderer.submit` 가 그렇게 함). **비트맵 `recycle()` 추가 금지**(`BitmapCache` 는 이미 recycle 안 함 — 그대로 둠). `RenderScale`·`PdfPageAdapter`·`PdfReaderView` 의 줌/캐시 로직은 건드리지 않는다(`PdfReaderView` 는 이미 있는 `scrollToPage`/`pageCount` 만 호출).
3. **테스트는 순수 JVM 만**(이 Phase엔 새 순수 로직이 없어 단위테스트 추가 없음 — 기존 33개가 깨지지 않는지만 확인). `org.json`/Robolectric/Android-타입 테스트 금지.
4. 각 명령의 "Expected" 와 다르면 멈추고 보고(BLOCKED). 추측 금지.
5. 환경: 루트 `C:\dev\openPDF`(git-bash). 에뮬 `emulator-5554`, adb `/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb`. 저장공간 부족 시 `pm trim-caches 9999999999`. 루트 `*.png` 는 gitignore. LF→CRLF 경고 무해. **브랜치 새로 만들어 작업(main 직접 커밋 금지).**

## 현재 시그니처(참고 — 이미 존재, 바꾸지 말 것)
- `PageRenderer.submit(page, scale, onReady): Future<*>` (onReady 는 렌더 스레드 — 콜백에서 `View.post` 로 메인 재포스트 필요).
- `BitmapCache(maxBytes: Int)`: `get(PageKey): Bitmap?`, `put(PageKey, Bitmap)`. `PageKey(page, scaleMilli)`. companion `BitmapCache.scaleMilli(scale: Float): Int`. **(eviction 시 recycle 안 함 — 그대로 사용)**
- `RenderScale.forPage(targetWidthPx: Float, page: PageSize): Float` (비트맵 ≤32MB 캡).
- `PdfReaderView.scrollToPage(index: Int)`, `pageCount: Int` (Phase 3에서 추가됨).
- `PageSize(width: Float, height: Float)`.
- `PdfPageAdapter`(복제 대상): 제출→`itemView.post{ cache.put; if(binding==pos) setImageBitmap }`, `onViewRecycled` 에서 `pending?.cancel(true)` + `setImageBitmap(null)`.

## File Structure (Phase 3.5)
- Create: `app/src/main/java/.../view/ThumbnailAdapter.kt` — 썸네일 그리드 어댑터
- Modify(추가): `app/src/main/res/menu/reader_menu.xml` — "썸네일" 항목 1개
- Modify(추가): `app/src/main/res/values/strings.xml` — 문자열 1개
- Modify(전체 교체): `app/src/main/java/.../MainActivity.kt` — `currentSizes` 보관 + `showThumbnails()` 다이얼로그

---

## Task 1: `ThumbnailAdapter` (새 파일)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/ThumbnailAdapter.kt`

- [ ] **Step 1: 아래 전체 내용으로 새 파일 생성**

`app/src/main/java/io/github/june690602_blip/cleanpdf/view/ThumbnailAdapter.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.cache.PageKey
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize
import io.github.june690602_blip.cleanpdf.pdf.RenderScale
import java.util.concurrent.Future

/**
 * Grid of low-resolution page thumbnails. Shares [renderer] (single render thread) and uses its own
 * small [cache]. Tapping a cell calls [onPick] with the 0-based page index. Mirrors PdfPageAdapter's
 * async-render pattern and does NOT recycle bitmaps (BitmapCache already drops references for the GC).
 */
class ThumbnailAdapter(
    private val renderer: PageRenderer,
    private val sizes: List<PageSize>,
    private val cache: BitmapCache,
    private val cellWidthPx: Int,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ThumbnailAdapter.ThumbVH>() {

    override fun getItemCount(): Int = sizes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbVH {
        val iv = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                (cellWidthPx * 1.3f).toInt(),
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(6, 6, 6, 6)
        }
        return ThumbVH(iv)
    }

    override fun onBindViewHolder(holder: ThumbVH, position: Int) {
        holder.pending?.cancel(true)
        holder.image.setImageBitmap(null)
        holder.image.setOnClickListener { onPick(position) }

        val scale = RenderScale.forPage(cellWidthPx.toFloat(), sizes[position])
        val key = PageKey(position, BitmapCache.scaleMilli(scale))
        val cached = cache.get(key)
        if (cached != null) { holder.image.setImageBitmap(cached); return }

        holder.pending = renderer.submit(position, scale) { bmp ->
            holder.image.post {
                cache.put(key, bmp)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: ThumbVH) {
        holder.pending?.cancel(true); holder.pending = null
        holder.image.setImageBitmap(null)
    }

    class ThumbVH(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var pending: Future<*>? = null
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "feat: add ThumbnailAdapter (shared renderer, own cache, no recycle)"
```

---

## Task 2: 메뉴 "썸네일" + 문자열 + `MainActivity` 다이얼로그 (추가/전체 교체) + 실기 검증

**Files:**
- Modify(추가): `app/src/main/res/values/strings.xml`
- Modify(추가): `app/src/main/res/menu/reader_menu.xml`
- Modify(전체 교체): `app/src/main/java/.../MainActivity.kt`

- [ ] **Step 1: 문자열 추가** — `res/values/strings.xml` 의 `</resources>` 바로 위에 추가(같은 name 있으면 건너뜀):

```xml
    <string name="thumbnails">썸네일</string>
```

- [ ] **Step 2: 메뉴 항목 추가** — `res/menu/reader_menu.xml` 의 `</menu>` 바로 위에 추가:

```xml
    <item
        android:id="@+id/action_thumbnails"
        android:title="@string/thumbnails"
        android:showAsAction="never" />
```

- [ ] **Step 3: `MainActivity.kt` 를 아래 전체 내용으로 교체** (추가: `import PageSize`, `currentSizes` 필드, `showDocument` 에서 `currentSizes = sizes`, `action_thumbnails` 분기, `showThumbnails()`)

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
$ADB -s emulator-5554 exec-out screencap -p > task2_reader.png
# 오버플로(⋮) 열기 -> "썸네일" 탭. (좌표는 스크린샷 보고 결정: ⋮ 는 우상단, 메뉴 항목은 드롭다운)
$ADB -s emulator-5554 shell input tap 1010 130   # ⋮ (좌표 확인 후 조정)
sleep 1
$ADB -s emulator-5554 exec-out screencap -p > task2_menu.png   # "썸네일" 위치 확인용
# task2_menu.png 에서 "썸네일" 좌표를 읽어 탭:
$ADB -s emulator-5554 shell input tap 700 <y_of_썸네일>
sleep 2
$ADB -s emulator-5554 exec-out screencap -p > task2_thumbs.png
```
Read `task2_thumbs.png`: 썸네일 그리드 다이얼로그에 페이지 미리보기들(샘플 3페이지면 작은 페이지 3개)이 보이는지 확인. 그 중 하나(예: 3번째)를 탭한 뒤 스크린샷:
```bash
$ADB -s emulator-5554 shell input tap <thumb3_x> <thumb3_y>
sleep 1
$ADB -s emulator-5554 exec-out screencap -p > task2_jumped.png
$ADB -s emulator-5554 logcat -d | grep -iE "FATAL|AndroidRuntime|recycled bitmap|RejectedExecution" | head || echo "no crash"
```
Read `task2_jumped.png`: 다이얼로그가 닫히고 리더가 탭한 페이지(예: Page 3)로 점프했는지 확인. 크래시 로그 비어 있어야 함.
Expected: 썸네일 그리드 렌더 + 탭 시 해당 페이지 점프, 크래시 없음. (좌표 탭이 빗나가면 스크린샷 보고 좌표 조정 후 재시도. 메뉴/다이얼로그 좌표를 끝내 못 맞추면, 그 사실을 정직히 보고 — 단 빌드/설치는 성공해야 함.)

- [ ] **Step 5: 커밋** (스크린샷 스테이징 금지)

```bash
cd /c/dev/openPDF && git status --short   # task2_*.png 이 안 보여야 함(루트 *.png gitignore)
cd /c/dev/openPDF && git add -A && git commit -m "feat: thumbnail grid dialog with tap-to-jump"
```

---

## Task 3: 최종 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 전체 단위 스위트 + 빌드** (기존 33개가 깨지지 않았는지)

```bash
cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest   # 33개 그대로 PASS
cd /c/dev/openPDF && ./gradlew :app:assembleDebug
```
Expected: 단위 33 PASS(0 실패), BUILD SUCCESSFUL. (이 Phase는 순수 로직 추가가 없어 단위 수 불변.)

- [ ] **Step 2: 회귀 계측 (기존 스모크가 여전히 통과)**

```bash
cd /c/dev/openPDF && /c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb -s emulator-5554 shell pm trim-caches 9999999999
cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest --tests "*NavigationSmokeTest" --tests "*ScrollZoomSmokeTest"
```
Expected: PASS (썸네일은 다이얼로그라 자동 계측 생략 — Task 2 실기 스크린샷 + 아래 수동 체크로 검증).

- [ ] **Step 3: 수동 검증 체크리스트** (커밋 본문에 기록)
- [ ] 멀티페이지 PDF에서 "썸네일" → 그리드에 페이지 미리보기들이 렌더됨(스크롤하면 추가 렌더)
- [ ] 썸네일 탭 → 다이얼로그 닫히고 그 페이지로 점프
- [ ] 큰 문서(50p+)에서 썸네일 그리드 스크롤 시 크래시/OOM 없음(공유 렌더 스레드, 작은 비트맵)
- [ ] 다른 PDF 연 뒤 "썸네일" → 새 문서 썸네일이 뜸(옛 렌더러 참조 안 함)

- [ ] **Step 4: 커밋**

```bash
cd /c/dev/openPDF && git add -A && git commit -m "test: verify Phase 3.5 thumbnails (regression suite + manual)" --allow-empty
```

**✅ Phase 3.5 done when:** "썸네일" → 그리드 렌더 → 탭 점프가 동작하고; 기존 단위 33 + 회귀 계측 통과; 크래시/OOM 없음. (이로써 스펙 Phase 3 "탐색"의 목차+페이지점프+번호점프+썸네일 4개가 모두 완료.)

---

## Self-Review

**Spec coverage:** 스펙 §5.6 "썸네일 점프" → Task 1,2 ✓ (그리드 다이얼로그 + 탭 점프, `scrollToPage` 재사용). 이로써 스펙 Phase 3 "탐색" 완결. **다음:** Phase 4(검색, §5.3) — `Page.search` 기반.

**Placeholder scan:** `...` 없음(전체 파일/추가 줄 제공). Task 2 Step 4 의 탭 좌표만 스크린샷 의존(저용량 모델도 스크린샷 읽고 조정하도록 명시; 안 맞으면 빌드/설치 성공 + 정직 보고로 처리). 새 순수 로직 없음 → 단위테스트 추가 없음(컨벤션상 허용 — UI/렌더 태스크).

**Type consistency:** `ThumbnailAdapter(renderer, sizes, cache, cellWidthPx, onPick)`, `RenderScale.forPage`, `BitmapCache(maxBytes)/get/put/scaleMilli`, `PageKey(page, scaleMilli)`, `PdfReaderView.scrollToPage/pageCount`, `MainActivity.currentSizes/showThumbnails` — 일관. `MainActivity` 는 Phase 3 버전 + (currentSizes 필드, showDocument 의 `currentSizes = sizes`, action_thumbnails 분기, showThumbnails, import GridLayoutManager/RecyclerView/BitmapCache/PageSize/ThumbnailAdapter) 만 추가.

**불변조건 준수:** 썸네일도 `renderer.submit`(단일 렌더 스레드)으로만 렌더. `holder.image.post` 로 메인 재포스트 후 캐시/표시. 별도 `BitmapCache`(recycle 안 함) — 화면에 붙은 썸네일이 recycle 될 일 없음. 옛 문서로 전환 시 `showThumbnails` 가 매번 **현재 renderer/currentSizes** 로 새로 구성 → 종료된 executor 에 submit 안 함.

**저용량 모델 위험요소(대응):**
1. 풀스크린 오버레이+뒤로가기 처리 복잡 → **AlertDialog** 로 회피(레이아웃 변경/백키 처리 없음).
2. 부분 병합 실패 → MainActivity 전체 교체, 메뉴/문자열은 정확한 추가 줄.
3. 렌더 동시성/recycle 크래시 → `PdfPageAdapter` 패턴 그대로 복제 + 기존 no-recycle `BitmapCache` 재사용.
4. 다이얼로그/메뉴 좌표 탭 불안정 → 스크린샷 기반 조정 + 실패 시 정직 보고(빌드/설치는 필수 성공).
