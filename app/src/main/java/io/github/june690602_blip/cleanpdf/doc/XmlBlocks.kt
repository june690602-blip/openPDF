package io.github.june690602_blip.cleanpdf.doc

import org.xmlpull.v1.XmlPullParser

/**
 * DOCX(document.xml)·HWPX(section*.xml) 공용 본문 → 구조 블록(순수). local-name 매칭(prefix 무관).
 *  <p>=Para, <t>=텍스트, <tab>=\t, <br>/<cr>=\n, <tbl>/<tr>/<tc>=Table(셀=평탄 텍스트).
 *  이미지 참조 속성(r:embed=docx, binaryItemIDRef=hwpx) → resolveImage(refId) → ImageFilter.
 *  표 안 이미지는 v1 생략. 중첩표 best-effort(텍스트는 부모 셀로).
 */
object XmlBlocks {
    // Local names of image-reference attributes (prefix-agnostic matching).
    private val IMAGE_REF_LOCAL = setOf("embed", "binaryItemIDRef")

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
        // Scan all attributes for embed / binaryItemIDRef by local-name (prefix agnostic).
        fun imageRef(): String? {
            for (i in 0 until parser.attributeCount) {
                val attrLocal = local(parser.getAttributeName(i))
                if (attrLocal in IMAGE_REF_LOCAL) return parser.getAttributeValue(i)
            }
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
