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
