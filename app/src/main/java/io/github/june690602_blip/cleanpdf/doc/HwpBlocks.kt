package io.github.june690602_blip.cleanpdf.doc

import kr.dogfoot.hwplib.`object`.HWPFile
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPicture
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph

/** hwplib 객체모델 → DocBlock. 표=ControlTable, 이미지=등장순으로 binData 임베디드와 페어링. */
object HwpBlocks {
    fun build(hwp: HWPFile): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        val images = hwp.getBinData()?.getEmbeddedBinaryDataList() ?: arrayListOf()
        var picIdx = 0
        var usedImg = 0

        fun walk(paras: Array<Paragraph>) {
            for (p in paras) {
                val txt = runCatching { p.getNormalString() ?: "" }.getOrDefault("")
                if (txt.isNotBlank()) out.add(DocBlock.Para(txt))
                val controls = p.getControlList() ?: continue
                for (c in controls) when (c) {
                    is ControlTable -> {
                        val rows = ArrayList<List<String>>()
                        for (r in c.getRowList()) {
                            val cells = ArrayList<String>()
                            for (cell in r.getCellList()) {
                                val cellText = StringBuilder()
                                for (cp in cell.getParagraphList().getParagraphs()) {
                                    val t = runCatching { cp.getNormalString() ?: "" }.getOrDefault("")
                                    if (t.isNotBlank()) {
                                        if (cellText.isNotEmpty()) cellText.append(' ')
                                        cellText.append(t)
                                    }
                                }
                                cells.add(cellText.toString().trim())
                            }
                            if (cells.isNotEmpty()) rows.add(cells)
                        }
                        if (rows.isNotEmpty()) out.add(DocBlock.Table(rows))
                    }
                    is ControlPicture -> {
                        val bytes = images.getOrNull(picIdx++)?.getData()
                        if (bytes != null) when (val o = ImageFilter.classify(bytes, usedImg)) {
                            is ImageFilter.Outcome.Ok -> { out.add(o.image); usedImg += bytes.size }
                            ImageFilter.Outcome.Oversized -> out.add(DocBlock.Para("[큰 이미지 생략]"))
                            ImageFilter.Outcome.Unsupported -> {}
                        }
                    }
                }
            }
        }
        for (sec in hwp.getBodyText().getSectionList()) walk(sec.getParagraphs())
        return out
    }
}
