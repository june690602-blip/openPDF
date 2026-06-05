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

    /**
     * One merged rect per text line covered by [range], as FloatArray[x0,y0,x1,y1] in PDF points.
     *
     * Requires [PageText.chars] (and therefore [range]) to be ordered by [PageChar.lineIndex]
     * (non-decreasing). This holds for fitz-sourced [PageText]; arbitrary orderings are undefined.
     */
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

    /**
     * Move one endpoint of [range] to char [idx], keeping start <= end (the selection cannot invert).
     * [isStart] true moves the start handle (clamped to <= current end); false moves the end handle
     * (clamped to >= current start).
     */
    fun adjustRange(range: IntRange, idx: Int, isStart: Boolean): IntRange =
        if (isStart) minOf(idx, range.last)..range.last else range.first..maxOf(idx, range.first)

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
}
