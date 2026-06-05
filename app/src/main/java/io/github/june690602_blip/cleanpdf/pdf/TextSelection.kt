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
}
