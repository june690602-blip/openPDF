# CleanPDF DocText 구조+이미지 (DR2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 평탄한 `DocText(paragraphs: List<String>)`를 구조 블록 `DocText(blocks: List<DocBlock>)`(문단/표/이미지)로 바꿔, docx·hwpx·hwp 모두 진짜 `<table>` + 인라인 이미지로 렌더한다.

**Architecture:** 추출기 → `List<DocBlock>` → `DocHtml.render` → 오프라인 WebView. 공용 `XmlBlocks`(resolver 주입)가 docx/hwpx를, hwplib 객체모델이 hwp를 블록으로 추출. 이미지는 `ImageFilter`(매직바이트·용량캡)로 거른 뒤 base64 인라인. 마이그레이션은 `toResult(List<String>)`이 한시적으로 문자열을 `Para`로 감싸 빌드를 항상 green으로 유지.

**Tech Stack:** Kotlin, Android WebView, `android.util.Xml`(XmlPullParser), `android.util.Base64`, `kr.dogfoot:hwplib:1.1.10`(객체모델), JUnit4 + Robolectric(단위) + AndroidJUnit4(계측).

---

## 작업 규약 (실행자 필독)
- 브랜치 `feat/doctext-structured`(main `a265e94`에서 분기). main 직접 커밋 금지.
- 모든 명령 프로젝트 루트에서. **Windows Bash는 매 호출 cwd 리셋** → 명령마다 `cd /c/dev/openPDF &&` 선행. 기기 경로 인자엔 `MSYS_NO_PATHCONV=1`.
- 빌드 `./gradlew :app:assembleDebug` · 단위 `./gradlew :app:testDebugUnitTest --tests "<FQCN>"` · 계측 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`(에뮬 필요, 실행 후 앱 언인스톨).
- `object`는 Kotlin 예약어 → hwplib import는 백틱: ``import kr.dogfoot.hwplib.`object`.…``.
- LF→CRLF 경고 무해. 패키지 루트 `io.github.june690602_blip.cleanpdf`.

## 파일 구조 (생성/교체)
- **신규(순수)**: `doc/DocBlock.kt`(모델), `doc/ImageFilter.kt`(매직·캡), `doc/XmlBlocks.kt`(docx/hwpx 블록 파서, XmlFlowText 대체), `doc/HwpBlocks.kt`(hwplib 객체모델→블록).
- **교체**: `doc/DocText.kt`(blocks), `doc/DocHtml.kt`(render+escape), `doc/DocTextExtractor.kt`(toResult(blocks)+toResultStrings), `doc/DocxExtractor.kt`/`HwpxExtractor.kt`/`HwpExtractor.kt`(구조+이미지).
- **삭제(E)**: `doc/XmlFlowText.kt`+`XmlFlowTextTest`, `toResultStrings`(마이그레이션 끝나면).
- **무변**: `DocTextActivity.kt`(여전히 `DocHtml.toHtml(result.text)`), 라우팅/매니페스트/recents/strings.

---

# Phase S-A — 순수 기반 (추가만, 항상 green)

## Task A1: DocBlock 모델
**Files:** Create `doc/DocBlock.kt`; Test `test/.../doc/DocBlockTest.kt`

- [ ] **Step 1: 실패 테스트**
```kotlin
// DocBlockTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DocBlockTest {
    @Test fun imageEqualityByContent() {
        val a = DocBlock.Image("image/png", byteArrayOf(1, 2, 3))
        val b = DocBlock.Image("image/png", byteArrayOf(1, 2, 3))
        val c = DocBlock.Image("image/png", byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
    @Test fun paraAndTable() {
        assertEquals(DocBlock.Para("x"), DocBlock.Para("x"))
        assertEquals(DocBlock.Table(listOf(listOf("a", "b"))), DocBlock.Table(listOf(listOf("a", "b"))))
    }
}
```
- [ ] **Step 2: 실패 확인** — `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.DocBlockTest"` → FAIL.
- [ ] **Step 3: 구현**
```kotlin
// DocBlock.kt
package io.github.june690602_blip.cleanpdf.doc

/** 구조 블록. 표 셀은 평탄 텍스트, 이미지는 래스터 바이트. */
sealed interface DocBlock {
    data class Para(val text: String) : DocBlock
    data class Table(val rows: List<List<String>>) : DocBlock
    class Image(val mime: String, val bytes: ByteArray) : DocBlock {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return mime == other.mime && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()
        override fun toString(): String = "Image(mime=$mime, bytes=${bytes.size})"
    }
}
```
- [ ] **Step 4: 통과 확인** → PASS (2 tests).
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocBlock.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocBlockTest.kt && git commit -m "feat: DocBlock 구조 모델(Para/Table/Image)"
```

## Task A2: ImageFilter (매직바이트 + 용량캡)
**Files:** Create `doc/ImageFilter.kt`; Test `test/.../doc/ImageFilterTest.kt`

- [ ] **Step 1: 실패 테스트**
```kotlin
// ImageFilterTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFilterTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun detectsRasterMimes() {
        assertEquals("image/png", ImageFilter.mimeOf(b(0x89, 0x50, 0x4E, 0x47, 0, 0)))
        assertEquals("image/jpeg", ImageFilter.mimeOf(b(0xFF, 0xD8, 0xFF, 0)))
        assertEquals("image/gif", ImageFilter.mimeOf(b(0x47, 0x49, 0x46, 0x38)))
        assertEquals("image/bmp", ImageFilter.mimeOf(b(0x42, 0x4D, 0, 0)))
    }
    @Test fun rejectsNonRaster() {
        assertNull(ImageFilter.mimeOf(b(0xD7, 0xCD, 0xC6, 0x9A)))   // WMF
        assertNull(ImageFilter.mimeOf(b(0x01, 0x00, 0x00, 0x00)))   // EMF
        assertNull(ImageFilter.mimeOf(b(0x00, 0x01, 0x02)))         // unknown
    }
    @Test fun classifyOkOversizedUnsupported() {
        val png = b(0x89, 0x50, 0x4E, 0x47) + ByteArray(10)
        val ok = ImageFilter.classify(png, 0)
        assertTrue(ok is ImageFilter.Outcome.Ok)
        assertEquals("image/png", (ok as ImageFilter.Outcome.Ok).image.mime)
        // unsupported
        assertTrue(ImageFilter.classify(b(0xD7, 0xCD, 0xC6, 0x9A), 0) is ImageFilter.Outcome.Unsupported)
        // oversized: cumulative over total cap
        val big = b(0x89, 0x50, 0x4E, 0x47) + ByteArray(10)
        assertTrue(ImageFilter.classify(big, ImageFilter.MAX_TOTAL_BYTES) is ImageFilter.Outcome.Oversized)
    }
}
```
- [ ] **Step 2: 실패 확인** — `--tests "...doc.ImageFilterTest"` → FAIL.
- [ ] **Step 3: 구현**
```kotlin
// ImageFilter.kt
package io.github.june690602_blip.cleanpdf.doc

/** 매직바이트로 웹 렌더 가능한 래스터만 통과 + 용량 캡(순수). */
object ImageFilter {
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024

    sealed interface Outcome {
        data class Ok(val image: DocBlock.Image) : Outcome
        data object Oversized : Outcome
        data object Unsupported : Outcome
    }

    /** PNG/JPEG/GIF/BMP → mime, 그 외(WMF/EMF/OLE/unknown) → null. */
    fun mimeOf(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            else -> null
        }
    }

    /** 포맷·용량 판정. usedSoFar = 지금까지 인라인한 누적 바이트. */
    fun classify(bytes: ByteArray, usedSoFar: Int): Outcome {
        val mime = mimeOf(bytes) ?: return Outcome.Unsupported
        if (bytes.size > MAX_IMAGE_BYTES || usedSoFar.toLong() + bytes.size > MAX_TOTAL_BYTES) return Outcome.Oversized
        return Outcome.Ok(DocBlock.Image(mime, bytes))
    }
}
```
- [ ] **Step 4: 통과 확인** → PASS (3 tests).
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/ImageFilter.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/ImageFilterTest.kt && git commit -m "feat: ImageFilter(매직바이트 래스터 판정 + 용량캡)"
```

## Task A3: XmlBlocks (docx/hwpx 공용 블록 파서)
**Files:** Create `doc/XmlBlocks.kt`; Test `test/.../doc/XmlBlocksTest.kt`

- [ ] **Step 1: 실패 테스트** (Robolectric — `android.util.Xml`)
```kotlin
// XmlBlocksTest.kt
package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmlBlocksTest {
    private fun parse(xml: String, resolve: (String) -> ByteArray? = { null }): List<DocBlock> {
        val p: XmlPullParser = Xml.newPullParser()
        p.setInput(StringReader(xml))
        return XmlBlocks.parse(p, resolve)
    }
    private val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test fun paragraphsAndTable() {
        val out = parse(
            """<w:document xmlns:w="x"><w:body>
                 <w:p><w:r><w:t>머리말</w:t></w:r></w:p>
                 <w:tbl>
                   <w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                         <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr>
                 </w:tbl>
               </w:body></w:document>"""
        )
        assertEquals(DocBlock.Para("머리말"), out[0])
        assertEquals(DocBlock.Table(listOf(listOf("항목", "금액"))), out[1])
    }

    @Test fun imageRefResolvedToImageBlock() {
        val out = parse(
            """<w:p xmlns:w="x" xmlns:r="y"><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>""",
            resolve = { id -> if (id == "rId7") pngHeader + ByteArray(20) else null }
        )
        assertTrue("expected an Image block", out.any { it is DocBlock.Image && it.mime == "image/png" })
    }

    @Test fun unsupportedImageOmitted() {
        val out = parse(
            """<w:p xmlns:w="x" xmlns:r="y"><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:p>""",
            resolve = { byteArrayOf(0xD7.toByte(), 0xCD.toByte(), 0xC6.toByte(), 0x9A.toByte()) } // WMF
        )
        assertTrue("WMF must be omitted", out.none { it is DocBlock.Image })
    }
}
```
- [ ] **Step 2: 실패 확인** — `--tests "...doc.XmlBlocksTest"` → FAIL.
- [ ] **Step 3: 구현**
```kotlin
// XmlBlocks.kt
package io.github.june690602_blip.cleanpdf.doc

import org.xmlpull.v1.XmlPullParser

/**
 * DOCX(document.xml)·HWPX(section*.xml) 공용 본문 → 구조 블록(순수). local-name 매칭(prefix 무관).
 *  <p>=Para, <t>=텍스트, <tab>=\t, <br>/<cr>=\n, <tbl>/<tr>/<tc>=Table(셀=평탄 텍스트).
 *  이미지 참조 속성(r:embed=docx, binaryItemIDRef=hwpx) → resolveImage(refId) → ImageFilter.
 *  표 안 이미지는 v1 생략. 중첩표 best-effort(텍스트는 부모 셀로).
 */
object XmlBlocks {
    private val IMAGE_REF_ATTRS = arrayOf("r:embed", "binaryItemIDRef")

    fun parse(parser: XmlPullParser, resolveImage: (String) -> ByteArray?): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        val para = StringBuilder()
        val cell = StringBuilder()
        var rows: MutableList<MutableList<String>>? = null
        var curRow: MutableList<String>? = null
        var tableDepth = 0
        var inCell = false
        var inT = false
        var usedImg = 0

        fun local(n: String?) = n?.substringAfterLast(':') ?: ""
        fun sink() = if (inCell) cell else para
        fun imageRef(): String? {
            for (a in IMAGE_REF_ATTRS) parser.getAttributeValue(null, a)?.let { return it }
            return null
        }

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (local(parser.name)) {
                        "p" -> if (!inCell && tableDepth == 0) para.setLength(0)
                        "t" -> inT = true
                        "tab" -> sink().append('\t')
                        "br", "cr" -> sink().append('\n')
                        "tbl" -> { tableDepth++; if (tableDepth == 1) rows = ArrayList() }
                        "tr" -> if (tableDepth == 1) curRow = ArrayList()
                        "tc" -> if (tableDepth == 1) { inCell = true; cell.setLength(0) }
                    }
                    if (tableDepth == 0) {
                        val ref = imageRef()
                        if (ref != null) {
                            if (para.isNotBlank()) { out.add(DocBlock.Para(para.toString())); para.setLength(0) }
                            resolveImage(ref)?.let { bytes ->
                                when (val o = ImageFilter.classify(bytes, usedImg)) {
                                    is ImageFilter.Outcome.Ok -> { out.add(o.image); usedImg += bytes.size }
                                    ImageFilter.Outcome.Oversized -> out.add(DocBlock.Para("[큰 이미지 생략]"))
                                    ImageFilter.Outcome.Unsupported -> {}
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inT) sink().append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "t" -> inT = false
                    "p" -> {
                        if (inCell) { if (cell.isNotEmpty() && cell.last() != ' ') cell.append(' ') }
                        else if (tableDepth == 0) {
                            if (para.isNotBlank()) out.add(DocBlock.Para(para.toString()))
                            para.setLength(0)
                        }
                    }
                    "tc" -> if (tableDepth == 1) { curRow?.add(cell.toString().trim()); inCell = false }
                    "tr" -> if (tableDepth == 1) { curRow?.let { rows?.add(it) }; curRow = null }
                    "tbl" -> {
                        if (tableDepth == 1) { rows?.let { if (it.isNotEmpty()) out.add(DocBlock.Table(it)) }; rows = null }
                        if (tableDepth > 0) tableDepth--
                    }
                }
            }
            ev = parser.next()
        }
        if (para.isNotBlank()) out.add(DocBlock.Para(para.toString()))
        return out
    }
}
```
- [ ] **Step 4: 통과 확인** → PASS (3 tests). 표/이미지/생략 검증.
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/XmlBlocks.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/XmlBlocksTest.kt && git commit -m "feat: XmlBlocks — docx/hwpx 공용 구조 블록 파서(표·이미지)"
```

## Task A4: DocHtml.render(blocks) 추가 (기존 toHtml 유지)
**Files:** Modify `doc/DocHtml.kt`; Test `test/.../doc/DocHtmlRenderTest.kt`

- [ ] **Step 1: 실패 테스트** (Robolectric — `android.util.Base64`)
```kotlin
// DocHtmlRenderTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocHtmlRenderTest {
    @Test fun rendersParaTableImage() {
        val html = DocHtml.render(
            listOf(
                DocBlock.Para("본문 <글자>"),
                DocBlock.Table(listOf(listOf("항목", "금액"), listOf("철근", "1000"))),
                DocBlock.Image("image/png", byteArrayOf(1, 2, 3))
            )
        )
        assertTrue(html.contains("<p>본문 &lt;글자&gt;</p>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>항목</td><td>금액</td>"))
        assertTrue(html.contains("<img src=\"data:image/png;base64,"))
        assertFalse(html.contains("<글자>"))           // escaped
        assertTrue(html.contains("charset=\"utf-8\""))
    }
}
```
- [ ] **Step 2: 실패 확인** — `--tests "...doc.DocHtmlRenderTest"` → FAIL (render 미존재).
- [ ] **Step 3: 구현** — `doc/DocHtml.kt`에 `render` 추가(기존 `escape`/`toHtml(DocText)`는 그대로 둠):
```kotlin
    /** 구조 블록 → 오프라인 HTML(문단=<p>, 표=<table>, 이미지=base64 <img>). */
    fun render(blocks: List<DocBlock>): String {
        val body = StringBuilder()
        for (b in blocks) when (b) {
            is DocBlock.Para -> body.append("<p>").append(escape(b.text)).append("</p>")
            is DocBlock.Table -> {
                body.append("<table>")
                for (row in b.rows) {
                    body.append("<tr>")
                    for (c in row) body.append("<td>").append(escape(c)).append("</td>")
                    body.append("</tr>")
                }
                body.append("</table>")
            }
            is DocBlock.Image -> {
                val b64 = android.util.Base64.encodeToString(b.bytes, android.util.Base64.NO_WRAP)
                body.append("<img src=\"data:").append(b.mime).append(";base64,").append(b64).append("\">")
            }
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" +
            "body{font-family:sans-serif;font-size:1rem;line-height:1.6;margin:12px;word-wrap:break-word}" +
            "p{white-space:pre-wrap;margin:0 0 .6em}" +
            "table{border-collapse:collapse;margin:.6em 0;max-width:100%}" +
            "td{border:1px solid #999;padding:4px 8px;vertical-align:top}" +
            "img{max-width:100%;height:auto;margin:.6em 0}</style></head><body>" +
            body + "</body></html>"
    }
```
- [ ] **Step 4: 통과 확인** → PASS. `assembleDebug` → SUCCESS(기존 toHtml/DocText 무변).
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocHtml.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocHtmlRenderTest.kt && git commit -m "feat: DocHtml.render(blocks) — 표·이미지 HTML"
```

---

# Phase S-B — 모델 전환(cut) + DOCX 구조화

## Task B1: DocText→blocks 마이그레이션 (빌드 green 유지)
**Files:** Modify `doc/DocText.kt`, `doc/DocTextExtractor.kt`, `doc/DocHtml.kt`, `doc/DocxExtractor.kt`, `doc/HwpxExtractor.kt`, `doc/HwpExtractor.kt`; Modify tests `ToResultTest.kt`, `DocxExtractorSmokeTest.kt`, `HwpxExtractorSmokeTest.kt`; Delete `DocHtmlTest.kt`(render로 대체)

- [ ] **Step 1: 모델 교체** — `doc/DocText.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.doc

/** 추출 결과(불변). 문단/표/이미지 블록 순서 목록. */
data class DocText(val blocks: List<DocBlock>)

sealed interface ExtractResult {
    data class Success(val text: DocText) : ExtractResult
    data object Empty : ExtractResult
    data class Failure(val reason: String) : ExtractResult
}
```

- [ ] **Step 2: toResult 교체 + 한시 wrapper** — `doc/DocTextExtractor.kt`의 `toResult` 함수를 교체:
```kotlin
/** 의미있는 블록이 하나도 없으면 Empty, 아니면 Success. */
fun toResult(blocks: List<DocBlock>): ExtractResult =
    if (blocks.none { it.hasContent() }) ExtractResult.Empty
    else ExtractResult.Success(DocText(blocks))

/** 마이그레이션용: 문자열 줄을 Para 블록으로 감쌈(포맷 추출기가 블록으로 옮겨가면 제거). */
fun toResultStrings(lines: List<String>): ExtractResult = toResult(lines.map { DocBlock.Para(it) })

private fun DocBlock.hasContent(): Boolean = when (this) {
    is DocBlock.Para -> text.isNotBlank()
    is DocBlock.Table -> rows.any { r -> r.any { it.isNotBlank() } }
    is DocBlock.Image -> true
}
```
(`interface DocTextExtractor`/`Extractors` 부분은 그대로.)

- [ ] **Step 3: DocHtml.toHtml 위임** — `doc/DocHtml.kt`의 `toHtml(text: DocText)` 본문을 교체:
```kotlin
    fun toHtml(text: DocText): String = render(text.blocks)
```
(`escape`/`render`는 유지. 기존 `<pre>` 본문 로직 제거.)

- [ ] **Step 4: 3 추출기 한시 전환** — 각 파일에서 `toResult(` 호출을 `toResultStrings(`로 교체:
  - `DocxExtractor.kt`: `toResultStrings(XmlFlowText.parse(parser))`
  - `HwpxExtractor.kt`: `toResultStrings(... XmlFlowText.parse ... )` (누적 `all` 그대로, 마지막 `toResultStrings(all)`)
  - `HwpExtractor.kt`: `toResultStrings(raw.split('\n'))`

- [ ] **Step 5: 깨진 테스트 수정**
  - `ToResultTest.kt` 교체:
```kotlin
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToResultTest {
    @Test fun blankParasAreEmpty() = assertEquals(ExtractResult.Empty, toResultStrings(listOf("", "  ")))
    @Test fun emptyIsEmpty() = assertEquals(ExtractResult.Empty, toResult(emptyList()))
    @Test fun tableIsContent() {
        val r = toResult(listOf(DocBlock.Table(listOf(listOf("a")))))
        assertTrue(r is ExtractResult.Success)
    }
    @Test fun stringsWrapToParaBlocks() {
        val r = toResultStrings(listOf("본문"))
        assertTrue(r is ExtractResult.Success)
        assertEquals(listOf(DocBlock.Para("본문")), (r as ExtractResult.Success).text.blocks)
    }
}
```
  - `DocxExtractorSmokeTest.kt` / `HwpxExtractorSmokeTest.kt`: `.text.paragraphs` 참조를 `.text.blocks` 기반으로 교체(이 시점엔 표가 평탄 Para라 텍스트 포함 여부로 검증):
    DOCX 예) `assertTrue(blocks.any { it is DocBlock.Para && it.text.contains("계약서 본문") })` 그리고 `assertTrue(blocks.any { it is DocBlock.Para && it.text.contains("항목") })`. (where `val blocks = (result as ExtractResult.Success).text.blocks`)
    HWPX 예) `assertEquals(listOf("첫장","둘째장"), blocks.filterIsInstance<DocBlock.Para>().map{it.text})`.
  - `DocHtmlTest.kt` 삭제(`git rm`): render 테스트(A4)가 escape/HTML을 커버.

- [ ] **Step 6: 전체 빌드+테스트** — `cd /c/dev/openPDF && ./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL, 전부 PASS. (세 포맷 여전히 동작: 표는 평탄 Para로 임시 표시.)

- [ ] **Step 7: 커밋**
```bash
cd /c/dev/openPDF && git rm app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocHtmlTest.kt && git add -A app/src/main/java/io/github/june690602_blip/cleanpdf/doc app/src/test/java/io/github/june690602_blip/cleanpdf/doc app/src/androidTest/java/io/github/june690602_blip/cleanpdf && git commit -m "refactor: DocText→DocBlock 모델 전환(toResultStrings 한시 wrapper로 green 유지)"
```

## Task B2: DocxExtractor 구조+이미지
**Files:** Modify `doc/DocxExtractor.kt`; Modify `androidTest/.../DocxExtractorSmokeTest.kt`

- [ ] **Step 1: 실패 테스트** — `DocxExtractorSmokeTest.kt` 교체(이미지+표 포함 docx를 in-test zip으로):
```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.DocxExtractor
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocxExtractorSmokeTest {
    // 1x1 PNG
    private val png = intArrayOf(
        0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,0x0D,0x49,0x48,0x44,0x52,0,0,0,1,0,0,0,1,
        8,6,0,0,0,0x1F,0x15,0xC4,0x89,0,0,0,0x0D,0x49,0x44,0x41,0x54,0x78,0x9C,0x62,0,1,0,0,5,
        0,1,0x0D,0x0A,0x2D,0xB4,0,0,0,0,0x49,0x45,0x4E,0x44,0xAE,0x42,0x60,0x82
    ).map { it.toByte() }.toByteArray()

    @Test fun extractsTableAndInlineImage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "structured.docx")
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:w" xmlns:r="urn:r" xmlns:a="urn:a"><w:body>
              <w:p><w:r><w:t>계약서 본문</w:t></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                          <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
            </w:body></w:document>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="urn:rel">
              <Relationship Id="rId1" Type="urn:image" Target="media/img1.png"/>
            </Relationships>"""
        ZipOutputStream(docx.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(doc.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("word/_rels/document.xml.rels")); z.write(rels.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("word/media/img1.png")); z.write(png); z.closeEntry()
        }
        val result = DocxExtractor.extract(docx)
        assertTrue(result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertTrue("table", blocks.any { it is DocBlock.Table && it.rows == listOf(listOf("항목","금액")) })
        assertTrue("image", blocks.any { it is DocBlock.Image && it.mime == "image/png" })
        assertTrue("para", blocks.any { it is DocBlock.Para && it.text.contains("계약서 본문") })
    }
}
```
- [ ] **Step 2: 실패 확인** — `connectedDebugAndroidTest -P...class=...DocxExtractorSmokeTest` → FAIL(아직 평탄).
- [ ] **Step 3: 구현** — `doc/DocxExtractor.kt` 교체:
```kotlin
package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile

/** DOCX = ZIP. document.xml을 XmlBlocks로 구조화, 이미지는 rels(rId→media)로 해소. */
object DocxExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        ZipFile(file).use { zip ->
            val docEntry = zip.getEntry("word/document.xml") ?: return ExtractResult.Failure("no document.xml")
            val rels = parseRels(zip)  // rId -> "word/<target>"
            zip.getInputStream(docEntry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null)
                val blocks = XmlBlocks.parse(parser) { rid ->
                    val path = rels[rid]
                    if (path == null) null
                    else zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                }
                toResult(blocks)
            }
        }
    }.getOrElse { ExtractResult.Failure(it.message ?: "docx parse error") }

    /** word/_rels/document.xml.rels: Id -> "word/" + Target(상대경로). 이미지 외 관계도 무해. */
    private fun parseRels(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("word/_rels/document.xml.rels") ?: return emptyMap()
        val map = HashMap<String, String>()
        zip.getInputStream(entry).use { input ->
            val p = Xml.newPullParser(); p.setInput(input, null)
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && p.name.substringAfterLast(':') == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val target = p.getAttributeValue(null, "Target")
                    if (id != null && target != null) map[id] = "word/" + target.removePrefix("/").removePrefix("word/")
                }
                ev = p.next()
            }
        }
        return map
    }
}
```
> `it.readBytes()`는 Kotlin 확장(전체 읽기, 모든 API OK). resolver 람다는 마지막 식을 반환.
- [ ] **Step 4: 통과 확인** — 위 계측 → PASS(table+image+para 블록).
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocxExtractor.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocxExtractorSmokeTest.kt && git commit -m "feat: DocxExtractor 구조+이미지(XmlBlocks + rels resolver)"
```

---

# Phase S-C — HWPX 구조+이미지

## Task C1: HwpxExtractor 구조+이미지
**Files:** Modify `doc/HwpxExtractor.kt`; Modify `androidTest/.../HwpxExtractorSmokeTest.kt`

- [ ] **Step 1: 실패 테스트** — `HwpxExtractorSmokeTest.kt` 교체(섹션 표 + 이미지 ref + content.hpf + BinData):
```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpxExtractor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class HwpxExtractorSmokeTest {
    private val png = intArrayOf(
        0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,0x0D,0x49,0x48,0x44,0x52,0,0,0,1,0,0,0,1,
        8,6,0,0,0,0x1F,0x15,0xC4,0x89,0,0,0,0x0D,0x49,0x44,0x41,0x54,0x78,0x9C,0x62,0,1,0,0,5,
        0,1,0x0D,0x0A,0x2D,0xB4,0,0,0,0,0x49,0x45,0x4E,0x44,0xAE,0x42,0x60,0x82
    ).map { it.toByte() }.toByteArray()

    @Test fun extractsTableAndImage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hwpx = File(ctx.cacheDir, "structured.hwpx")
        val sec = """<?xml version="1.0" encoding="UTF-8"?>
            <hs:sec xmlns:hs="urn:s" xmlns:hp="urn:p"><hp:p><hp:run><hp:t>한글 본문</hp:t></hp:run></hp:p>
              <hp:tbl><hp:tr><hp:tc><hp:subList><hp:p><hp:run><hp:t>가</hp:t></hp:run></hp:p></hp:subList></hp:tc>
                            <hp:tc><hp:subList><hp:p><hp:run><hp:t>나</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr></hp:tbl>
              <hp:p><hp:run><hp:pic binaryItemIDRef="image1"/></hp:run></hp:p></hs:sec>"""
        val hpf = """<?xml version="1.0" encoding="UTF-8"?>
            <opf:package xmlns:opf="urn:opf"><opf:manifest>
              <opf:item id="image1" href="BinData/image1.png" media-type="image/png"/>
            </opf:manifest></opf:package>"""
        ZipOutputStream(hwpx.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Contents/section0.xml")); z.write(sec.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("Contents/content.hpf")); z.write(hpf.toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry("BinData/image1.png")); z.write(png); z.closeEntry()
        }
        val result = HwpxExtractor.extract(hwpx)
        assertTrue(result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertTrue("table", blocks.any { it is DocBlock.Table && it.rows == listOf(listOf("가","나")) })
        assertTrue("image", blocks.any { it is DocBlock.Image && it.mime == "image/png" })
        assertTrue("para", blocks.any { it is DocBlock.Para && it.text.contains("한글 본문") })
    }
}
```
- [ ] **Step 2: 실패 확인** → FAIL.
- [ ] **Step 3: 구현** — `doc/HwpxExtractor.kt` 교체:
```kotlin
package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile

/** HWPX = ZIP. section*.xml(번호순)을 XmlBlocks로, 이미지는 content.hpf(id→href)로 해소. */
object HwpxExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        ZipFile(file).use { zip ->
            val manifest = parseManifest(zip)  // binItemId -> href
            val sections = zip.entries().asSequence()
                .filter { it.name.matches(Regex("Contents/section\\d+\\.xml")) }
                .sortedBy { sectionIndex(it.name) }.toList()
            if (sections.isEmpty()) return ExtractResult.Failure("no section xml")
            val all = ArrayList<DocBlock>()
            for (entry in sections) {
                zip.getInputStream(entry).use { input ->
                    val parser = Xml.newPullParser(); parser.setInput(input, null)
                    all.addAll(XmlBlocks.parse(parser) { ref -> resolveBin(zip, manifest, ref) })
                }
            }
            toResult(all)
        }
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwpx parse error") }

    private fun resolveBin(zip: ZipFile, manifest: Map<String, String>, ref: String): ByteArray? {
        val href = manifest[ref] ?: return null
        val base = href.removePrefix("/")
        val candidates = listOf(base, "Contents/$base", "BinData/" + base.substringAfterLast('/'))
        for (path in candidates) zip.getEntry(path)?.let { e -> return zip.getInputStream(e).use { it.readBytes() } }
        return null
    }

    /** Contents/content.hpf 의 <opf:item id href> → id→href. */
    private fun parseManifest(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("Contents/content.hpf") ?: return emptyMap()
        val map = HashMap<String, String>()
        zip.getInputStream(entry).use { input ->
            val p = Xml.newPullParser(); p.setInput(input, null)
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && p.name.substringAfterLast(':') == "item") {
                    val id = p.getAttributeValue(null, "id"); val href = p.getAttributeValue(null, "href")
                    if (id != null && href != null) map[id] = href
                }
                ev = p.next()
            }
        }
        return map
    }
}

/** "Contents/section12.xml" → 12. */
fun sectionIndex(name: String): Int =
    Regex("section(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
```
> `sectionIndex`는 기존 파일에 이미 있다 — 중복 선언되지 않도록 기존 정의를 이 파일에 유지(다른 곳에 있었다면 한 곳만 남길 것). `SectionIndexTest`는 그대로 통과.
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/HwpxExtractor.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/HwpxExtractorSmokeTest.kt && git commit -m "feat: HwpxExtractor 구조+이미지(XmlBlocks + content.hpf resolver)"
```

---

# Phase S-D — HWP(객체모델) 구조+이미지

## Task D1: HwpBlocks + HwpExtractor 객체모델 전환
**Files:** Create `doc/HwpBlocks.kt`; Modify `doc/HwpExtractor.kt`; Modify `androidTest/.../HwpExtractorSmokeTest.kt`

- [ ] **Step 1: 실패 테스트** — `HwpExtractorSmokeTest.kt` 교체(skip-if-absent, 표/이미지 메트릭 검증):
```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocBlock
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpExtractor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HwpExtractorSmokeTest {
    @Test fun extractsStructureIfFixturePresent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val present = runCatching { inst.context.assets.open("sample.hwp").close() }.isSuccess
        assumeTrue("place app/src/androidTest/assets/sample.hwp (table+image) to run", present)
        val out = File(inst.targetContext.cacheDir, "sample.hwp")
        inst.context.assets.open("sample.hwp").use { i -> out.outputStream().use { i.copyTo(it) } }

        val result = HwpExtractor.extract(out)
        assertTrue("expected Success, got $result", result is ExtractResult.Success)
        val blocks = (result as ExtractResult.Success).text.blocks
        assertTrue("has korean para", blocks.any { it is DocBlock.Para && it.text.any { c -> c in '가'..'힣' } })
        assertTrue("has table", blocks.any { it is DocBlock.Table })
    }
}
```
- [ ] **Step 2: 실패 확인** — `connectedDebugAndroidTest -P...class=...HwpExtractorSmokeTest` → 컴파일 FAIL(HwpBlocks 미존재) 또는 fixture 없으면 SKIP. (HwpBlocks 컴파일 통과가 1차 목표.)
- [ ] **Step 3: 구현** — `doc/HwpBlocks.kt`(객체모델 순회. `object` 백틱 주의. 이미지는 등장 순서로 임베디드와 페어링):
```kotlin
package io.github.june690602_blip.cleanpdf.doc

import kr.dogfoot.hwplib.`object`.HWPFile
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPicture
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph

/** hwplib 객체모델 → DocBlock. 표=ControlTable, 이미지=등장순으로 binData 임베디드와 페어링. */
object HwpBlocks {
    fun build(hwp: HWPFile): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        val images = hwp.binData?.embeddedBinaryDataList ?: arrayListOf()
        var picIdx = 0
        var usedImg = 0

        fun walk(paras: Array<Paragraph>) {
            for (p in paras) {
                val txt = runCatching { p.normalString ?: "" }.getOrDefault("")
                if (txt.isNotBlank()) out.add(DocBlock.Para(txt))
                val controls = p.controlList ?: continue
                for (c in controls) when (c) {
                    is ControlTable -> {
                        val rows = ArrayList<List<String>>()
                        for (r in c.rowList) {
                            val cells = ArrayList<String>()
                            for (cell in r.cellList) {
                                val cellText = StringBuilder()
                                for (cp in cell.paragraphList.paragraphs) {
                                    val t = runCatching { cp.normalString ?: "" }.getOrDefault("")
                                    if (t.isNotBlank()) { if (cellText.isNotEmpty()) cellText.append(' '); cellText.append(t) }
                                }
                                cells.add(cellText.toString().trim())
                            }
                            if (cells.isNotEmpty()) rows.add(cells)
                        }
                        if (rows.isNotEmpty()) out.add(DocBlock.Table(rows))
                    }
                    is ControlPicture -> {
                        val bytes = images.getOrNull(picIdx++)?.data
                        if (bytes != null) when (val o = ImageFilter.classify(bytes, usedImg)) {
                            is ImageFilter.Outcome.Ok -> { out.add(o.image); usedImg += bytes.size }
                            ImageFilter.Outcome.Oversized -> out.add(DocBlock.Para("[큰 이미지 생략]"))
                            ImageFilter.Outcome.Unsupported -> {}
                        }
                    }
                }
            }
        }
        for (sec in hwp.bodyText.sectionList) walk(sec.paragraphs)
        return out
    }
}
```
그리고 `doc/HwpExtractor.kt` 교체:
```kotlin
package io.github.june690602_blip.cleanpdf.doc

import kr.dogfoot.hwplib.reader.HWPReader
import java.io.File

/** HWP v5 = hwplib 객체모델 → 구조 블록(표·이미지). */
object HwpExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        val hwp = file.inputStream().use { HWPReader.fromInputStream(it) }
        toResult(HwpBlocks.build(hwp))
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwp parse error") }
}
```
> 이미지 페어링은 등장순(v1 best-effort). binItemID 정밀 매핑은 후속. ImageFilter가 WMF/EMF 자동 차단.
- [ ] **Step 4: 통과 확인** — `assembleDebug` SUCCESS(객체모델 API 결합 확인). 계측은 fixture 없으면 SKIP(0 실패). **검증용 fixture 로컬 배치(미커밋)**: 표+이미지 있는 실제 .hwp를 `app/src/androidTest/assets/sample.hwp`에 두고 1회 실행→PASS 확인 후 **삭제**(개인정보 미커밋). `.gitignore`에 `app/src/androidTest/assets/*.hwp` 추가 권장.
- [ ] **Step 5: 커밋** (fixture·gitignore 제외, 코드만)
```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/HwpBlocks.kt app/src/main/java/io/github/june690602_blip/cleanpdf/doc/HwpExtractor.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/HwpExtractorSmokeTest.kt && git commit -m "feat: HwpExtractor 객체모델 전환(HwpBlocks 표·이미지)"
```

---

# Phase S-E — 정리 + 검증 + 문서

## Task E1: 죽은 코드 제거 + 전체 검증
**Files:** Delete `doc/XmlFlowText.kt`, `test/.../doc/XmlFlowTextTest.kt`; Modify `doc/DocTextExtractor.kt`(toResultStrings 제거)

- [ ] **Step 1: 사용처 확인** — `cd /c/dev/openPDF && git grep -n "XmlFlowText\|toResultStrings" -- "app/src/main"` → main에서 더 이상 안 쓰이는지 확인(추출기 3개 모두 블록 전환 완료 상태). 테스트의 `toResultStrings` 사용(ToResultTest)은 남겨도 되나, 제거 시 그 테스트도 toResult(blocks)로 교체.
- [ ] **Step 2: 제거** — `XmlFlowText.kt`+`XmlFlowTextTest.kt` 삭제. `DocTextExtractor.kt`에서 `toResultStrings` 제거. `ToResultTest.kt`의 `stringsWrapToParaBlocks`/`blankParasAreEmpty`를 `toResult(blocks)` 기반으로 교체(예: `toResult(listOf(DocBlock.Para("")))` → Empty).
- [ ] **Step 3: 전체 빌드+단위** — `cd /c/dev/openPDF && ./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest` → SUCCESS, 전부 PASS.
- [ ] **Step 4: 전체 계측** — 에뮬 부팅 후 `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest` → 전부 PASS(HWP smoke는 SKIP 허용). PDF 기존 계측 회귀 0 확인.
- [ ] **Step 5: 커밋**
```bash
cd /c/dev/openPDF && git rm app/src/main/java/io/github/june690602_blip/cleanpdf/doc/XmlFlowText.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/XmlFlowTextTest.kt && git add -A app/src/main/java/io/github/june690602_blip/cleanpdf/doc app/src/test/java/io/github/june690602_blip/cleanpdf/doc && git commit -m "refactor: 평탄 경로(XmlFlowText/toResultStrings) 제거 — 블록 전환 완료"
```

## Task E2: 실기 검증 + 문서
**Files:** Modify `CLAUDE.md`; Create `docs/superpowers/handoff/2026-06-08-cleanpdf-doctext-structured-handoff.md`

- [ ] **Step 1: 실기 3포맷** — `installDebug` 후, 표+이미지 있는 실제 .docx/.hwpx/.hwp를 기기(에뮬/실폰)로 열어(공유→CleanPDF 또는 VIEW 인텐트) **표가 `<table>`로, 이미지가 인라인으로** 보이는지 스크린샷. 손상/빈/미지원 에러도 확인.
- [ ] **Step 2: CLAUDE.md 갱신** — DocText 항목에 "표=진짜표·이미지 인라인(DocBlock 구조 모델), hwp는 hwplib 객체모델" 추가. 테스트 수 갱신.
- [ ] **Step 3: 핸드오프 작성** — 완료/결정(구조 블록, ImageFilter 캡, 이미지 등장순 페어링)/파일/미검증(binItemID 정밀 매핑, HWPX 실제 image attr명)/후속(헤딩·셀내 이미지·binItemID 정밀).
- [ ] **Step 4: 커밋**
```bash
cd /c/dev/openPDF && git add CLAUDE.md docs/superpowers/handoff/2026-06-08-cleanpdf-doctext-structured-handoff.md && git commit -m "docs: DocText 구조+이미지 완료 — CLAUDE.md + 핸드오프"
```

## Task E3: 브랜치 마무리
- [ ] superpowers:finishing-a-development-branch 로 병합/PR 결정.

---

## Self-Review (작성자 점검)
**1. 스펙 커버리지** — §5 모델=A1, §6.1 XmlBlocks=A3, §6.2 추출기=B2/C1/D1, §6.3 ImageFilter=A2, §7 DocHtml=A4, 모델전환=B1, 정리=E1, 검증/문서=E2. §3 결정(세 포맷 구조화·스타일 제외·래스터만·용량캡·인라인) 모두 태스크 매핑. 갭 없음.
**2. 플레이스홀더** — 모든 코드 스텝에 실제 코드. B2의 resolver는 잘못된 의사식(`return@parse_null`)을 명시적으로 정정 블록으로 교체해 둠(실행자는 정정형 사용).
**3. 타입 일관성** — `DocBlock.{Para(text)/Table(rows)/Image(mime,bytes)}`, `ImageFilter.classify→Outcome.{Ok(image)/Oversized/Unsupported}`, `XmlBlocks.parse(parser, resolveImage)`, `DocHtml.render(blocks)`/`toHtml(text)=render(text.blocks)`, `toResult(blocks)`/`toResultStrings(lines)`(E1서 제거), `HwpBlocks.build(hwp)`, `sectionIndex` 1곳 유지 — 태스크 간 일치.
**알려진 리스크(스펙 §11)**: HWPX 실제 이미지 attr/경로(`binaryItemIDRef`/href↔BinData)·HWP binItemID 정밀 매핑은 실기/실파일로 확정(테스트는 합성 fixture로 green). 등장순 이미지 페어링은 v1 best-effort.
