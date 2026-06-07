package io.github.june690602_blip.cleanpdf.doc

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile

/** DOCX = ZIP. word/document.xml 본문만 XmlFlowText 로 파싱. */
object DocxExtractor : DocTextExtractor {
    override fun extract(file: File): ExtractResult = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: return ExtractResult.Failure("no word/document.xml")
            zip.getInputStream(entry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null)
                toResult(XmlFlowText.parse(parser))
            }
        }
    }.getOrElse { ExtractResult.Failure(it.message ?: "docx parse error") }
}
