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
        toResultStrings(all)
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwpx parse error") }
}

/** "Contents/section12.xml" → 12. 못 찾으면 0. */
fun sectionIndex(name: String): Int =
    Regex("section(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
