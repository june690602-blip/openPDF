package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile

/** HWPX = ZIP. section*.xml(번호순)을 XmlBlocks로, 이미지는 content.hpf(id→href)로 해소. */
object HwpxExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        ZipFile(file).use { zip ->
            val manifest = parseManifest(zip)  // binItemId -> href
            val sections = zip.entries().asSequence()
                .filter { it.name.matches(Regex("Contents/section\\d+\\.xml")) }
                .sortedBy { sectionIndex(it.name) }.toList()
            if (sections.isEmpty()) return ExtractResult.Failure("no section xml")
            val all = ArrayList<DocBlock>()
            for (entry in sections) {
                zip.getInputStream(entry).use { input ->
                    val parser = Xml.newPullParser(); parser.setInput(input, null)
                    all.addAll(XmlBlocks.parse(parser) { ref -> resolveBin(zip, manifest, ref) })
                }
            }
            toResult(all)
        }
    }.getOrElse { ExtractResult.Failure(it.message ?: "hwpx parse error") }

    private fun resolveBin(zip: ZipFile, manifest: Map<String, String>, ref: String): ByteArray? {
        val href = manifest[ref] ?: return null
        val base = href.removePrefix("/")
        val candidates = listOf(base, "Contents/$base", "BinData/" + base.substringAfterLast('/'))
        for (path in candidates) zip.getEntry(path)?.let { e -> return zip.getInputStream(e).use { it.readBytes() } }
        return null
    }

    /** Contents/content.hpf 의 <opf:item id href> → id→href. */
    private fun parseManifest(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("Contents/content.hpf") ?: return emptyMap()
        val map = HashMap<String, String>()
        zip.getInputStream(entry).use { input ->
            val p = Xml.newPullParser(); p.setInput(input, null)
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && p.name.substringAfterLast(':') == "item") {
                    val id = p.getAttributeValue(null, "id"); val href = p.getAttributeValue(null, "href")
                    if (id != null && href != null) map[id] = href
                }
                ev = p.next()
            }
        }
        return map
    }
}

/** "Contents/section12.xml" → 12. 못 찾으면 0. */
fun sectionIndex(name: String): Int =
    Regex("section(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
