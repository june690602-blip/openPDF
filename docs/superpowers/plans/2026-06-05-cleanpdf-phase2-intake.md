# CleanPDF Viewer — Phase 2 (파일 받기 + 에러 처리 + 최근 파일) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 카톡 등 외부앱에서 받은 PDF를 "열기(VIEW)/공유(SEND)"로 바로 열람하고, 손상/비-PDF/암호 PDF를 친절히 처리하며, 최근 연 파일을 재열람할 수 있게 한다.

**Architecture:** 기존 Phase 1 뷰어 위에 (1) 인텐트 인입(`Intents.incomingUri` 순수 셀렉터 + Manifest 필터), (2) `isLikelyPdf` 게이트(이미 구현·테스트됨)로 비-PDF 차단, (3) `PdfOpenResult` 기반 에러/암호 화면, (4) `RecentFilesStore`(영속, 불변)를 얹는다. content:// 는 즉시 캐시 복사(`PdfSource.copyToCache`, 이미 있음)해 핸들 만료를 회피한다.

**Tech Stack:** Kotlin + Android Views, MuPDF fitz 1.27.1, AndroidX `core` (`IntentCompat`), SharedPreferences, JUnit4(+inlined Android 상수) 단위 / AndroidX Test 계측.

**Spec:** `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md` (§5.2 파일 받기, §6 에러 처리, §5.8 최근 파일)
**필독:** `docs/superpowers/handoff/2026-06-05-cleanpdf-phase1-handoff.md` §2 **아키텍처 불변조건** — 특히 단일 렌더 스레드 / `openFile` shutdown 순서 / 캐시 recycle 금지. Phase 2 작업이 이걸 깨면 안 된다.

> **참고:** 자매앱 CleanCAD(`C:\dev\opendwg`)가 동일한 인텐트 인입을 이미 구현(Phase 11). MIME 목록·octet-stream 우회·SEND 우회 패턴을 그 CLAUDE.md에서 교차 확인 가능.

---

## 기존 재사용 자산 (새로 만들지 말 것)
- `io/PdfSource.kt` — `copyToCache(context, uri): File` (content:// → 캐시 파일). 이 Phase에서 `looksLikePdf` 추가.
- `pdf/PdfValidation.kt` — `isLikelyPdf(name: String?, head: ByteArray): Boolean` (순수, 단위테스트 5개 완료).
- `pdf/PdfOpenResult.kt` — `sealed interface PdfOpenResult { Success(document); NeedsPassword; Error(reason) }` (모델만 존재, 미연결).
- `pdf/PdfDocument.kt` — `open(path)`, `needsPassword()`, `authenticate(password)`, `pageCount`, `pageSize`, `renderPage`, `close`.
- `MainActivity.kt` — `openFile(File)`(공용 열기), `loadFromUri(Uri)`(피커 경로), `bg` 단일스레드 executor, `@Volatile renderer`.

## File Structure (Phase 2)
- Create: `app/src/main/java/.../io/Intents.kt` — 순수 인텐트 셀렉터
- Create: `app/src/test/java/.../io/IntentsTest.kt`
- Modify: `app/src/main/AndroidManifest.xml` — VIEW/SEND 인텐트 필터
- Modify: `app/src/main/java/.../io/PdfSource.kt` — `looksLikePdf(context, uri)` 추가
- Modify: `app/src/main/java/.../pdf/PdfDocument.kt` — `openResult(path): PdfOpenResult`
- Create: `app/src/test/java/.../pdf/PdfDocumentResultTest.kt` (Robolectric 불필요한 부분만; 실제 open은 계측)
- Modify: `app/src/main/res/layout/activity_main.xml` — 에러 오버레이 TextView
- Modify: `app/src/main/res/values/strings.xml` — 에러/암호/최근 문자열
- Modify: `app/src/main/java/.../MainActivity.kt` — 인텐트 인입 + 에러/암호 화면 + 최근 연동
- Create: `app/src/main/java/.../store/RecentFilesStore.kt` — 영속 최근 목록(불변)
- Create: `app/src/test/java/.../store/RecentFilesLogicTest.kt` — 순수 add/dedup/cap 로직
- Modify: `app/src/main/res/menu/reader_menu.xml` — "최근 파일" 항목
- Create: `app/src/androidTest/java/.../IntentIntakeSmokeTest.kt`

---

## Task 1: `Intents.incomingUri` — VIEW/SEND 셀렉터 (순수, TDD)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/io/Intents.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/io/IntentsTest.kt`

> `android.content.Intent.ACTION_VIEW`/`ACTION_SEND` 는 `public static final String` 컴파일타임 상수라 JVM 단위테스트에서 인라인되어 동작한다(Robolectric 불필요). 제네릭 `<T>` 로 만들어 `Uri` 인스턴스 없이 String 센티넬로 순수 테스트한다.

- [ ] **Step 1: 실패 테스트 작성**

`app/src/test/java/io/github/june690602_blip/cleanpdf/io/IntentsTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.io

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentsTest {
    @Test fun viewReturnsData() {
        assertEquals("DATA", Intents.incomingUri(Intent.ACTION_VIEW, "DATA", "STREAM"))
    }
    @Test fun sendReturnsStream() {
        assertEquals("STREAM", Intents.incomingUri(Intent.ACTION_SEND, null, "STREAM"))
    }
    @Test fun launcherReturnsNull() {
        assertNull(Intents.incomingUri(Intent.ACTION_MAIN, "DATA", "STREAM"))
    }
    @Test fun nullActionReturnsNull() {
        assertNull(Intents.incomingUri<String>(null, "DATA", "STREAM"))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "*IntentsTest"`
Expected: FAIL — `Intents` unresolved.

- [ ] **Step 3: 구현**

`app/src/main/java/io/github/june690602_blip/cleanpdf/io/Intents.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.io

import android.content.Intent

/** Pure intent helpers — operate on already-extracted pieces (no Android Intent instance needed). */
object Intents {
    /** The incoming document for a VIEW (→ [viewData]) or SEND (→ [sendStream]) intent; else null. */
    fun <T> incomingUri(action: String?, viewData: T?, sendStream: T?): T? = when (action) {
        Intent.ACTION_VIEW -> viewData
        Intent.ACTION_SEND -> sendStream
        else -> null
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "*IntentsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add -A && git commit -m "feat: add pure Intents.incomingUri VIEW/SEND selector with unit tests"
```

---

## Task 2: AndroidManifest VIEW + SEND 인텐트 필터 (설정)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `.MainActivity` 의 `<activity>` 안(기존 LAUNCHER 필터 아래)에 두 필터 추가**

```xml
            <!-- 외부앱 "열기" (카톡 등). octet-stream 은 폭넓게 받고 isLikelyPdf 로 파싱 전 차단. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="application/pdf" />
                <data android:mimeType="application/x-pdf" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
            <!-- 시스템 공유시트 "공유" (안드 11+ 패키지 가시성으로 VIEW가 안 닿는 기기 우회). -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/pdf" />
                <data android:mimeType="application/x-pdf" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
```

- [ ] **Step 2: 빌드 + 인텐트 해석 검증**

```bash
./gradlew :app:installDebug
adb -s emulator-5554 shell cmd package query-activities --brief -a android.intent.action.VIEW -d content://x -t application/pdf | grep -i cleancad || echo "check"
adb -s emulator-5554 shell cmd package query-activities --brief -a android.intent.action.SEND -t application/pdf | grep -i cleanpdf
```
Expected: 두 쿼리 모두 `io.github.june690602_blip.cleanpdf/.MainActivity` 로 해석. (저장공간 부족 시 `pm trim-caches 9999999999`.)

- [ ] **Step 3: 커밋**

```bash
git add -A && git commit -m "feat: add VIEW/SEND intent filters for PDF MIME types"
```

---

## Task 3: 에러/암호 처리 토대 — `PdfOpenResult` 연결 + 에러 화면 (B-1)

**Files:**
- Modify: `app/src/main/java/.../pdf/PdfDocument.kt` (결과형 열기)
- Modify: `app/src/main/res/layout/activity_main.xml` (에러 오버레이)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/.../MainActivity.kt` (에러/암호 화면 + openFile 재구성)

> 불변조건: `openFile` 의 "새 렌더러 완성 → setDocument → 옛 렌더러 shutdown" 순서를 유지한다(핸드오프 §2-8). 아래 `showDocument` 가 그 순서를 보존한다.

- [ ] **Step 1: `PdfDocument.openResult` 추가** (열기/암호 판정을 결과형으로)

`pdf/PdfDocument.kt` 의 `companion object` 안에 추가:
```kotlin
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
```

- [ ] **Step 2: `PdfOpenResult` 에 `NeedsPassword` 가 문서를 들고 있도록 변경**

`pdf/PdfOpenResult.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/** Outcome of attempting to open a PDF. Immutable. */
sealed interface PdfOpenResult {
    data class Success(val document: PdfDocument) : PdfOpenResult
    data class NeedsPassword(val document: PdfDocument) : PdfOpenResult
    data class Error(val reason: String) : PdfOpenResult
}
```

- [ ] **Step 3: 에러 오버레이 + 문자열**

`res/layout/activity_main.xml` — 리더를 `FrameLayout` 으로 감싸 에러 TextView 를 오버레이:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        android:theme="@style/ThemeOverlay.Material3.Dark.ActionBar" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <io.github.june690602_blip.cleanpdf.view.PdfReaderView
            android:id="@+id/reader"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@color/reader_bg" />

        <TextView
            android:id="@+id/error_view"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:padding="24dp"
            android:textColor="#FFFFFFFF"
            android:textSize="16sp"
            android:visibility="gone" />
    </FrameLayout>
</LinearLayout>
```

`res/values/strings.xml` 에 추가:
```xml
    <string name="error_not_pdf">PDF 파일이 아닙니다</string>
    <string name="password_title">암호 입력</string>
    <string name="password_hint">PDF 암호</string>
    <string name="password_wrong">암호가 올바르지 않습니다</string>
    <string name="ok">확인</string>
    <string name="cancel">취소</string>
```

- [ ] **Step 4: `MainActivity` 재구성 — openFile 분리 + 에러/암호 화면**

`MainActivity` 의 `reader` 옆에 `error_view` 필드를 잡고, `openFile` 을 결과형으로 재작성. 아래로 교체/추가:
```kotlin
    private lateinit var errorView: android.widget.TextView
    // onCreate 에서: errorView = findViewById(R.id.error_view)

    private fun showError(message: String) {
        reader.visibility = android.view.View.GONE
        errorView.text = message
        errorView.visibility = android.view.View.VISIBLE
    }

    private fun showDocument(doc: PdfDocument) {
        // 불변조건(§2-8): 새 렌더러 완성 → 어댑터 교체 → 그 다음 옛 렌더러 shutdown.
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
```

기존 `openFile`(throwing 버전)·`runOnUiThread { reader.setDocument(...) }` 호출부는 모두 위 `showDocument`/결과형 `openFile` 로 대체한다. `onDestroy` 의 `renderer?.shutdown()` 는 유지.

- [ ] **Step 5: 빌드 + 실기 검증 (정상 + 손상)**

```bash
./gradlew :app:assembleDebug && ./gradlew :app:installDebug
adb -s emulator-5554 shell am force-stop io.github.june690602_blip.cleanpdf
adb -s emulator-5554 shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity
# 정상 샘플 렌더 확인(스크린샷). 손상 파일은 Task 4 인입 검증에서 함께 확인.
```
Expected: 샘플 정상 렌더(회귀 없음). `assembleDebug` BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add -A && git commit -m "feat: PdfOpenResult-based open with error screen and password dialog"
```

---

## Task 4: 인텐트 인입 — VIEW/SEND → isLikelyPdf 게이트 → 열람

**Files:**
- Modify: `app/src/main/java/.../io/PdfSource.kt` (`looksLikePdf` 추가)
- Modify: `app/src/main/java/.../MainActivity.kt` (onCreate 인텐트 분기 + loadFromUri 게이트)

- [ ] **Step 1: `PdfSource.looksLikePdf` 추가** (이름 + 매직헤더로 순수 `isLikelyPdf` 호출)

`io/PdfSource.kt` 의 `object PdfSource` 안에 추가:
```kotlin
    /** True if [uri] looks like a PDF by display name (.pdf) or %PDF- magic header. */
    fun looksLikePdf(context: Context, uri: Uri): Boolean {
        val name = queryName(context, uri)
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ByteArray(8).let { buf -> val n = input.read(buf); if (n <= 0) ByteArray(0) else buf.copyOf(n) }
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        return isLikelyPdf(name, head)
    }
```
파일 상단에 `import io.github.june690602_blip.cleanpdf.pdf.isLikelyPdf` 추가. (`queryName` 은 이미 private 으로 존재.)

- [ ] **Step 2: `MainActivity.onCreate` 에서 인텐트 분기**

`onCreate` 의 dev-샘플 자동오픈 블록을, 인입 인텐트 우선 처리로 교체:
```kotlin
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
```
상단 import: `import android.content.Intent`, `import android.net.Uri`.

- [ ] **Step 3: `loadFromUri` 에 isLikelyPdf 게이트 추가**

```kotlin
    private fun loadFromUri(uri: Uri) = bg.execute {
        if (!PdfSource.looksLikePdf(this, uri)) {
            runOnUiThread { showError(getString(R.string.error_not_pdf)) }
            return@execute
        }
        runCatching { openFile(PdfSource.copyToCache(this, uri)) }
            .onFailure { runOnUiThread { showError(getString(R.string.error_open)) } }
    }
```

- [ ] **Step 4: 빌드 + 실기 검증 (VIEW/SEND 인입 + 비-PDF 차단)**

```bash
./gradlew :app:assembleDebug && ./gradlew :app:installDebug
# 배포된 PDF를 VIEW 인텐트로 열기 (계측 PDF를 Download 에 push 후):
python - <<'PY'  # 한 페이지 distinct PDF 생성 (Task 검증용; 핸드오프에 동일 스니펫)
# ... (handoff/phase1 검증과 동일한 최소 PDF 생성) ...
PY
adb -s emulator-5554 push <distinct.pdf> /sdcard/Download/intake.pdf   # MSYS_NO_PATHCONV=1
adb -s emulator-5554 shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/intake.pdf -t application/pdf -n io.github.june690602_blip.cleanpdf/.MainActivity
# 스크린샷으로 그 PDF가 떴는지 확인. 비-PDF(예: .txt)는 "PDF 파일이 아닙니다" 에러 확인.
adb -s emulator-5554 logcat -d | grep -iE "FATAL|AndroidRuntime|recycled bitmap" || echo "no crash"
```
Expected: VIEW 인텐트로 전달한 PDF가 열람됨, 비-PDF는 에러 화면, 크래시 0.

- [ ] **Step 5: 커밋**

```bash
git add -A && git commit -m "feat: open PDFs from VIEW/SEND intents with isLikelyPdf gate"
```

---

## Task 5: `RecentFilesStore` — 영속 최근 목록 (순수 로직 TDD + SharedPreferences)

**Files:**
- Create: `app/src/main/java/.../store/RecentFilesStore.kt`
- Test: `app/src/test/java/.../store/RecentFilesLogicTest.kt`

> 영속화는 SharedPreferences 지만, **add/dedup/cap/직렬화 로직은 순수 함수**로 분리해 JVM 단위테스트한다. 각 항목은 `RecentFile(path, name, ts)`. 재열람은 캐시 파일 경로가 살아있으면 `openFile(File(path))`, 없으면 목록에서 제거.

- [ ] **Step 1: 실패 테스트 (순수 로직)**

`app/src/test/java/io/github/june690602_blip/cleanpdf/store/RecentFilesLogicTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.store

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFilesLogicTest {
    private fun rf(path: String) = RecentFile(path, path.substringAfterLast('/'), 0L)

    @Test fun newestFirst() {
        val list = RecentFilesLogic.add(emptyList(), rf("/a"), max = 5)
        val list2 = RecentFilesLogic.add(list, rf("/b"), max = 5)
        assertEquals(listOf("/b", "/a"), list2.map { it.path })
    }

    @Test fun dedupByPathMovesToFront() {
        var l = RecentFilesLogic.add(emptyList(), rf("/a"), max = 5)
        l = RecentFilesLogic.add(l, rf("/b"), max = 5)
        l = RecentFilesLogic.add(l, rf("/a"), max = 5) // re-add /a
        assertEquals(listOf("/a", "/b"), l.map { it.path })
    }

    @Test fun capsToMax() {
        var l = emptyList<RecentFile>()
        for (c in listOf("/a", "/b", "/c", "/d")) l = RecentFilesLogic.add(l, rf(c), max = 2)
        assertEquals(listOf("/d", "/c"), l.map { it.path })
    }

    @Test fun serializeRoundTrip() {
        val l = listOf(RecentFile("/x", "x.pdf", 7L), RecentFile("/y", "y.pdf", 8L))
        assertEquals(l, RecentFilesLogic.deserialize(RecentFilesLogic.serialize(l)))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "*RecentFilesLogicTest"` → FAIL (unresolved).

- [ ] **Step 3: 구현 (순수 로직 + 얇은 영속 래퍼)**

`app/src/main/java/io/github/june690602_blip/cleanpdf/store/RecentFilesStore.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One recent entry. Immutable. [path] = the app-cache file we copied the PDF into. */
data class RecentFile(val path: String, val name: String, val ts: Long)

/** Pure list logic (newest-first, dedup by path, capped) + JSON (de)serialization. */
object RecentFilesLogic {
    fun add(current: List<RecentFile>, item: RecentFile, max: Int): List<RecentFile> =
        (listOf(item) + current.filterNot { it.path == item.path }).take(max)

    fun serialize(list: List<RecentFile>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("p", it.path).put("n", it.name).put("t", it.ts)) }
        return arr.toString()
    }

    fun deserialize(json: String): List<RecentFile> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); RecentFile(o.getString("p"), o.getString("n"), o.getLong("t"))
        }
    }.getOrDefault(emptyList())
}

/** SharedPreferences-backed store. Returns immutable lists. */
class RecentFilesStore(context: Context, private val max: Int = 10) {
    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> = RecentFilesLogic.deserialize(prefs.getString("items", "[]")!!)

    fun add(path: String, name: String) {
        val next = RecentFilesLogic.add(list(), RecentFile(path, name, System.currentTimeMillis()), max)
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }

    fun remove(path: String) {
        val next = list().filterNot { it.path == path }
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }
}
```
> 주: `org.json` 은 단위테스트에서 stub 라 `serializeRoundTrip` 은 Robolectric 가 필요할 수 있다. 만약 JVM에서 `org.json` 이 "not mocked" 면, 테스트 클래스에 `@RunWith(org.robolectric.RobolectricTestRunner::class)` + `@Config(manifest=Config.NONE)` 를 붙인다(robolectric 은 이미 testImplementation 에 있음). add/dedup/cap 3개는 순수라 그대로 통과.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "*RecentFilesLogicTest"` → PASS (4 tests; serializeRoundTrip 이 org.json 때문에 실패하면 위 주석대로 Robolectric 러너 적용 후 재실행).

- [ ] **Step 5: 커밋**

```bash
git add -A && git commit -m "feat: add RecentFilesStore with pure list logic + unit tests"
```

---

## Task 6: 최근 파일 UI 연동 + 인입 시 기록

**Files:**
- Modify: `app/src/main/res/menu/reader_menu.xml` (항목 추가)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/.../MainActivity.kt`

- [ ] **Step 1: 메뉴 항목 + 문자열**

`res/menu/reader_menu.xml` 에 항목 추가(기존 `action_open` 아래):
```xml
    <item
        android:id="@+id/action_recent"
        android:title="@string/recent_files"
        android:showAsAction="never" />
```
`res/values/strings.xml`:
```xml
    <string name="recent_files">최근 파일</string>
    <string name="no_recent">최근 파일 없음</string>
    <string name="recent_missing">파일을 더 이상 찾을 수 없습니다</string>
```

- [ ] **Step 2: `MainActivity` — 최근 기록 + 목록 다이얼로그**

필드 + 연동:
```kotlin
    private val recents by lazy { io.github.june690602_blip.cleanpdf.store.RecentFilesStore(this) }
```
`onOptionsItemSelected` 의 `when` 에 분기 추가:
```kotlin
        R.id.action_recent -> { showRecent(); true }
```
`showDocument` 성공 시 기록을 남기도록, `openFile` 의 Success 경로를 통해 `showDocument(doc, file)` 로 파일을 넘겨 기록한다. `openFile`/`showDocument` 시그니처를 파일을 받도록 조정:
```kotlin
    private fun openFile(file: File) {
        when (val result = PdfDocument.openResult(file.absolutePath)) {
            is PdfOpenResult.Success -> showDocument(result.document, file)
            is PdfOpenResult.NeedsPassword -> runOnUiThread { promptPassword(result.document, file) }
            is PdfOpenResult.Error -> runOnUiThread { showError(getString(R.string.error_open)) }
        }
    }

    private fun showDocument(doc: PdfDocument, file: File) {
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
```
(`promptPassword` 도 `(doc, file)` 를 받아 성공 시 `showDocument(doc, file)` 호출.)

목록 다이얼로그:
```kotlin
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
```

- [ ] **Step 3: 빌드 + 실기 검증 (기록 + 재열람)**

```bash
./gradlew :app:assembleDebug && ./gradlew :app:installDebug
# 피커로 PDF 1개 연 뒤, 오버플로 → "최근 파일" → 항목 탭 → 재열람되는지 스크린샷 확인.
```
Expected: 최근 목록에 방금 연 파일이 뜨고, 탭하면 재열람. `assembleDebug` 성공.

- [ ] **Step 4: 커밋**

```bash
git add -A && git commit -m "feat: recent files list (store on open, reopen from overflow menu)"
```

---

## Task 7: 계측 스모크 + 최종 검증

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/IntentIntakeSmokeTest.kt`

- [ ] **Step 1: VIEW 인텐트 인입 계측 테스트**

```kotlin
package io.github.june690602_blip.cleanpdf

import android.content.Intent
import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IntentIntakeSmokeTest {
    @Test fun opensPdfFromViewIntent() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 번들 샘플을 외부 파일로 복사해 file:// VIEW 인텐트로 전달.
        val out = File(ctx.getExternalFilesDir(null), "intake_smoke.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(out), "application/pdf")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            Thread.sleep(1500)
            scenario.onActivity { a ->
                val rv = a.findViewById<RecyclerView>(R.id.reader)
                assertTrue("intent-opened PDF should have pages", (rv.adapter?.itemCount ?: 0) >= 1)
            }
        }
    }
}
```

- [ ] **Step 2: 실행**

Run: `adb -s emulator-5554 shell pm trim-caches 9999999999 && ./gradlew :app:connectedDebugAndroidTest --tests "*IntentIntakeSmokeTest"`
Expected: PASS.

- [ ] **Step 3: 전체 스위트 + 빌드 (최종 게이트)**

```bash
./gradlew :app:testDebugUnitTest   # Intents 4 + RecentFilesLogic 4 추가 → 26
./gradlew :app:assembleDebug
```
Expected: 모든 단위테스트 PASS, BUILD SUCCESSFUL.

- [ ] **Step 4: 수동 검증 체크리스트** (커밋 본문에 결과 기록)
- [ ] 카톡(또는 파일앱) → .pdf "열기" → 우리 앱으로 열림 (실기기)
- [ ] 공유 → CleanPDF → 열림
- [ ] 비-PDF(.txt 등) → "PDF 파일이 아닙니다" 에러 화면, 크래시 없음
- [ ] (암호 PDF 있으면) 비번 입력 → 열람 / 오답 → 재입력
- [ ] 최근 파일 → 항목 재열람; 캐시 삭제된 항목 → 안내 후 목록서 제거
- [ ] 2번째 파일 열어도 1번째 완전 대체, 크래시 없음 (불변조건 §2-8)

- [ ] **Step 5: 커밋**

```bash
git add -A && git commit -m "test: add intent-intake instrumented smoke; verify Phase 2 manually"
```

**✅ Phase 2 done when:** 카톡 등에서 받은 PDF가 VIEW/SEND로 열리고, 비-PDF/손상/암호가 친절히 처리되며, 최근 파일 재열람이 동작한다; 단위 + 계측 테스트 통과; 수동 체크리스트 클린.

---

## Self-Review

**Spec coverage (이 plan = 스펙 §5.2 파일받기 + §6 에러처리 + §5.8 최근파일):**
- VIEW/SEND 인텐트 + content:// 캐시복사 → Task 2,4 ✓ (`PdfSource.copyToCache` 재사용)
- `isLikelyPdf` 비-PDF 차단 → Task 4 ✓ (`PdfValidation` 재사용, `PdfSource.looksLikePdf` 래퍼)
- 손상/비-PDF 친절 에러 + 암호 PDF 다이얼로그 → Task 3 ✓ (`PdfOpenResult` 연결, `needsPassword/authenticate`)
- 최근 파일(영속, 불변) → Task 5,6 ✓
- **이 plan에 없음(의도적):** 목차/썸네일/검색/선택/야간반전/출시(Phase 3–7). 다음 plan에서.

**Placeholder scan:** Task 4 Step 4 의 PDF 생성 스니펫은 핸드오프/Phase1 검증의 최소-PDF 생성과 동일(분량상 `...` 표기) — 실행 시 그 스니펫을 그대로 사용. 그 외 TBD/TODO 없음.

**Type consistency:** `Intents.incomingUri(action, viewData, sendStream)`, `PdfSource.copyToCache/looksLikePdf`, `PdfDocument.openResult`, `PdfOpenResult.{Success,NeedsPassword,Error}(...)`, `MainActivity.openFile(file)/showDocument(doc,file)/promptPassword(doc,file)/showError/showRecent`, `RecentFile(path,name,ts)`, `RecentFilesLogic.add/serialize/deserialize`, `RecentFilesStore.list/add/remove` — 태스크 간 일관 사용. `PdfOpenResult.NeedsPassword` 를 `data object`→`data class(document)` 로 바꾼 점(Task 3 Step 2) 반영됨.

**불변조건 준수:** `showDocument` 가 "새 렌더러 완성 → setDocument → 옛 렌더러 shutdown" 순서 유지(핸드오프 §2-8). 캐시/렌더러/렌더캡/recycle 관련 클래스는 건드리지 않음.

**Known residual risks (실행 중 확인):**
1. `org.json` 단위테스트 가용성 — Task 5 Step 3 주석에 Robolectric 폴백 명시.
2. octet-stream VIEW 필터가 다른 octet-stream 파일까지 받는 부작용 → `isLikelyPdf` 게이트(Task 4)로 파싱 전 차단 + 에러 화면.
3. 안드 16(S25+) 카톡 미리보기 "열기" 가시성 한계 → SEND 경로로 우회(opendwg Phase 11.2와 동일 이슈; 실기 확인 필요).
