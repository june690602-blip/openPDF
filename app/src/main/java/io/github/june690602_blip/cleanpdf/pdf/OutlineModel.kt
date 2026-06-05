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
