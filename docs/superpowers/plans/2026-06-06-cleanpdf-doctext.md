# CleanPDF DocText (한글·워드 텍스트 읽기) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PDF 코어를 건드리지 않고 `MainActivity`에 포맷 라우터를 끼워 DOCX·HWP·HWPX 문서를 별도 `DocTextActivity`(WebView)에서 텍스트로 읽게 한다.

**Architecture:** 인텐트/SAF/최근파일 → `MainActivity`가 `detectFormat`으로 분기 → PDF는 기존 경로, 문서는 cache 복사 후 `DocTextActivity`로 전달 → bg 스레드에서 포맷별 `DocTextExtractor`가 불변 `DocText` 추출 → `DocHtml`로 HTML화 → 오프라인 WebView(JS off)에서 표시(찾기·글자줌·선택복사). 순수 로직(감지/파싱/HTML)과 IO(추출/ZIP/probe)와 UI(액티비티)를 파일 경계로 분리.

**Tech Stack:** Kotlin, Android Views, WebView, `android.util.Xml`(XmlPullParser, 0 dep) for DOCX/HWPX, `kr.dogfoot:hwplib:1.1.10`(Apache-2.0) for HWP, JUnit4 + Robolectric(단위) + AndroidJUnit4(계측).

---

## 작업 규약 (실행자 필독)

- 브랜치 `feat/doctext-reader`(main `6c05712`에서 분기)에서 작업. main 직접 커밋 금지.
- 모든 명령은 프로젝트 루트에서. **Windows는 Bash 툴이 매 호출 cwd 리셋** → 명령마다 `cd /c/dev/openPDF &&` 선행. PowerShell이 필요하면 `$null`/`$env:`.
- 빌드: `./gradlew :app:assembleDebug` · 단위: `./gradlew :app:testDebugUnitTest --tests "<FQCN>"` · 계측(단일): `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.
- **계측 전체(`connectedDebugAndroidTest`)는 실행 후 앱 APK를 언인스톨** → 실기 수동검증은 그 전에(또는 `installDebug` 재설치 후).
- 에뮬 저장공간 부족: `adb -s emulator-5554 shell pm trim-caches 9999999999`.
- `LF→CRLF` git 경고는 무해, 무시.
- 신규 코드 패키지 루트: `io.github.june690602_blip.cleanpdf` (디렉터리 `app/src/main/java/io/github/june690602_blip/cleanpdf/`).

## 파일 구조 (생성/수정 맵)

**신규 — 순수(단위테스트):**
- `doc/DocFormat.kt` — `enum DocFormat` + `detectFormat(name, head)` + 매직 헬퍼. (스펙 §5.1)
- `doc/DocText.kt` — `DocText` 모델 + `ExtractResult` sealed.
- `doc/XmlFlowText.kt` — DOCX·HWPX 공용 XML→문단 파서. **스펙의 `DocxXml`+`HwpxXml`를 DRY로 통합**(둘 다 local name `p`/`t`/`tbl`/`tr`/`tc`로 동일하게 매칭되므로 하나로 충분).
- `doc/DocHtml.kt` — `DocText`→HTML(escape + `<pre>`).

**신규 — IO(계측/단위):**
- `doc/DocTextExtractor.kt` — 인터페이스 + `Extractors` 팩토리 + `toResult` 헬퍼.
- `doc/DocxExtractor.kt` — ZIP→`word/document.xml`→`XmlFlowText`.
- `doc/HwpxExtractor.kt` — ZIP→`Contents/section*.xml`(번호순)→`XmlFlowText` + `sectionIndex`.
- `doc/HwpExtractor.kt` — hwplib 위임.
- `doc/DocProbe.kt` — 확장자 없는 ZIP/OLE 컨테이너 정밀판정(IO).

**신규 — UI:**
- `DocTextActivity.kt` — file+format 수신 → 추출 → WebView. 찾기·에러.
- `res/layout/activity_doc_text.xml`, `res/menu/doc_menu.xml`.

**수정:**
- `io/PdfSource.kt` — `displayName`/`peekHead` 추가, `looksLikePdf` 제거(라우팅이 흡수), 기본 파일명 정리.
- `MainActivity.kt` — `loadFromUri` 라우팅, `route()`, `showRecent` 라우팅, SAF `*/*`, 메뉴 문자열.
- `store/RecentFilesStore.kt` — `RecentFile.format` 추가 + 직렬화 + 하위호환.
- `res/values/strings.xml` — 신규 문자열.
- `AndroidManifest.xml` — VIEW/SEND MIME 추가 + `DocTextActivity` 등록.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — hwplib 의존성.

---

# Phase A — 골격 + 라우팅 + DOCX 수직 슬라이스

## Task A1: DocFormat + detectFormat (순수)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocFormat.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocFormatTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// DocFormatTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Test

class DocFormatTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val pk = bytes(0x50, 0x4B, 0x03, 0x04)
    private val ole = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
    private val pdf = "%PDF-1.7".toByteArray(Charsets.US_ASCII)

    @Test fun byDocxExtension() = assertEquals(DocFormat.DOCX, detectFormat("a.DOCX", ByteArray(0)))
    @Test fun byHwpxExtension() = assertEquals(DocFormat.HWPX, detectFormat("a.hwpx", ByteArray(0)))
    @Test fun byHwpExtension() = assertEquals(DocFormat.HWP, detectFormat("a.hwp", ByteArray(0)))
    @Test fun byPdfExtensionOrMagic() {
        assertEquals(DocFormat.PDF, detectFormat("a.pdf", ByteArray(0)))
        assertEquals(DocFormat.PDF, detectFormat("blob", pdf))
    }
    @Test fun zipWithoutExtensionIsUnknown() = assertEquals(DocFormat.UNKNOWN, detectFormat("blob", pk))
    @Test fun oleWithoutExtensionIsUnknown() = assertEquals(DocFormat.UNKNOWN, detectFormat("blob", ole))
    @Test fun magicHelpers() {
        assertEquals(true, isZipMagic(pk)); assertEquals(true, isOleMagic(ole))
        assertEquals(false, isZipMagic(ole)); assertEquals(false, isOleMagic(pk))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.DocFormatTest"` → FAIL (unresolved `detectFormat`).

- [ ] **Step 3: 구현**

```kotlin
// DocFormat.kt
package io.github.june690602_blip.cleanpdf.doc

import io.github.june690602_blip.cleanpdf.pdf.isLikelyPdf

enum class DocFormat { PDF, DOCX, HWP, HWPX, UNKNOWN }

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private val OLE_MAGIC = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
)

/** Pure classify: 확장자 1차, PDF 매직 2차. 확장자 없는 ZIP/OLE는 UNKNOWN(→ DocProbe로 정밀판정). */
fun detectFormat(name: String?, head: ByteArray): DocFormat {
    val lower = name?.lowercase()
    when {
        lower == null -> {}
        lower.endsWith(".docx") -> return DocFormat.DOCX
        lower.endsWith(".hwpx") -> return DocFormat.HWPX
        lower.endsWith(".hwp") -> return DocFormat.HWP
    }
    if (isLikelyPdf(name, head)) return DocFormat.PDF
    return DocFormat.UNKNOWN
}

fun isZipMagic(head: ByteArray) = startsWith(head, ZIP_MAGIC)
fun isOleMagic(head: ByteArray) = startsWith(head, OLE_MAGIC)

private fun startsWith(head: ByteArray, magic: ByteArray): Boolean {
    if (head.size < magic.size) return false
    for (i in magic.indices) if (head[i] != magic[i]) return false
    return true
}
```

- [ ] **Step 4: 통과 확인** — Run: 위 동일 명령 → PASS (7 tests).

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocFormat.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocFormatTest.kt && git commit -m "feat: DocFormat 감지(확장자+매직, PDF는 isLikelyPdf 재사용)"
```

## Task A2: DocText 모델 + Extractor 인터페이스 + toResult

**Files:**
- Create: `doc/DocText.kt`, `doc/DocTextExtractor.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/doc/ToResultTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// ToResultTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToResultTest {
    @Test fun blankOnlyIsEmpty() {
        assertEquals(ExtractResult.Empty, toResult(listOf("", "   ", "\t")))
    }
    @Test fun emptyListIsEmpty() {
        assertEquals(ExtractResult.Empty, toResult(emptyList()))
    }
    @Test fun anyTextIsSuccess() {
        val r = toResult(listOf("", "본문"))
        assertTrue(r is ExtractResult.Success)
        assertEquals(listOf("", "본문"), (r as ExtractResult.Success).text.paragraphs)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.ToResultTest"` → FAIL.

- [ ] **Step 3: 구현**

```kotlin
// DocText.kt
package io.github.june690602_blip.cleanpdf.doc

/** 추출된 텍스트(불변). 표는 이미 평탄화되어 한 행이 한 문자열. */
data class DocText(val paragraphs: List<String>)

sealed interface ExtractResult {
    data class Success(val text: DocText) : ExtractResult
    data object Empty : ExtractResult
    data class Failure(val reason: String) : ExtractResult
}
```

```kotlin
// DocTextExtractor.kt
package io.github.june690602_blip.cleanpdf.doc

import java.io.File

interface DocTextExtractor {
    fun extract(file: File): ExtractResult
}

/** 문단들이 모두 공백이면 Empty, 아니면 Success. */
fun toResult(paragraphs: List<String>): ExtractResult =
    if (paragraphs.none { it.isNotBlank() }) ExtractResult.Empty
    else ExtractResult.Success(DocText(paragraphs))

object Extractors {
    /** 포맷→추출기. 포맷별 구현은 이후 Task 에서 연결(지금은 전부 null). */
    fun forFormat(format: DocFormat): DocTextExtractor? = null
}
```

- [ ] **Step 4: 통과 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.ToResultTest"` → PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocText.kt app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocTextExtractor.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/ToResultTest.kt && git commit -m "feat: DocText/ExtractResult 모델 + Extractor 인터페이스 + toResult"
```

## Task A3: XmlFlowText (DOCX·HWPX 공용 파서, 순수)

**Files:**
- Create: `doc/XmlFlowText.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/doc/XmlFlowTextTest.kt`

- [ ] **Step 1: 실패 테스트 작성** (Robolectric — `android.util.Xml` 사용)

```kotlin
// XmlFlowTextTest.kt
package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmlFlowTextTest {
    private fun parse(xml: String): List<String> {
        val p: XmlPullParser = Xml.newPullParser()
        p.setInput(StringReader(xml))
        return XmlFlowText.parse(p)
    }

    @Test fun paragraphsBecomeLines() {
        val out = parse(
            """<w:document xmlns:w="x"><w:body>
                 <w:p><w:r><w:t>첫 문단</w:t></w:r></w:p>
                 <w:p><w:r><w:t>둘째</w:t></w:r></w:p>
               </w:body></w:document>"""
        )
        assertEquals(listOf("첫 문단", "둘째"), out)
    }

    @Test fun runsConcatAndTabAndBreak() {
        val out = parse(
            """<w:p xmlns:w="x"><w:r><w:t>A</w:t></w:r><w:r><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>"""
        )
        assertEquals(listOf("A\tB\nC"), out)
    }

    @Test fun tableFlattensCellsTabRowNewline() {
        val out = parse(
            """<w:tbl xmlns:w="x">
                 <w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                       <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr>
                 <w:tr><w:tc><w:p><w:r><w:t>철근</w:t></w:r></w:p></w:tc>
                       <w:tc><w:p><w:r><w:t>1000</w:t></w:r></w:p></w:tc></w:tr>
               </w:tbl>"""
        )
        assertEquals(listOf("항목\t금액", "철근\t1000"), out)
    }

    @Test fun preservesPreservedSpacesNotIndentation() {
        // <w:t> 안의 공백은 보존, 태그 사이 들여쓰기 공백은 무시.
        val out = parse("""<w:p xmlns:w="x">
              <w:r><w:t>가 </w:t></w:r><w:r><w:t>나</w:t></w:r>
            </w:p>""")
        assertEquals(listOf("가 나"), out)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.XmlFlowTextTest"` → FAIL.

- [ ] **Step 3: 구현**

```kotlin
// XmlFlowText.kt
package io.github.june690602_blip.cleanpdf.doc

import org.xmlpull.v1.XmlPullParser

/**
 * DOCX(word/document.xml) · HWPX(Contents/section*.xml) 공용 본문 텍스트 추출(순수).
 * local name 기준 매칭이라 prefix(w:, hp:)와 무관:
 *  - <p> = 문단(표 밖이면 한 줄), <t> = 텍스트 런, <tab>=\t, <br>/<cr>=\n
 *  - <tbl>/<tr>/<tc> = 표 → 한 행이 한 줄, 셀은 \t 로 결합
 * 표 안 문단은 셀 텍스트로 합쳐지고, 중첩 표는 best-effort(텍스트 보존, 그룹핑 근사).
 */
object XmlFlowText {
    fun parse(parser: XmlPullParser): List<String> {
        val out = ArrayList<String>()
        val para = StringBuilder()
        val cell = StringBuilder()
        val rowCells = ArrayList<String>()
        var tableDepth = 0
        var inCell = false
        var inT = false

        fun local(n: String?): String = n?.substringAfterLast(':') ?: ""

        fun flushPara() {
            when {
                inCell -> { if (cell.isNotEmpty()) cell.append(' '); cell.append(para) }
                tableDepth == 0 -> out.add(para.toString())
            }
            para.setLength(0)
        }

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "p" -> para.setLength(0)
                    "t" -> inT = true
                    "tab" -> para.append('\t')
                    "br", "cr" -> para.append('\n')
                    "tbl" -> tableDepth++
                    "tc" -> if (tableDepth == 1) { inCell = true; cell.setLength(0) }
                }
                XmlPullParser.TEXT -> if (inT) para.append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "t" -> inT = false
                    "p" -> flushPara()
                    "tc" -> if (tableDepth == 1) { rowCells.add(cell.toString().trim()); inCell = false }
                    "tr" -> if (tableDepth == 1) { out.add(rowCells.joinToString("\t")); rowCells.clear() }
                    "tbl" -> if (tableDepth > 0) tableDepth--
                }
            }
            ev = parser.next()
        }
        return out
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: 위 동일 명령 → PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/XmlFlowText.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/XmlFlowTextTest.kt && git commit -m "feat: XmlFlowText — DOCX/HWPX 공용 본문·표 평탄화 파서"
```

## Task A4: DocHtml (순수)

**Files:**
- Create: `doc/DocHtml.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocHtmlTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// DocHtmlTest.kt
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocHtmlTest {
    @Test fun escapesDangerousChars() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", DocHtml.escape("<b> & \"x\" 'y'"))
    }
    @Test fun noScriptInjection() {
        val html = DocHtml.toHtml(DocText(listOf("<script>alert(1)</script>")))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test fun wrapsInPreAndKeepsLines() {
        val html = DocHtml.toHtml(DocText(listOf("줄1", "줄2")))
        assertTrue(html.contains("<pre"))
        assertTrue(html.contains("줄1\n줄2"))
        assertTrue(html.contains("charset=\"utf-8\""))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.DocHtmlTest"` → FAIL.

- [ ] **Step 3: 구현**

```kotlin
// DocHtml.kt
package io.github.june690602_blip.cleanpdf.doc

object DocHtml {
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** 문단을 줄바꿈으로 이어 <pre>(줄바꿈·탭 보존, 자동 줄나눔)로 감싼 오프라인 HTML. */
    fun toHtml(text: DocText): String {
        val body = text.paragraphs.joinToString("\n") { escape(it) }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
            "<body><pre style=\"white-space:pre-wrap; word-wrap:break-word; " +
            "font-family:sans-serif; font-size:1rem; line-height:1.5; margin:12px\">" +
            body + "</pre></body></html>"
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: 위 동일 명령 → PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocHtml.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocHtmlTest.kt && git commit -m "feat: DocHtml — DocText→오프라인 HTML(escape+<pre>)"
```

## Task A5: DocxExtractor (IO)

**Files:**
- Create: `doc/DocxExtractor.kt`
- Test: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocxExtractorSmokeTest.kt`

- [ ] **Step 1: 실패 테스트 작성** (계측 — 테스트 내에서 docx zip 생성)

```kotlin
// DocxExtractorSmokeTest.kt
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    @Test fun extractsParagraphsAndTable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "smoke.docx")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:x"><w:body>
              <w:p><w:r><w:t>계약서 본문</w:t></w:r></w:p>
              <w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>항목</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>금액</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl>
            </w:body></w:document>"""
        ZipOutputStream(docx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }

        val result = DocxExtractor.extract(docx)
        assertTrue("expected Success, got $result", result is ExtractResult.Success)
        val paras = (result as ExtractResult.Success).text.paragraphs
        assertTrue(paras.any { it.contains("계약서 본문") })
        assertTrue("table row flattened", paras.any { it == "항목\t금액" })
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.DocxExtractorSmokeTest` → FAIL (unresolved `DocxExtractor`). (에뮬 `emulator-5554` 실행 중이어야 함.)

- [ ] **Step 3: 구현**

```kotlin
// DocxExtractor.kt
package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile

/** DOCX = ZIP. word/document.xml 본문만 XmlFlowText 로 파싱. */
object DocxExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: return ExtractResult.Failure("no word/document.xml")
            zip.getInputStream(entry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null) // null = XML 선언에서 인코딩 자동감지
                toResult(XmlFlowText.parse(parser))
            }
        }
    }.getOrElse { ExtractResult.Failure(it.message ?: "docx parse error") }
}
```

그리고 `doc/DocTextExtractor.kt`의 `Extractors.forFormat` 를 교체(DOCX 연결):

```kotlin
    fun forFormat(format: DocFormat): DocTextExtractor? = when (format) {
        DocFormat.DOCX -> DocxExtractor
        else -> null
    }
```

- [ ] **Step 4: 통과 확인** — Run: 위 동일 계측 명령 → PASS. 그리고 A2 단위테스트도: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.ToResultTest"` → PASS.

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocxExtractor.kt app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocTextExtractor.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocxExtractorSmokeTest.kt && git commit -m "feat: DocxExtractor(ZIP→document.xml) + 팩토리 DOCX 연결 + 계측 스모크"
```

## Task A6: DocTextActivity + 레이아웃 + 문자열 (WebView 렌더)

**Files:**
- Create: `res/layout/activity_doc_text.xml`, `res/menu/doc_menu.xml`, `DocTextActivity.kt`
- Modify: `res/values/strings.xml`
- Test: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocTextActivitySmokeTest.kt`

- [ ] **Step 1: 문자열 추가** — `res/values/strings.xml`의 `</resources>` 직전에:

```xml
    <string name="open_file">파일 열기</string>
    <string name="error_unsupported">지원하지 않는 형식입니다</string>
    <string name="error_open_doc">이 파일은 텍스트로 열 수 없습니다</string>
    <string name="doc_empty">이 문서에는 읽을 텍스트가 없습니다</string>
    <string name="find">찾기</string>
```

- [ ] **Step 2: 레이아웃 생성** — `res/layout/activity_doc_text.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/doc_toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        android:theme="@style/ThemeOverlay.Material3.Dark.ActionBar" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <WebView
            android:id="@+id/doc_web"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <TextView
            android:id="@+id/doc_error"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:padding="24dp"
            android:textSize="16sp"
            android:visibility="gone" />

        <LinearLayout
            android:id="@+id/find_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:orientation="horizontal"
            android:background="?attr/colorPrimary"
            android:gravity="center_vertical"
            android:padding="4dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/find_position"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:paddingStart="12dp"
                android:textColor="#FFFFFFFF"
                android:textSize="14sp" />

            <Button android:id="@+id/find_prev_btn" style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/search_prev" android:textColor="#FFFFFFFF" />
            <Button android:id="@+id/find_next_btn" style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/search_next" android:textColor="#FFFFFFFF" />
            <Button android:id="@+id/find_close_btn" style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/search_close" android:textColor="#FFFFFFFF" />
        </LinearLayout>
    </FrameLayout>
</LinearLayout>
```

- [ ] **Step 3: 메뉴 생성** — `res/menu/doc_menu.xml`:

```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/action_doc_find"
        android:title="@string/find"
        android:showAsAction="never" />
</menu>
```

- [ ] **Step 4: 실패 테스트 작성** — `DocTextActivitySmokeTest.kt` (구성한 docx로 액티비티 기동, 크래시 없음 확인):

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocTextActivitySmokeTest {
    @Test fun launchesAndRendersDocx() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "activity_smoke.docx")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:x"><w:body>
              <w:p><w:r><w:t>액티비티 스모크</w:t></w:r></w:p>
            </w:body></w:document>"""
        ZipOutputStream(docx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val intent = DocTextActivity.intent(ctx, docx, DocFormat.DOCX, "activity_smoke.docx")
        ActivityScenario.launch<DocTextActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
```

- [ ] **Step 5: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.DocTextActivitySmokeTest` → FAIL (unresolved `DocTextActivity`). (`androidx.test.core.app.ActivityScenario`가 미해결이면 `androidTestImplementation("androidx.test:core-ktx:1.6.1")`를 build.gradle 에 추가.)

- [ ] **Step 6: 구현** — `DocTextActivity.kt` (찾기 로직은 Task D1에서 채움; 여기선 렌더+에러+WebView 설정):

```kotlin
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
        // R.id.action_doc_find -> Task D1 에서 채움
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() { super.onDestroy(); bg.shutdown() }
}
```

- [ ] **Step 7: 통과 확인** — Run: 위 동일 계측 명령 → PASS. 빌드도: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 8: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/DocTextActivity.kt app/src/main/res/layout/activity_doc_text.xml app/src/main/res/menu/doc_menu.xml app/src/main/res/values/strings.xml app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocTextActivitySmokeTest.kt && git commit -m "feat: DocTextActivity — WebView 렌더 + 에러화면(찾기는 D1)"
```

## Task A7: PdfSource peek/displayName + DocProbe (IO)

**Files:**
- Modify: `io/PdfSource.kt`
- Create: `doc/DocProbe.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocProbeTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `DocProbeTest.kt` (JVM, java.util.zip 로 실제 zip 생성):

```kotlin
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocProbeTest {
    private val zipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private fun zipWith(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("probe", ".zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(ZipEntry(n)); zos.write(c.toByteArray()); zos.closeEntry()
            }
        }
        return f
    }

    @Test fun zipWithDocumentXmlIsDocx() {
        val f = zipWith("word/document.xml" to "<x/>")
        assertEquals(DocFormat.DOCX, DocProbe.refine(f, zipHead))
    }
    @Test fun zipWithHwpMimetypeIsHwpx() {
        val f = zipWith("mimetype" to "application/hwp+zip")
        assertEquals(DocFormat.HWPX, DocProbe.refine(f, zipHead))
    }
    @Test fun zipWithSectionIsHwpx() {
        val f = zipWith("Contents/section0.xml" to "<x/>")
        assertEquals(DocFormat.HWPX, DocProbe.refine(f, zipHead))
    }
    @Test fun unknownZipIsUnknown() {
        val f = zipWith("random.txt" to "hi")
        assertEquals(DocFormat.UNKNOWN, DocProbe.refine(f, zipHead))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.DocProbeTest"` → FAIL.

- [ ] **Step 3: DocProbe 구현** — `doc/DocProbe.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.doc

import java.io.File
import java.util.zip.ZipFile

/** detect 가 UNKNOWN 인데 매직이 ZIP/OLE 면 컨테이너를 열어 정밀판정. */
object DocProbe {
    fun refine(file: File, head: ByteArray): DocFormat = when {
        isZipMagic(head) -> probeZip(file)
        isOleMagic(head) -> if (isHwpOle(file)) DocFormat.HWP else DocFormat.UNKNOWN
        else -> DocFormat.UNKNOWN
    }

    private fun probeZip(file: File): DocFormat = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            when {
                names.any { it == "word/document.xml" } -> DocFormat.DOCX
                names.any { it.startsWith("Contents/section") } -> DocFormat.HWPX
                zip.getEntry("mimetype")?.let { e ->
                    zip.getInputStream(e).bufferedReader().use { it.readText() }.trim()
                } == "application/hwp+zip" -> DocFormat.HWPX
                else -> DocFormat.UNKNOWN
            }
        }
    }.getOrDefault(DocFormat.UNKNOWN)

    /** OLE 헤더 앞부분에서 HWP v5 서명을 best-effort 스캔(확장자 없는 경우만 도달하는 드문 경로). */
    private fun isHwpOle(file: File): Boolean = runCatching {
        val buf = ByteArray(64 * 1024)
        val n = file.inputStream().use { it.read(buf) }
        val head = if (n > 0) buf.copyOf(n) else ByteArray(0)
        indexOf(head, "HWP Document File".toByteArray(Charsets.US_ASCII)) >= 0
    }.getOrDefault(false)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: 위 동일 명령 → PASS (4 tests).

- [ ] **Step 5: PdfSource 수정** — `io/PdfSource.kt`를 아래로 교체(공개 `displayName`/`peekHead` 추가, `looksLikePdf` 제거):

```kotlin
package io.github.june690602_blip.cleanpdf.io

import android.content.Context
import android.net.Uri
import java.io.File

/** content:// (또는 file://) 문서를 앱 캐시로 복사하고 이름/헤더를 들여다보는 헬퍼(포맷 중립). */
object PdfSource {
    fun copyToCache(context: Context, uri: Uri): File {
        val name = displayName(context, uri) ?: "document"
        val out = File(context.cacheDir, "opened_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open input stream for $uri" }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** 콘텐츠 표시 이름(.확장자 포함)을 조회. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 매직 판별용 선두 [n]바이트. 실패 시 빈 배열. */
    fun peekHead(context: Context, uri: Uri, n: Int = 16): ByteArray = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(n); val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        } ?: ByteArray(0)
    }.getOrDefault(ByteArray(0))
}
```

- [ ] **Step 6: 빌드 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug` → 컴파일 에러(아직 `MainActivity`가 `looksLikePdf` 참조). **이 단계에선 에러 정상** — Task A9에서 MainActivity를 고친 뒤 빌드가 통과한다. (원하면 A7~A9를 연속 진행.)

- [ ] **Step 7: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocProbe.kt app/src/main/java/io/github/june690602_blip/cleanpdf/io/PdfSource.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/DocProbeTest.kt && git commit -m "feat: DocProbe(컨테이너 정밀판정) + PdfSource displayName/peekHead(looksLikePdf 제거)"
```

## Task A8: RecentFile 에 format 추가 (하위호환)

**Files:**
- Modify: `store/RecentFilesStore.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleanpdf/store/RecentFilesLogicTest.kt` (추가 테스트)

- [ ] **Step 1: 실패 테스트 추가** — `RecentFilesLogicTest.kt`에 아래 테스트 추가(클래스 본문 안):

```kotlin
    @Test fun serializesAndReadsFormat() {
        val list = listOf(RecentFile("/p/a.docx", "a.docx", 1L, "DOCX"))
        val round = RecentFilesLogic.deserialize(RecentFilesLogic.serialize(list))
        org.junit.Assert.assertEquals("DOCX", round[0].format)
    }

    @Test fun legacyEntryWithoutFormatDefaultsToPdf() {
        // 구버전 JSON(키 f 없음) → format = PDF
        val legacy = "[{\"p\":\"/p/x.pdf\",\"n\":\"x.pdf\",\"t\":5}]"
        org.junit.Assert.assertEquals("PDF", RecentFilesLogic.deserialize(legacy)[0].format)
    }
```

> 참고: `RecentFilesLogicTest`는 org.json 때문에 Robolectric 으로 동작 중(기존 `@RunWith(RobolectricTestRunner::class)` 유지).

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.store.RecentFilesLogicTest"` → FAIL (RecentFile 에 format 없음).

- [ ] **Step 3: 구현** — `store/RecentFilesStore.kt`에서 `RecentFile`/직렬화/`add` 수정:

```kotlin
data class RecentFile(val path: String, val name: String, val ts: Long, val format: String = "PDF")
```

`RecentFilesLogic.serialize` 의 `put` 라인을 교체:

```kotlin
        list.forEach { arr.put(JSONObject().put("p", it.path).put("n", it.name).put("t", it.ts).put("f", it.format)) }
```

`RecentFilesLogic.deserialize` 의 매핑 라인을 교체:

```kotlin
            val o = arr.getJSONObject(i)
            RecentFile(o.getString("p"), o.getString("n"), o.getLong("t"), o.optString("f", "PDF"))
```

`RecentFilesStore.add` 시그니처/본문 교체:

```kotlin
    fun add(path: String, name: String, format: String = "PDF") {
        val next = RecentFilesLogic.add(list(), RecentFile(path, name, System.currentTimeMillis(), format), max)
        prefs.edit().putString("items", RecentFilesLogic.serialize(next)).apply()
    }
```

- [ ] **Step 4: 통과 확인** — Run: 위 동일 명령 → PASS (기존 + 신규 2).

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/store/RecentFilesStore.kt app/src/test/java/io/github/june690602_blip/cleanpdf/store/RecentFilesLogicTest.kt && git commit -m "feat: RecentFile.format 추가(직렬화+PDF 하위호환)"
```

## Task A9: MainActivity 라우팅 + 매니페스트 + DocTextActivity 등록

**Files:**
- Modify: `MainActivity.kt`, `AndroidManifest.xml`
- Test: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocRoutingSmokeTest.kt`

- [ ] **Step 1: 매니페스트 수정** — `AndroidManifest.xml`의 VIEW 필터 `<data>`들 아래에 doc MIME 추가, SEND 필터에도 동일 추가, 그리고 `</activity>` 뒤에 DocTextActivity 등록. VIEW 필터 mimeType 블록을 아래로 교체:

```xml
                <data android:mimeType="application/pdf" />
                <data android:mimeType="application/x-pdf" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
                <data android:mimeType="application/x-hwp" />
                <data android:mimeType="application/haansofthwp" />
                <data android:mimeType="application/vnd.hancom.hwp" />
                <data android:mimeType="application/hwp+zip" />
                <data android:mimeType="application/vnd.hancom.hwpx" />
                <data android:mimeType="application/zip" />
                <data android:mimeType="application/octet-stream" />
```

SEND 필터 mimeType 블록도 동일하게 교체(같은 9개 + octet-stream). 그리고 MainActivity `</activity>` 다음에:

```xml
        <activity
            android:name=".DocTextActivity"
            android:exported="false" />
```

- [ ] **Step 2: 실패 테스트 작성** — `DocRoutingSmokeTest.kt` (octet-stream 처럼 확장자만 가진 파일이 DocTextActivity 로 가는지 detectFormat 단으로 검증 + 라우팅 헬퍼):

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import io.github.june690602_blip.cleanpdf.doc.detectFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocRoutingSmokeTest {
    @Test fun docxNameRoutesToDoc() =
        assertEquals(DocFormat.DOCX, detectFormat("받은파일.docx", byteArrayOf(0x50, 0x4B, 0x03, 0x04)))

    @Test fun pdfStillRoutesToPdf() =
        assertEquals(DocFormat.PDF, detectFormat("계약서.pdf", ByteArray(0)))
}
```

> (실제 인텐트→액티비티 해석은 빌드 후 `adb shell cmd package query-activities`로 수동검증 — Step 6.)

- [ ] **Step 3: MainActivity 라우팅 구현** — 아래를 반영:

3a. import 추가(파일 상단 import 영역):

```kotlin
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import io.github.june690602_blip.cleanpdf.doc.DocProbe
import io.github.june690602_blip.cleanpdf.doc.detectFormat
```

3b. `loadFromUri` 교체:

```kotlin
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

    /** 포맷별 화면으로 보냄. PDF 는 기존 경로(bg), 문서는 DocTextActivity(UI). */
    private fun route(format: DocFormat, file: File, name: String) {
        when (format) {
            DocFormat.PDF -> openFile(file)
            DocFormat.DOCX, DocFormat.HWP, DocFormat.HWPX ->
                runOnUiThread { startActivity(DocTextActivity.intent(this, file, format, name)) }
            DocFormat.UNKNOWN -> runOnUiThread { showError(getString(R.string.error_unsupported)) }
        }
    }
```

3c. `showDocument`의 `recents.add(file.absolutePath, file.name)` 를 교체(PDF 타입 명시):

```kotlin
        recents.add(file.absolutePath, file.name, DocFormat.PDF.name)
```

3d. SAF 열기 — `R.id.action_open` 핸들러의 `openDoc.launch(arrayOf("application/pdf"))` 를 교체:

```kotlin
        R.id.action_open -> { openDoc.launch(arrayOf("*/*")); true }
```

3e. 메뉴 라벨 — `res/menu/reader_menu.xml`의 `action_open` title 을 `@string/open_file` 로 교체:

```xml
        android:title="@string/open_file"
```

3f. `showRecent` 의 항목 클릭 분기 교체(타입으로 라우팅):

```kotlin
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
```

- [ ] **Step 4: 빌드 + 통과 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (A7의 미해결 참조 해소됨).
Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.DocRoutingSmokeTest` → PASS.

- [ ] **Step 5: 전체 단위테스트 회귀 확인**

Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest` → 전부 PASS(기존 58 + 신규 단위들). 실패 시 그 테스트만 보고 수정.

- [ ] **Step 6: 수동검증(인텐트 해석 + DOCX end-to-end)**

```bash
cd /c/dev/openPDF && ./gradlew :app:installDebug
# octet-stream VIEW 가 우리 앱으로 해석되는지
~/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 shell cmd package query-activities \
  -a android.intent.action.VIEW -t application/octet-stream | grep -i cleanpdf
```
그리고 A5에서 만든 docx 를 기기로 밀어 VIEW 인텐트로 열어 텍스트가 보이는지 확인(또는 SAF "파일 열기"로 선택). 스크린샷 1장 저장.

- [ ] **Step 7: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/menu/reader_menu.xml app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocRoutingSmokeTest.kt && git commit -m "feat: MainActivity 포맷 라우팅 + 매니페스트 doc MIME + DocTextActivity 등록"
```

> **Phase A 종료 = DOCX 수직 슬라이스 동작.** 인입/SAF/최근파일에서 .docx 가 DocTextActivity 로 열려 텍스트·표가 보인다.

---

# Phase B — HWPX 추출

## Task B1: sectionIndex + HwpxExtractor + 팩토리 연결

**Files:**
- Modify: `doc/DocTextExtractor.kt` (팩토리에 HWPX 추가), `doc/HwpxExtractor.kt`(생성)
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/doc/SectionIndexTest.kt`(JVM), `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/HwpxExtractorSmokeTest.kt`(계측)

- [ ] **Step 1: 실패 단위테스트(sectionIndex 정렬)** — `SectionIndexTest.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.doc

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionIndexTest {
    @Test fun parsesNumber() {
        assertEquals(0, sectionIndex("Contents/section0.xml"))
        assertEquals(12, sectionIndex("Contents/section12.xml"))
    }
    @Test fun sortsNumericallyNotLexically() {
        val names = listOf("Contents/section10.xml", "Contents/section2.xml", "Contents/section1.xml")
        assertEquals(
            listOf("Contents/section1.xml", "Contents/section2.xml", "Contents/section10.xml"),
            names.sortedBy { sectionIndex(it) }
        )
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleanpdf.doc.SectionIndexTest"` → FAIL.

- [ ] **Step 3: 실패 계측테스트(HwpxExtractor)** — `HwpxExtractorSmokeTest.kt` (테스트 내 hwpx zip 생성: section1 다음 section0 순서로 넣어 정렬 확인):

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpxExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class HwpxExtractorSmokeTest {
    private fun section(text: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <hs:sec xmlns:hs="urn:s" xmlns:hp="urn:p">
             <hp:p><hp:run><hp:t>$text</hp:t></hp:run></hp:p>
           </hs:sec>"""

    @Test fun extractsSectionsInOrder() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hwpx = File(ctx.cacheDir, "smoke.hwpx")
        ZipOutputStream(hwpx.outputStream()).use { zos ->
            // 일부러 역순으로 넣음 → 추출기가 번호순 정렬해야 함
            zos.putNextEntry(ZipEntry("Contents/section1.xml")); zos.write(section("둘째장").toByteArray(Charsets.UTF_8)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("Contents/section0.xml")); zos.write(section("첫장").toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val result = HwpxExtractor.extract(hwpx)
        assertTrue(result is ExtractResult.Success)
        val paras = (result as ExtractResult.Success).text.paragraphs
        assertEquals(listOf("첫장", "둘째장"), paras)
    }
}
```

- [ ] **Step 4: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.HwpxExtractorSmokeTest` → FAIL.

- [ ] **Step 5: 구현** — `doc/HwpxExtractor.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile

/** HWPX = ZIP. Contents/section*.xml 을 번호순 누적 파싱(공용 XmlFlowText). */
object HwpxExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        val all = ArrayList<String>()
        ZipFile(file).use { zip ->
            val sections = zip.entries().asSequence()
                .filter { it.name.matches(Regex("Contents/section\\d+\\.xml")) }
                .sortedBy { sectionIndex(it.name) }
                .toList()
            if (sections.isEmpty()) return ExtractResult.Failure("no section xml")
            for (entry in sections) {
                zip.getInputStream(entry).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, null)
                    all.addAll(XmlFlowText.parse(parser))
                }
            }
        }
        toResult(all)
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwpx parse error") }
}

/** "Contents/section12.xml" → 12. 못 찾으면 0. */
fun sectionIndex(name: String): Int =
    Regex("section(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
```

그리고 `doc/DocTextExtractor.kt`의 `Extractors.forFormat` 에 HWPX 추가:

```kotlin
    fun forFormat(format: DocFormat): DocTextExtractor? = when (format) {
        DocFormat.DOCX -> DocxExtractor
        DocFormat.HWPX -> HwpxExtractor
        else -> null
    }
```

- [ ] **Step 6: 통과 확인** — Run: Step 2 와 Step 4 명령 둘 다 → PASS.

- [ ] **Step 7: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/HwpxExtractor.kt app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocTextExtractor.kt app/src/test/java/io/github/june690602_blip/cleanpdf/doc/SectionIndexTest.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/HwpxExtractorSmokeTest.kt && git commit -m "feat: HwpxExtractor(Contents/section*.xml 번호순) + 팩토리 연결"
```

> **수동검증(권장)**: 한글에서 저장한 실제 `.hwpx`를 SAF/인텐트로 열어 한글 텍스트가 보이는지 확인. element 이름이 가정과 다르면(드묾) 텍스트가 비어 보일 수 있음 → 그 경우 실제 section0.xml 을 `adb pull` 로 꺼내 element 명 확인 후 `XmlFlowText` local-name 매칭 보강.

---

# Phase C — HWP 추출 (hwplib)

## Task C1: hwplib 의존성 추가

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [ ] **Step 1: 버전 카탈로그에 추가** — `gradle/libs.versions.toml`:

`[versions]`에 추가:
```toml
hwplib = "1.1.10"
```
`[libraries]`에 추가:
```toml
hwplib = { group = "kr.dogfoot", name = "hwplib", version.ref = "hwplib" }
```

- [ ] **Step 2: build.gradle 에 의존성** — `app/build.gradle.kts`의 `dependencies {}` 안 `implementation(libs.mupdf.fitz)` 아래에:

```kotlin
    implementation(libs.hwplib)
```

- [ ] **Step 3: 해상도 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (kr.dogfoot:hwplib:1.1.10 이 mavenCentral 에서 받아짐). 실패 시 네트워크/좌표 확인.

- [ ] **Step 4: 커밋**

```bash
cd /c/dev/openPDF && git add gradle/libs.versions.toml app/build.gradle.kts && git commit -m "build: hwplib 1.1.10(Apache-2.0) 의존성 추가"
```

## Task C2: HwpExtractor + 팩토리 연결

**Files:**
- Create: `doc/HwpExtractor.kt`
- Modify: `doc/DocTextExtractor.kt`
- Test: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/HwpExtractorSmokeTest.kt`
- 픽스처: `app/src/androidTest/assets/sample.hwp` (작은 실제 HWP v5 파일 — PoC 때 쓴 파일 권장)

- [ ] **Step 1: 픽스처 배치** — 작은 실제 `.hwp`(한글 v5, 본문에 식별 가능한 한글 한 줄 포함)를 `app/src/androidTest/assets/sample.hwp` 로 둔다. (없으면 한글에서 새 문서에 "테스트 본문"을 넣고 .hwp 로 저장.)

- [ ] **Step 2: 실패 테스트 작성** — `HwpExtractorSmokeTest.kt` (계측 컨텍스트의 assets 사용 → 앱에 미동봉):

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.ExtractResult
import io.github.june690602_blip.cleanpdf.doc.HwpExtractor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HwpExtractorSmokeTest {
    @Test fun extractsKoreanText() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val out = File(inst.targetContext.cacheDir, "sample.hwp")
        inst.context.assets.open("sample.hwp").use { i -> out.outputStream().use { i.copyTo(it) } }

        val result = HwpExtractor.extract(out)
        assertTrue("expected Success, got $result", result is ExtractResult.Success)
        val text = (result as ExtractResult.Success).text.paragraphs.joinToString("\n")
        assertTrue("should contain Korean text", text.any { it in '가'..'힣' })
    }
}
```

- [ ] **Step 3: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.HwpExtractorSmokeTest` → FAIL (unresolved `HwpExtractor`).

- [ ] **Step 4: 구현** — `doc/HwpExtractor.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.doc

import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor
import java.io.File

/** HWP v5 바이너리 = hwplib 위임. 문단 사이 컨트롤(표 등) 텍스트까지 포함. */
object HwpExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        val hwp = file.inputStream().use { HWPReader.fromInputStream(it) }
        val raw = TextExtractor.extract(hwp, TextExtractMethod.InsertControlTextBetweenParagraphText)
        toResult(raw.split('\n'))
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwp parse error") }
}
```

그리고 `Extractors.forFormat` 에 HWP 추가:

```kotlin
    fun forFormat(format: DocFormat): DocTextExtractor? = when (format) {
        DocFormat.DOCX -> DocxExtractor
        DocFormat.HWPX -> HwpxExtractor
        DocFormat.HWP -> HwpExtractor
        else -> null
    }
```

- [ ] **Step 5: 통과 확인** — Run: Step 3 명령 → PASS. (API 시그니처가 다르면 hwplib 1.1.10 의 `HWPReader`/`TextExtractor`/`TextExtractMethod` 패키지를 `./gradlew :app:dependencies` + IDE 로 확인 후 import 보정 — PoC 검증된 API라 일치 예상.)

- [ ] **Step 6: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/doc/HwpExtractor.kt app/src/main/java/io/github/june690602_blip/cleanpdf/doc/DocTextExtractor.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/HwpExtractorSmokeTest.kt && git commit -m "feat: HwpExtractor(hwplib) + 팩토리 연결"
```

> **Phase C 종료 = 세 포맷 모두 텍스트 추출.**

---

# Phase D — 뷰어 크롬 (찾기) + 글자줌 검증

## Task D1: WebView 찾기바

**Files:**
- Modify: `DocTextActivity.kt`
- Test: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocFindSmokeTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `DocFindSmokeTest.kt` (findAllAsync 콜백 카운트>0):

```kotlin
package io.github.june690602_blip.cleanpdf

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.doc.DocFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocFindSmokeTest {
    @Test fun findReportsMatches() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val docx = File(ctx.cacheDir, "find.docx")
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="urn:x"><w:body>
              <w:p><w:r><w:t>금액 금액 금액</w:t></w:r></w:p>
            </w:body></w:document>"""
        ZipOutputStream(docx.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8)); zos.closeEntry()
        }
        val intent = DocTextActivity.intent(ctx, docx, DocFormat.DOCX, "find.docx")
        var count = 0
        val latch = CountDownLatch(1)
        ActivityScenario.launch<DocTextActivity>(intent).use { scenario ->
            Thread.sleep(800) // WebView 로드 대기
            scenario.onActivity { act ->
                val web = act.findViewById<WebView>(R.id.doc_web)
                web.setFindListener { _, c, done -> if (done) { count = c; latch.countDown() } }
                web.findAllAsync("금액")
            }
            assertTrue("find callback fired", latch.await(5, TimeUnit.SECONDS))
            assertTrue("expected >=3 matches, got $count", count >= 3)
        }
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.DocFindSmokeTest` → 현재도 통과할 수 있음(WebView 기본 findAllAsync). 핵심은 사용자용 찾기 UI 배선 → 아래 구현 후 수동검증으로 확정.

- [ ] **Step 3: 구현** — `DocTextActivity.kt`에 찾기 상태/메서드 추가하고 메뉴 분기를 연결.

3a. 클래스 필드 추가(`format` 아래):

```kotlin
    private var findResultCount = 0
```

3b. `onCreate`의 `configureWebView(web)` 다음에 찾기바 버튼 배선 추가:

```kotlin
        findViewById<android.widget.Button>(R.id.find_prev_btn).setOnClickListener { web.findNext(false) }
        findViewById<android.widget.Button>(R.id.find_next_btn).setOnClickListener { web.findNext(true) }
        findViewById<android.widget.Button>(R.id.find_close_btn).setOnClickListener { closeFind() }
        web.setFindListener { activeOrdinal, count, isDoneCounting ->
            if (isDoneCounting) {
                findResultCount = count
                findPosition.text = if (count == 0) getString(R.string.search_none)
                    else getString(R.string.search_position, activeOrdinal + 1, count)
            }
        }
```

3c. 메뉴 분기 활성화 — `onOptionsItemSelected`의 주석 라인을 교체:

```kotlin
        R.id.action_doc_find -> { promptFind(); true }
```

3d. 메서드 추가(`onDestroy` 위):

```kotlin
    private fun promptFind() {
        val input = android.widget.EditText(this).apply { hint = getString(R.string.search_hint) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.find)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val q = input.text.toString()
                if (q.isNotEmpty()) {
                    findBar.visibility = android.view.View.VISIBLE
                    web.findAllAsync(q)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun closeFind() {
        web.clearMatches()
        findBar.visibility = android.view.View.GONE
    }
```

- [ ] **Step 4: 통과 확인 + 빌드** — Run: Step 2 계측 명령 → PASS. Run: `cd /c/dev/openPDF && ./gradlew :app:assembleDebug` → SUCCESS.

- [ ] **Step 5: 수동검증(찾기 + 글자줌)**

```bash
cd /c/dev/openPDF && ./gradlew :app:installDebug
```
DOCX 를 열고 ① 메뉴 "찾기"→단어 입력→하단바에 "n/전체" 표시·◀▶ 이동, ② **두 손가락 핀치로 글자 확대/축소**(builtInZoomControls), ③ 길게 눌러 텍스트 선택→복사 확인. 스크린샷 1장.

- [ ] **Step 6: 커밋**

```bash
cd /c/dev/openPDF && git add app/src/main/java/io/github/june690602_blip/cleanpdf/DocTextActivity.kt app/src/androidTest/java/io/github/june690602_blip/cleanpdf/DocFindSmokeTest.kt && git commit -m "feat: DocTextActivity 찾기바(findAllAsync) + 핀치 글자줌 검증"
```

---

# Phase E — 마감

## Task E1: 전체 검증 + 증거 수집

- [ ] **Step 1: 전체 단위테스트** — Run: `cd /c/dev/openPDF && ./gradlew :app:testDebugUnitTest` → 전부 PASS. 통과 수 기록.

- [ ] **Step 2: 전체 계측테스트(마지막에)** — Run: `cd /c/dev/openPDF && ./gradlew :app:connectedDebugAndroidTest` → 전부 PASS. (실행 후 앱이 언인스톨되니 이후 수동검증은 `installDebug` 후.)

- [ ] **Step 3: 실기 3포맷 수동검증** — `installDebug` 후, 가능하면 실제 .docx/.hwp/.hwpx 를 카톡/SAF로 열어 텍스트·표·찾기·줌·복사 확인. 손상 파일/빈 문서/미지원(.doc)에서 친절한 에러 확인. 스크린샷 3장.

## Task E2: 문서화 + 라이선스 메모

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/superpowers/handoff/2026-06-06-cleanpdf-doctext-handoff.md`

- [ ] **Step 1: CLAUDE.md 갱신** — Status 섹션에 "DocText(한글·워드 텍스트 읽기) 완료" 추가: 지원 포맷(DOCX/HWP/HWPX), 별도 DocTextActivity(WebView, JS off), 라우팅(MainActivity detectFormat), hwplib 의존성(Apache-2.0, AGPL 호환), 테스트 수, 미지원(.doc/.hwp v3). 스택 섹션에 hwplib + WebView 한 줄.

- [ ] **Step 2: 핸드오프 작성** — 완료 상태/핵심 결정(공용 XmlFlowText, OLE 매직 충돌 처리, 재추출 무캐시)/파일 목록/불변조건 준수(PDF 코어 무접촉)/후속(OSS 고지 화면은 출시 Phase 7에서 hwplib 포함, "다른 앱으로 열기" 폴백 보류)을 기록.

- [ ] **Step 3: 라이선스 메모** — 출시(Phase 7) 시 앱 내 OSS 고지에 **hwplib (Apache-2.0)** 추가해야 함을 핸드오프 "후속"에 명시(현재 OSS 화면 미존재라 코드 작업은 Phase 7).

- [ ] **Step 4: 커밋**

```bash
cd /c/dev/openPDF && git add CLAUDE.md docs/superpowers/handoff/2026-06-06-cleanpdf-doctext-handoff.md && git commit -m "docs: DocText 완료 — CLAUDE.md 갱신 + 핸드오프"
```

## Task E3: 브랜치 마무리

- [ ] **Step 1**: `superpowers:finishing-a-development-branch` 스킬로 병합/PR 여부를 사용자와 결정. (원격 없으면 로컬 main 병합 또는 브랜치 유지.)

---

## Self-Review (작성자 점검 결과)

**1. 스펙 커버리지** — §5 컴포넌트 전부 Task 매핑됨(DocFormat=A1, DocText/Extractor=A2, XmlFlowText[=DocxXml+HwpxXml 통합]=A3, DocHtml=A4, DocxExtractor=A5, DocTextActivity=A6, DocProbe/PdfSource=A7, RecentFile.format=A8, MainActivity/Manifest=A9, HwpxExtractor=B1, hwplib=C1, HwpExtractor=C2, 찾기=D1, 글자줌=A6 설정+D1 검증). §6 감지(A1+A7), §7 추출(A5/B1/C2), §8 뷰어(A6/D1), §9 에러(A6 Empty/Failure + A9 UNKNOWN), §10 의존성/라이선스(C1+E2), §11 테스트(각 Task), §13 비범위 준수. **갭 없음.**

**2. 플레이스홀더 스캔** — 모든 코드 스텝에 실제 코드. "TODO/적절히/유사하게" 없음. (D1 메뉴 분기는 A6에서 의도적으로 주석 placeholder 로 두고 D1에서 교체하도록 명시 — 기능 누락 아님.)

**3. 타입 일관성** — `detectFormat(name,head)`, `DocText(paragraphs)`, `ExtractResult.{Success(text)/Empty/Failure(reason)}`, `DocTextExtractor.extract(file)`, `Extractors.forFormat`, `toResult`, `XmlFlowText.parse(parser)`, `DocHtml.toHtml/escape`, `DocProbe.refine(file,head)`, `PdfSource.displayName/peekHead/copyToCache`, `RecentFile(path,name,ts,format)`, `RecentFilesStore.add(path,name,format)`, `DocTextActivity.intent(ctx,file,format,name)` — Task 간 시그니처 일치 확인.

**알려진 리스크(스펙 §12 반영)**: HWPX element 명(hp:p/hp:t) 가정 — 합성 픽스처로 단위는 통과하지만 실제 .hwpx 는 B1 수동검증으로 확정. hwplib API 명 — PoC 검증됨, C2에서 실패 시 import 보정.
