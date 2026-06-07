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
