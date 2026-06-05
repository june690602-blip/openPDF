# Phase 5 — Text Selection & Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Long-press a word in a PDF page to select it, drag start/end handles to adjust the range, and copy the selected text to the clipboard.

**Architecture:** A **pure-model engine** — on long-press, fitz parses the page once (`Page.toStructuredText().getBlocks()`) into an immutable, android-free `PageText` (chars with Unicode codepoint + bbox in PDF points). All selection math (hit-test, word-snap, highlight rects, copied string, handle points) is **pure Kotlin over `PageText`** (JVM-unit-tested, no render-thread round-trips during drag). Selection is stored in PDF points and re-projected to cell pixels in `onBindViewHolder`, so it follows zoom exactly like search highlights. A `SelectionOverlayView` (sibling of `HighlightOverlayView`) paints the selection + two handles; a bottom `selection_bar` (mirroring `search_bar`) triggers copy/close. Scope is **single-page** (no cross-page selection).

**Tech Stack:** Kotlin, Android Views, MuPDF `fitz` 1.27.1 (`StructuredText`/`TextBlock`/`TextLine`/`TextChar`/`Quad`), JUnit (JVM unit), AndroidJUnit4 + Espresso (instrumented).

---

## Invariants this plan must respect (from Phase 1 handoff §2)

1. **Single render thread** — all fitz access (`toStructuredText`, `getBlocks`) goes through `PageRenderer`'s single executor (`extractTextBlocking`). `PageText` is android/fitz-free and crosses to the UI thread by value.
2. **Overlay, not bitmap** — selection paints in a sibling overlay `View`; it never touches the page bitmap or the `BitmapCache` (no cache-key pollution, no `recycle`).
3. **Apply selection BEFORE the cache-hit early-return in `onBindViewHolder`** — the same lesson as search highlights (Phase 4.5 §2): a cached page must still get its overlay.
4. **Zoom follows for free** — selection is stored in PDF points; `commitZoom → relayout → notifyDataSetChanged → onBind` re-projects with the new `contentWidth`.
5. **Coordinate space** — fitz page space is **y-down, top-left origin**; render `Matrix(scale)` is the same space; `scale = contentWidth / pageWidthPts`; **no y-flip** (Phase 4.5 §4, device-confirmed).

---

## File Structure

**New — pure logic (JVM-unit-tested, android-free):**
- `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageText.kt` — immutable `PageChar` + `PageText` model.
- `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt` — pure selection math (`object`).
- `app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometry.kt` — pure PDF-point ↔ cell-pixel transforms (incl. the inverse search lacked).

**New — UI (device-smoke verified):**
- `app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionOverlayView.kt` — paints selection rects + 2 handles.

**Modified:**
- `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfDocument.kt` — `extractText(index): PageText`.
- `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageRenderer.kt` — `extractTextBlocking(page): PageText`.
- `app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfPageAdapter.kt` — third overlay view + `setSelection(...)`.
- `app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt` — long-press, handle-drag interception, `setSelection`/`clearSelection`, callbacks.
- `app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt` — wiring (begin/apply/drag/copy/close), clear-on-open.
- `app/src/main/res/layout/activity_main.xml` — bottom `selection_bar`.
- `app/src/main/res/values/strings.xml` — selection strings.

**New — tests:**
- `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt` — pure model + selection math.
- `app/src/test/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometryTest.kt` — pure transforms.
- `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextExtractionSmokeTest.kt` — fitz→`PageText` extraction (deterministic).
- `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextSelectionSmokeTest.kt` — long-press → bar visible (UI path).

**No change:** `res/menu/reader_menu.xml` (selection is gesture-initiated; no menu item).

---

## Task 1: `PageText` model + `TextSelection.nearestCharIndex`

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageText.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSelectionTest {

    /** "Hi" on line 0, "there" on line 0 after a space; bboxes are simple 12-tall glyphs. */
    private fun sample(): PageText = PageText(
        pageIndex = 0,
        chars = listOf(
            PageChar('H'.code, 0f, 0f, 10f, 12f, 0),
            PageChar('i'.code, 10f, 0f, 16f, 12f, 0),
            PageChar(' '.code, 16f, 0f, 20f, 12f, 0),
            PageChar('t'.code, 20f, 0f, 28f, 12f, 0),
            PageChar('h'.code, 28f, 0f, 36f, 12f, 0),
            PageChar('e'.code, 36f, 0f, 44f, 12f, 0),
            PageChar('r'.code, 44f, 0f, 50f, 12f, 0),
            PageChar('e'.code, 50f, 0f, 58f, 12f, 0),
        ),
    )

    @Test fun nearestPicksClosestCharByCenter() {
        val p = sample()
        assertEquals(0, TextSelection.nearestCharIndex(p, 5f, 6f))   // over 'H'
        assertEquals(3, TextSelection.nearestCharIndex(p, 24f, 6f))  // over 't'
    }

    @Test fun nearestOnEmptyPageIsMinusOne() {
        assertEquals(-1, TextSelection.nearestCharIndex(PageText(0, emptyList()), 5f, 6f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: FAIL — unresolved references `PageText`, `PageChar`, `TextSelection`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageText.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/**
 * One laid-out character on a page: its Unicode [codepoint], bounding box in PDF points
 * (y-down, top-left origin — same space as [SearchHit]), and the 0-based [lineIndex] of the text
 * line it belongs to (lines are in fitz reading order). Immutable, android-free.
 */
data class PageChar(
    val codepoint: Int,
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val lineIndex: Int,
)

/**
 * Immutable text content of one page: [chars] flattened in reading order. Holds no android/fitz
 * types, so the selection logic over it is JVM-unit-testable.
 */
data class PageText(val pageIndex: Int, val chars: List<PageChar>) {
    val isEmpty: Boolean get() = chars.isEmpty()
}
```

Create `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/**
 * Pure selection math over a [PageText]. No android/fitz types → JVM-unit-testable.
 * A selection is an inclusive index range [start..end] (start <= end) into [PageText.chars].
 */
object TextSelection {

    /**
     * Index of the char nearest to PDF point ([x],[y]); -1 if the page has no text.
     * Distance is measured to the char's bbox center; ties break to the lower index.
     */
    fun nearestCharIndex(page: PageText, x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in page.chars.indices) {
            val c = page.chars[i]
            val cx = (c.x0 + c.x1) * 0.5f
            val cy = (c.y0 + c.y1) * 0.5f
            val dx = x - cx
            val dy = y - cy
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageText.kt \
        app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt \
        app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt
git commit -m "feat: PageText model + nearestCharIndex (pure)"
```

---

## Task 2: `TextSelection.wordRangeAt` (snap-to-word for long-press)

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt`

- [ ] **Step 1: Write the failing test** — append to `TextSelectionTest`:

```kotlin
    @Test fun wordRangeSnapsToSurroundingNonWhitespaceRun() {
        val p = sample()
        assertEquals(0..1, TextSelection.wordRangeAt(p, 5f, 6f))   // "Hi"
        assertEquals(3..7, TextSelection.wordRangeAt(p, 40f, 6f))  // "there"
    }

    @Test fun wordRangeOnWhitespaceSelectsThatChar() {
        assertEquals(2..2, TextSelection.wordRangeAt(sample(), 18f, 6f)) // the space
    }

    @Test fun wordRangeOnEmptyPageIsNull() {
        assertNull(TextSelection.wordRangeAt(PageText(0, emptyList()), 0f, 0f))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: FAIL — unresolved reference `wordRangeAt`.

- [ ] **Step 3: Write minimal implementation** — add to `TextSelection`:

```kotlin
    /**
     * The word (a run of non-whitespace chars on a single line) at PDF point ([x],[y]); null if the
     * page has no text. If the nearest char is itself whitespace, selects just that char.
     */
    fun wordRangeAt(page: PageText, x: Float, y: Float): IntRange? {
        val i = nearestCharIndex(page, x, y)
        if (i < 0) return null
        val chars = page.chars
        if (isWhitespace(chars[i].codepoint)) return i..i
        val line = chars[i].lineIndex
        var s = i
        while (s - 1 >= 0 && chars[s - 1].lineIndex == line && !isWhitespace(chars[s - 1].codepoint)) s--
        var e = i
        while (e + 1 < chars.size && chars[e + 1].lineIndex == line && !isWhitespace(chars[e + 1].codepoint)) e++
        return s..e
    }

    private fun isWhitespace(cp: Int): Boolean = Character.isWhitespace(cp)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt \
        app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt
git commit -m "feat: wordRangeAt snap-to-word (pure)"
```

---

## Task 3: `TextSelection.selectionRects` (one merged rect per line)

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt`

- [ ] **Step 1: Write the failing test** — append:

```kotlin
    /** "AB" on line 0, "C" on line 1. */
    private fun twoLines(): PageText = PageText(
        pageIndex = 0,
        chars = listOf(
            PageChar('A'.code, 0f, 0f, 10f, 12f, 0),
            PageChar('B'.code, 10f, 0f, 20f, 12f, 0),
            PageChar('C'.code, 0f, 20f, 10f, 32f, 1),
        ),
    )

    @Test fun selectionRectsMergePerLine() {
        val rects = TextSelection.selectionRects(twoLines(), 0..2)
        assertEquals(2, rects.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 20f, 12f), rects[0], 0.001f)
        assertArrayEquals(floatArrayOf(0f, 20f, 10f, 32f), rects[1], 0.001f)
    }

    @Test fun selectionRectsOnEmptyPageIsEmpty() {
        assertEquals(0, TextSelection.selectionRects(PageText(0, emptyList()), 0..0).size)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: FAIL — unresolved reference `selectionRects`.

- [ ] **Step 3: Write minimal implementation** — add to `TextSelection`:

```kotlin
    /** One merged rect per text line covered by [range], as FloatArray[x0,y0,x1,y1] in PDF points. */
    fun selectionRects(page: PageText, range: IntRange): List<FloatArray> {
        if (page.isEmpty) return emptyList()
        val lo = range.first.coerceIn(0, page.chars.size - 1)
        val hi = range.last.coerceIn(0, page.chars.size - 1)
        val out = ArrayList<FloatArray>()
        var i = lo
        while (i <= hi) {
            val line = page.chars[i].lineIndex
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            while (i <= hi && page.chars[i].lineIndex == line) {
                val c = page.chars[i]
                if (c.x0 < x0) x0 = c.x0
                if (c.y0 < y0) y0 = c.y0
                if (c.x1 > x1) x1 = c.x1
                if (c.y1 > y1) y1 = c.y1
                i++
            }
            out.add(floatArrayOf(x0, y0, x1, y1))
        }
        return out
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt \
        app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt
git commit -m "feat: selectionRects per-line merge (pure)"
```

---

## Task 4: `TextSelection.selectedText` (copy string with line breaks)

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt`

- [ ] **Step 1: Write the failing test** — append:

```kotlin
    @Test fun selectedTextJoinsCharsAndBreaksLines() {
        assertEquals("AB\nC", TextSelection.selectedText(twoLines(), 0..2))
        assertEquals("B\nC", TextSelection.selectedText(twoLines(), 1..2))
    }

    @Test fun selectedTextOnEmptyPageIsBlank() {
        assertEquals("", TextSelection.selectedText(PageText(0, emptyList()), 0..0))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: FAIL — unresolved reference `selectedText`.

- [ ] **Step 3: Write minimal implementation** — add to `TextSelection`:

```kotlin
    /** The selected text for [range]: chars concatenated in order, '\n' inserted at each line break. */
    fun selectedText(page: PageText, range: IntRange): String {
        if (page.isEmpty) return ""
        val lo = range.first.coerceIn(0, page.chars.size - 1)
        val hi = range.last.coerceIn(0, page.chars.size - 1)
        val sb = StringBuilder()
        var prevLine = page.chars[lo].lineIndex
        for (i in lo..hi) {
            val c = page.chars[i]
            if (c.lineIndex != prevLine) { sb.append('\n'); prevLine = c.lineIndex }
            sb.appendCodePoint(c.codepoint)
        }
        return sb.toString()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt \
        app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt
git commit -m "feat: selectedText with line breaks (pure)"
```

---

## Task 5: `TextSelection.handlePoints` (start/end handle anchors)

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt`

- [ ] **Step 1: Write the failing test** — append:

```kotlin
    @Test fun handlePointsAreFirstBottomLeftAndLastBottomRight() {
        val (s, e) = TextSelection.handlePoints(twoLines(), 0..2)!!
        assertArrayEquals(floatArrayOf(0f, 12f), s, 0.001f)   // 'A' bottom-left
        assertArrayEquals(floatArrayOf(10f, 32f), e, 0.001f)  // 'C' bottom-right
    }

    @Test fun handlePointsOnEmptyPageIsNull() {
        assertNull(TextSelection.handlePoints(PageText(0, emptyList()), 0..0))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: FAIL — unresolved reference `handlePoints`.

- [ ] **Step 3: Write minimal implementation** — add to `TextSelection`:

```kotlin
    /**
     * Start (bottom-left of the first selected char) and end (bottom-right of the last selected char)
     * handle anchor points, each as FloatArray[x,y] in PDF points; null if the page has no text.
     */
    fun handlePoints(page: PageText, range: IntRange): Pair<FloatArray, FloatArray>? {
        if (page.isEmpty) return null
        val lo = range.first.coerceIn(0, page.chars.size - 1)
        val hi = range.last.coerceIn(0, page.chars.size - 1)
        val a = page.chars[lo]
        val b = page.chars[hi]
        return floatArrayOf(a.x0, a.y1) to floatArrayOf(b.x1, b.y1)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TextSelectionTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/TextSelection.kt \
        app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/TextSelectionTest.kt
git commit -m "feat: handlePoints start/end anchors (pure)"
```

---

## Task 6: `SelectionGeometry` (PDF-point ↔ cell-pixel, incl. inverse)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometry.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometryTest.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGeometryTest {

    @Test fun scaleIsContentWidthOverPageWidth() {
        assertEquals(3f, SelectionGeometry.scale(100f, 300f), 0.001f)
        assertEquals(0f, SelectionGeometry.scale(0f, 300f), 0.001f) // guard div-by-zero
    }

    @Test fun pixelToPdfIsInverseOfScale() {
        assertArrayEquals(floatArrayOf(10f, 20f), SelectionGeometry.toPdfPoint(30f, 60f, 3f), 0.001f)
    }

    @Test fun rectAndPointToPixelsApplyScale() {
        assertArrayEquals(
            floatArrayOf(30f, 60f, 90f, 120f),
            SelectionGeometry.rectToPixels(floatArrayOf(10f, 20f, 30f, 40f), 3f), 0.001f,
        )
        assertArrayEquals(floatArrayOf(30f, 60f), SelectionGeometry.pointToPixels(10f, 20f, 3f), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SelectionGeometryTest"`
Expected: FAIL — unresolved reference `SelectionGeometry`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometry.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.view

/**
 * Pure PDF-point ↔ cell-pixel transforms for text selection. Mirrors [HighlightGeometry] but adds
 * the inverse (pixel→point) needed to map touches back to PDF space. android-free → JVM-testable.
 * scale = contentWidth / pageWidthPts (x and y share it; the cell keeps the page aspect ratio).
 */
object SelectionGeometry {
    fun scale(pageWidthPts: Float, contentWidth: Float): Float =
        if (pageWidthPts <= 0f) 0f else contentWidth / pageWidthPts

    /** Cell pixel ([px],[py]) → PDF point, as FloatArray[xPt, yPt]. */
    fun toPdfPoint(px: Float, py: Float, scale: Float): FloatArray =
        if (scale <= 0f) floatArrayOf(0f, 0f) else floatArrayOf(px / scale, py / scale)

    /** PDF-point rect[x0,y0,x1,y1] → cell-pixel rect[x0,y0,x1,y1]. */
    fun rectToPixels(rectPts: FloatArray, scale: Float): FloatArray =
        floatArrayOf(rectPts[0] * scale, rectPts[1] * scale, rectPts[2] * scale, rectPts[3] * scale)

    /** PDF point ([xPt],[yPt]) → cell-pixel point, as FloatArray[x, y]. */
    fun pointToPixels(xPt: Float, yPt: Float, scale: Float): FloatArray =
        floatArrayOf(xPt * scale, yPt * scale)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SelectionGeometryTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometry.kt \
        app/src/test/java/io/github/june690602_blip/cleanpdf/view/SelectionGeometryTest.kt
git commit -m "feat: SelectionGeometry transforms incl. inverse (pure)"
```

---

## Task 7: fitz → `PageText` extraction + render-thread wrapper + smoke

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfDocument.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageRenderer.kt`
- Test: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextExtractionSmokeTest.kt`

- [ ] **Step 1: Write the failing instrumented test**

Create `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextExtractionSmokeTest.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.pdf.TextSelection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextExtractionSmokeTest {
    @Test fun extractsTextFromSample() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "extract_sample.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val doc = PdfDocument.open(out.absolutePath)
        val text = doc.extractText(0)
        doc.close()

        assertTrue("page 0 should yield chars", text.chars.isNotEmpty())
        val all = TextSelection.selectedText(text, 0..(text.chars.size - 1))
        assertTrue("extracted text should contain 'Page'", all.contains("Page"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:assembleDebugAndroidTest`
Expected: FAIL to compile — unresolved reference `extractText`.

- [ ] **Step 3: Write minimal implementation**

In `PdfDocument.kt`, add this method (after `search(...)`, before `close()`). The fitz imports (`StructuredText`) are already present; `getBlocks()` returns `StructuredText.TextBlock[]` (`.lines` may be null for image blocks — guard):

```kotlin
    /**
     * Extract laid-out text for [index] as a [PageText] — chars in fitz reading order, bboxes in
     * PDF points (y-down). Empty if the page has no extractable text (e.g. a scanned image).
     * MUST be called on the render thread (it touches the fitz Document).
     */
    fun extractText(index: Int): PageText {
        val page = doc.loadPage(index)
        val stext = page.toStructuredText()
        val chars = ArrayList<PageChar>()
        var lineIndex = 0
        for (block in stext.blocks) {
            val lines = block.lines ?: continue
            for (line in lines) {
                val cs = line.chars ?: continue
                for (ch in cs) {
                    val r = ch.quad.toRect()
                    chars.add(PageChar(ch.c, r.x0, r.y0, r.x1, r.y1, lineIndex))
                }
                lineIndex++
            }
        }
        stext.destroy()
        page.destroy()
        return PageText(index, chars)
    }
```

In `PageRenderer.kt`, add (after `searchBlocking(...)`):

```kotlin
    /** Extract a page's text on the render thread (fitz access is single-threaded). Blocking. */
    fun extractTextBlocking(page: Int): PageText =
        exec.submit<PageText> { doc.extractText(page) }.get()
```

- [ ] **Step 4: Run the smoke test to verify it passes**

Ensure an emulator is running (`emulator-5554`). If storage is low: `adb -s emulator-5554 shell pm trim-caches 9999999999`.
Run: `./gradlew :app:connectedDebugAndroidTest --tests "*.TextExtractionSmokeTest"`
Expected: PASS — extraction yields chars and the text contains "Page".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfDocument.kt \
        app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageRenderer.kt \
        app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextExtractionSmokeTest.kt
git commit -m "feat: fitz StructuredText -> PageText extraction + render-thread wrapper"
```

---

## Task 8: `SelectionOverlayView` (paints rects + 2 handles)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionOverlayView.kt`

- [ ] **Step 1: Write the implementation** (presentational `View`, no PDF state — device-smoke verified later via Task 15; no JVM test because it draws on a `Canvas`)

Create `app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionOverlayView.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View

/**
 * Transparent overlay that paints the text selection over a page cell: translucent rects plus two
 * round drag handles (start/end). Purely presentational; holds no PDF state. Sibling of
 * [HighlightOverlayView] (search highlights), kept separate so the two never fight over one view.
 */
class SelectionOverlayView(context: Context) : View(context) {
    private val fill = Paint().apply { color = Color.argb(80, 33, 150, 243); isAntiAlias = true }
    private val handlePaint = Paint().apply { color = Color.argb(255, 33, 150, 243); isAntiAlias = true }
    private val handleRadius = resources.displayMetrics.density * 8f

    private var rects: List<RectF> = emptyList()
    private var startHandle: PointF? = null
    private var endHandle: PointF? = null

    /** [rects] and handle points are in this view's pixel space (same size as the page cell). */
    fun setSelection(rects: List<RectF>, start: PointF?, end: PointF?) {
        this.rects = rects
        this.startHandle = start
        this.endHandle = end
        invalidate()
    }

    fun clear() {
        if (rects.isEmpty() && startHandle == null && endHandle == null) return
        rects = emptyList(); startHandle = null; endHandle = null; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (r in rects) canvas.drawRect(r, fill)
        startHandle?.let { canvas.drawCircle(it.x, it.y, handleRadius, handlePaint) }
        endHandle?.let { canvas.drawCircle(it.x, it.y, handleRadius, handlePaint) }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/view/SelectionOverlayView.kt
git commit -m "feat: SelectionOverlayView (rects + handles)"
```

---

## Task 9: `PdfPageAdapter` — add the selection overlay to each cell

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfPageAdapter.kt`

**Context:** the cell `FrameLayout` currently holds `image` then `overlay` (search highlights). Add a third child `selectionOverlay` on top. Store selection state in PDF points and project it in `onBind` (before the cache-hit return, per invariant #3), exactly like search highlights.

- [ ] **Step 1: Add imports** — at the top of the file, add:

```kotlin
import android.graphics.PointF
```

(`RectF` is already imported.)

- [ ] **Step 2: Add selection state + setter** — after the `activeHit` field and `setHighlights(...)`:

```kotlin
    // Selection (single page). Rects + handle points are stored in PDF points and projected to cell
    // pixels in onBind, so they follow zoom like search highlights. selPage = -1 means "no selection".
    private var selPage: Int = -1
    private var selRectsPts: List<FloatArray> = emptyList()
    private var selStartPt: FloatArray? = null
    private var selEndPt: FloatArray? = null

    /** Set the active text selection on [page] (rects + handle anchors in PDF points). */
    fun setSelection(page: Int, rectsPts: List<FloatArray>, startPt: FloatArray?, endPt: FloatArray?) {
        selPage = page; selRectsPts = rectsPts; selStartPt = startPt; selEndPt = endPt
        notifyDataSetChanged()
    }

    /** Clear any text selection overlay. */
    fun clearSelection() {
        if (selPage == -1) return
        selPage = -1; selRectsPts = emptyList(); selStartPt = null; selEndPt = null
        notifyDataSetChanged()
    }
```

- [ ] **Step 3: Create the overlay view in `onCreateViewHolder`** — replace the body so a `SelectionOverlayView` is added above the highlight overlay, and pass it to the holder:

```kotlin
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val frame = FrameLayout(parent.context)
        val iv = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val overlay = HighlightOverlayView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val selectionOverlay = SelectionOverlayView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(iv)
        frame.addView(overlay)           // search highlights, above the page bitmap
        frame.addView(selectionOverlay)  // text selection, above the highlights
        return PageVH(frame, iv, overlay, selectionOverlay)
    }
```

- [ ] **Step 4: Project the selection in `onBindViewHolder`** — immediately after the existing search-highlight block (still BEFORE the cache-hit early return), add:

```kotlin
        // Text selection for this page — also applied BEFORE the cache-hit early return so a cached
        // page keeps its selection overlay. PDF points → cell pixels via SelectionGeometry.
        if (position == selPage && selRectsPts.isNotEmpty()) {
            val pw = pageSizeProvider(position).width
            val s = SelectionGeometry.scale(pw, l.contentWidth)
            val selRects = selRectsPts.map {
                val q = SelectionGeometry.rectToPixels(it, s)
                RectF(q[0], q[1], q[2], q[3])
            }
            val start = selStartPt?.let { SelectionGeometry.pointToPixels(it[0], it[1], s).let { p -> PointF(p[0], p[1]) } }
            val end = selEndPt?.let { SelectionGeometry.pointToPixels(it[0], it[1], s).let { p -> PointF(p[0], p[1]) } }
            holder.selectionOverlay.setSelection(selRects, start, end)
        } else {
            holder.selectionOverlay.clear()
        }
```

- [ ] **Step 5: Extend `PageVH`** — replace the class with:

```kotlin
    class PageVH(
        itemView: FrameLayout,
        val image: ImageView,
        val overlay: HighlightOverlayView,
        val selectionOverlay: SelectionOverlayView,
    ) : RecyclerView.ViewHolder(itemView) {
        var pending: Future<*>? = null
    }
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfPageAdapter.kt
git commit -m "feat: selection overlay in PdfPageAdapter (PDF-point projection)"
```

---

## Task 10: `PdfReaderView` — long-press → PDF point, `setSelection`/`clearSelection`

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt`

- [ ] **Step 1: Add the long-press callback field** — near `onPageChanged`:

```kotlin
    /** Invoked when the user long-presses on a page: (0-based page, x/y in PDF points). */
    var onLongPressPdf: ((page: Int, xPt: Float, yPt: Float) -> Unit)? = null
```

- [ ] **Step 2: Detect long-press in the tap detector** — add `onLongPress` to the existing `tapDetector` listener (alongside `onDoubleTap`):

```kotlin
            override fun onLongPress(e: MotionEvent) {
                val child = findChildViewUnder(e.x, e.y) ?: return
                val page = getChildAdapterPosition(child)
                if (page == NO_POSITION) return
                val cw = lastLayout?.contentWidth ?: return
                val s = SelectionGeometry.scale(sizes[page].width, cw)
                val pdf = SelectionGeometry.toPdfPoint(e.x - child.left, e.y - child.top, s)
                onLongPressPdf?.invoke(page, pdf[0], pdf[1])
            }
```

- [ ] **Step 3: Add selection forwarding + mirrored handle state** — after `clearSearchHighlights()`:

```kotlin
    // Active selection mirror (PDF points) — kept so handle hit-testing can locate the handles
    // without reaching into the adapter. selPage = -1 means "no selection".
    private var selPage: Int = -1
    private var selStartPt: FloatArray? = null
    private var selEndPt: FloatArray? = null

    /** Show a text selection on [page]: rects + handle anchors in PDF points. */
    fun setSelection(page: Int, rectsPts: List<FloatArray>, startPt: FloatArray?, endPt: FloatArray?) {
        selPage = page; selStartPt = startPt; selEndPt = endPt
        adapterImpl?.setSelection(page, rectsPts, startPt, endPt)
    }

    /** Remove the text selection overlay and stop treating handles as grabbable. */
    fun clearSelection() {
        selPage = -1; selStartPt = null; selEndPt = null
        adapterImpl?.clearSelection()
    }
```

- [ ] **Step 4: Clear selection when a new document is attached** — at the start of `setDocument(...)`, before assigning the renderer, add:

```kotlin
        clearSelection()
```

- [ ] **Step 5: Verify it compiles** (`SelectionGeometry` is in the same `view` package — no import needed)

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt
git commit -m "feat: PdfReaderView long-press -> PDF point + setSelection/clearSelection"
```

---

## Task 11: `PdfReaderView` — handle-drag gesture interception

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt`

**Context:** when a selection is active and the user touches down within grab radius of a handle, we steal the gesture (so the list does not scroll) and report drag points back as PDF points. The owner decides which char the handle snaps to.

- [ ] **Step 1: Add the drag callback + drag state** — near `onLongPressPdf`:

```kotlin
    /** Invoked while dragging a selection handle: (page, x/y in PDF points, isStart handle). */
    var onSelectionDragPdf: ((page: Int, xPt: Float, yPt: Float, isStart: Boolean) -> Unit)? = null

    private enum class Handle { START, END }
    private var draggingHandle: Handle? = null
    private val grabRadiusPx = resources.displayMetrics.density * 24f
```

- [ ] **Step 2: Add handle hit-testing** — as a private method:

```kotlin
    private fun handleUnder(x: Float, y: Float): Handle? {
        val page = selPage
        if (page < 0) return null
        val vh = findViewHolderForAdapterPosition(page) ?: return null
        val cell = vh.itemView
        val cw = lastLayout?.contentWidth ?: return null
        val s = SelectionGeometry.scale(sizes[page].width, cw)
        selStartPt?.let {
            val p = SelectionGeometry.pointToPixels(it[0], it[1], s)
            if (dist(x, y, cell.left + p[0], cell.top + p[1]) <= grabRadiusPx) return Handle.START
        }
        selEndPt?.let {
            val p = SelectionGeometry.pointToPixels(it[0], it[1], s)
            if (dist(x, y, cell.left + p[0], cell.top + p[1]) <= grabRadiusPx) return Handle.END
        }
        return null
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
```

- [ ] **Step 3: Intercept the gesture on a handle grab** — override `onInterceptTouchEvent`:

```kotlin
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && selPage >= 0) {
            val h = handleUnder(e.x, e.y)
            if (h != null) { draggingHandle = h; return true }
        }
        return super.onInterceptTouchEvent(e)
    }
```

- [ ] **Step 4: Drive the drag in `onTouchEvent`** — replace the existing `onTouchEvent` with a version that handles an in-progress handle drag first:

```kotlin
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = draggingHandle
        if (h != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val page = selPage
                    val vh = findViewHolderForAdapterPosition(page)
                    val cw = lastLayout?.contentWidth
                    if (vh != null && cw != null) {
                        val cell = vh.itemView
                        val s = SelectionGeometry.scale(sizes[page].width, cw)
                        val pdf = SelectionGeometry.toPdfPoint(e.x - cell.left, e.y - cell.top, s)
                        onSelectionDragPdf?.invoke(page, pdf[0], pdf[1], h == Handle.START)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> draggingHandle = null
            }
            return true
        }
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        return super.onTouchEvent(e) || scaleDetector.isInProgress
    }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt
git commit -m "feat: PdfReaderView selection handle-drag interception"
```

---

## Task 12: `selection_bar` layout + strings

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the selection bar** — inside the `FrameLayout` (sibling of `search_bar`, also bottom-anchored, initially gone). Insert just before the closing `</FrameLayout>`:

```xml
        <LinearLayout
            android:id="@+id/selection_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom"
            android:orientation="horizontal"
            android:background="?attr/colorPrimary"
            android:gravity="center_vertical"
            android:padding="4dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/selection_info"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:paddingStart="12dp"
                android:textColor="#FFFFFFFF"
                android:textSize="14sp" />

            <Button
                android:id="@+id/selection_copy_btn"
                style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/selection_copy"
                android:textColor="#FFFFFFFF" />

            <Button
                android:id="@+id/selection_close_btn"
                style="?attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/selection_close"
                android:textColor="#FFFFFFFF" />
        </LinearLayout>
```

- [ ] **Step 2: Add the strings** — append inside `<resources>` of `app/src/main/res/values/strings.xml`:

```xml
    <string name="selection_copy">복사</string>
    <string name="selection_close">닫기</string>
    <string name="selection_copied">복사됨</string>
    <string name="selection_chars">%d자 선택</string>
    <string name="selection_none">선택할 텍스트가 없습니다</string>
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml
git commit -m "feat: selection_bar layout + strings"
```

---

## Task 13: `MainActivity` — begin selection, apply, copy, close

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt`

**Context:** mirror the search wiring (`openSearch`/`stepSearch`/`applyCursor`/`closeSearch`). Selection state lives in the Activity; pure math is delegated to `TextSelection`.

- [ ] **Step 1: Add imports** — with the other imports:

```kotlin
import android.content.ClipData
import android.content.ClipboardManager
import io.github.june690602_blip.cleanpdf.pdf.PageText
import io.github.june690602_blip.cleanpdf.pdf.TextSelection
```

- [ ] **Step 2: Add selection fields** — near `cursor`/`searchBar`:

```kotlin
    private var selText: PageText? = null
    private var selRange: IntRange? = null
    private lateinit var selectionBar: android.view.View
    private lateinit var selectionInfo: android.widget.TextView
```

- [ ] **Step 3: Wire views + callbacks in `onCreate`** — after the search button wiring:

```kotlin
        selectionBar = findViewById(R.id.selection_bar)
        selectionInfo = findViewById(R.id.selection_info)
        findViewById<android.widget.Button>(R.id.selection_copy_btn).setOnClickListener { copySelection() }
        findViewById<android.widget.Button>(R.id.selection_close_btn).setOnClickListener { clearSelection() }
        reader.onLongPressPdf = { page, x, y -> beginSelection(page, x, y) }
        reader.onSelectionDragPdf = { page, x, y, isStart -> dragSelection(page, x, y, isStart) }
```

- [ ] **Step 4: Add the selection methods** — after `closeSearch()`:

```kotlin
    /** Long-press: extract the page's text off the UI thread, then select the word under the finger. */
    private fun beginSelection(page: Int, xPt: Float, yPt: Float) {
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
```

- [ ] **Step 5: Add the drag handler stub** — add this method now (filled the same way as Task 14 expects); it keeps Task 13 self-contained and compilable:

```kotlin
    /** Drag a handle: snap it to the char under the finger, clamping so start <= end. */
    private fun dragSelection(page: Int, xPt: Float, yPt: Float, isStart: Boolean) {
        val text = selText ?: return
        val range = selRange ?: return
        if (text.pageIndex != page) return
        val idx = TextSelection.nearestCharIndex(text, xPt, yPt)
        if (idx < 0) return
        selRange = if (isStart) minOf(idx, range.last)..range.last else range.first..maxOf(idx, range.first)
        applySelection()
    }
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt
git commit -m "feat: MainActivity text-selection wiring (begin/apply/drag/copy/close)"
```

---

## Task 14: `MainActivity` — clear selection on new document

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt`

**Context:** when a new PDF loads, any selection refers to the old document's text. The reader already calls `clearSelection()` in `setDocument` (Task 10 §4), but the Activity still holds `selText`/`selRange` and the visible bar. Reset them too.

- [ ] **Step 1: Clear Activity-side selection in `showDocument`** — inside the `runOnUiThread { ... }` block, after `reader.setDocument(r, sizes)`:

```kotlin
                selText = null; selRange = null
                selectionBar.visibility = android.view.View.GONE
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full unit suite (regression)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — 43 prior + 14 new (TextSelection 11 + SelectionGeometry 3) = **57 unit tests**, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt
git commit -m "feat: reset selection state on new document"
```

---

## Task 15: Instrumented UI smoke — long-press shows the selection bar

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextSelectionSmokeTest.kt`

**Context:** drives the real gesture path with Espresso `longClick()` on the reader, then asserts the selection bar becomes visible. The bundled `sample.pdf` has text ("CleanPDF - Page N"); if a center long-press lands on whitespace the bar won't show — adjust the tap location during Task 16 device verification if needed (note left in the test).

- [ ] **Step 1: Write the instrumented test**

Create `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextSelectionSmokeTest.kt`:

```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSelectionSmokeTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test fun longPressShowsSelectionBar() {
        Thread.sleep(2000) // let the bundled sample render
        onView(withId(R.id.reader)).perform(longClick())
        Thread.sleep(1000) // text extraction + apply
        onView(withId(R.id.selection_bar)).check(matches(isDisplayed()))
    }
}
```

- [ ] **Step 2: Run the smoke test**

Ensure `emulator-5554` is up; if storage low: `adb -s emulator-5554 shell pm trim-caches 9999999999`.
Run: `./gradlew :app:connectedDebugAndroidTest --tests "*.TextSelectionSmokeTest"`
Expected: PASS — selection bar displayed after long-press. (If it fails because the center is blank, see Task 16 — adjust to long-press where the sample has text, e.g. via a `GeneralClickAction` at a specific coordinate, then re-run.)

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/io/github/june690602_blip/cleanpdf/TextSelectionSmokeTest.kt
git commit -m "test: instrumented long-press -> selection bar smoke"
```

---

## Task 16: Device verification + docs + final evidence

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/superpowers/handoff/2026-06-06-cleanpdf-phase5-handoff.md`

- [ ] **Step 1: Install and manually verify on the emulator**

```bash
./gradlew :app:installDebug
adb -s emulator-5554 shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity
```

Verify (capture a screenshot with `adb -s emulator-5554 exec-out screencap -p > selection_evidence.png` for each):
1. **Long-press a word** → it highlights (translucent blue) with two round handles, and the bottom bar shows "N자 선택" + 복사 + 닫기.
2. **Drag the end handle** right/down → the highlight grows; the count updates. Drag the start handle → the start moves. (Confirms gesture interception steals from scroll.)
3. **복사** → toast "복사됨"; paste into another app (or check via `adb -s emulator-5554 shell` logclip) shows the selected text.
4. **닫기** → highlight + handles + bar disappear.
5. **Zoom in (double-tap)** while selected → the highlight + handles stay aligned to the same glyphs (PDF-point projection follows zoom).
6. **Long-press on a blank area** → toast "선택할 텍스트가 없습니다", no bar.

If the UI smoke (Task 15) needed a coordinate tweak, apply it now and re-run `connectedDebugAndroidTest --tests "*.TextSelectionSmokeTest"`.

- [ ] **Step 2: Run the full test suites for final evidence**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Expected: unit **57/57**, instrumented **8/8** (6 prior + TextExtraction + TextSelection), 0 failures. Record the exact counts.

- [ ] **Step 3: Update `CLAUDE.md`** — bump the Status section to Phase 5 complete: move "텍스트 선택·복사" from 진행 중 to 작동 중, summarizing the pure-model engine (`PageText`/`TextSelection`/`SelectionGeometry`), the overlay + bottom bar, single-page scope, and the new test counts. Note the date 2026-06-06 and the branch `feat/phase5-text-selection`.

- [ ] **Step 4: Write the Phase 5 handoff** — create `docs/superpowers/handoff/2026-06-06-cleanpdf-phase5-handoff.md` covering: what shipped, the pure-model decision + why (responsiveness, JVM-testable, WYSIWYG), files added/changed, the gesture-interception approach (and any device quirks found), invariants respected, and follow-ups (cross-page selection deferred; CJK "word" = whitespace-run run; handle drawable could become teardrops; next = Phase 6 dark/night-invert).

- [ ] **Step 5: Final commit**

```bash
git add CLAUDE.md docs/superpowers/handoff/2026-06-06-cleanpdf-phase5-handoff.md
git commit -m "docs: Phase 5 text selection complete; handoff + CLAUDE.md"
```

---

## Self-Review

**1. Spec coverage** (design spec §5.4 + §4 `TextSelectionController`):
- "롱프레스로 선택" → Task 2 (`wordRangeAt`) + Task 10 (long-press) + Task 13 (`beginSelection`). ✓
- "핸들 드래그로 범위 조절" → Task 5 (`handlePoints`) + Task 11 (handle-drag) + Task 13/14 (`dragSelection`). ✓
- "클립보드 복사" → Task 4 (`selectedText`) + Task 13 (`copySelection`). ✓
- `TextSelectionController` responsibility (롱프레스→StructuredText 선택영역→복사) → realized as `TextSelection` (pure) + extraction (Task 7) + Activity wiring (Task 13), following the established "fold controller into Activity" pattern from search (Phase 4.5). ✓
- Reuse of `HighlightOverlayView`/`HighlightGeometry` coordinate pattern → `SelectionOverlayView`/`SelectionGeometry` mirror it; overlay-not-bitmap + apply-before-cache-hit invariants honored. ✓

**2. Placeholder scan:** No "TBD"/"add error handling"/"similar to Task N" — every code step shows complete code. Empty-page/whitespace/blank-area cases are handled explicitly (`nearestCharIndex == -1`, `wordRangeAt == null`, `selection_none` toast). ✓

**3. Type consistency:** `PageText(pageIndex, chars)`, `PageChar(codepoint,x0,y0,x1,y1,lineIndex)`, ranges as `IntRange`, rects/points as `FloatArray`, `Pair<FloatArray,FloatArray>` for handles — used identically across Tasks 1–14. Methods: `nearestCharIndex`, `wordRangeAt`, `selectionRects`, `selectedText`, `handlePoints`; `SelectionGeometry.scale/toPdfPoint/rectToPixels/pointToPixels`; reader `setSelection(page,rectsPts,startPt,endPt)`/`clearSelection`/`onLongPressPdf`/`onSelectionDragPdf`; adapter `setSelection`/`clearSelection`. Consistent. ✓

**Test counts:** unit 43→57 (+11 TextSelection, +3 SelectionGeometry); instrumented 6→8 (+TextExtraction, +TextSelection).
