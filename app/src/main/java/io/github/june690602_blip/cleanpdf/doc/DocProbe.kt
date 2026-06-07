package io.github.june690602_blip.cleanpdf.doc

import java.io.File
import java.util.zip.ZipFile

/** detect 가 UNKNOWN 인데 매직이 ZIP/OLE 면 컨테이너를 열어 정밀판정. */
object DocProbe {
    fun refine(file: File, head: ByteArray): DocFormat = when {
        isZipMagic(head) -> probeZip(file)
        isOleMagic(head) -> if (isHwpOle(file)) DocFormat.HWP else DocFormat.UNKNOWN
        else -> DocFormat.UNKNOWN
    }

    private fun probeZip(file: File): DocFormat = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            when {
                names.any { it == "word/document.xml" } -> DocFormat.DOCX
                names.any { it.startsWith("Contents/section") } -> DocFormat.HWPX
                zip.getEntry("mimetype")?.let { e ->
                    zip.getInputStream(e).bufferedReader().use { it.readText() }.trim()
                } == "application/hwp+zip" -> DocFormat.HWPX
                else -> DocFormat.UNKNOWN
            }
        }
    }.getOrDefault(DocFormat.UNKNOWN)

    /** OLE 헤더 앞부분에서 HWP v5 서명을 best-effort 스캔(확장자 없는 경우만 도달하는 드문 경로). */
    private fun isHwpOle(file: File): Boolean = runCatching {
        val buf = ByteArray(64 * 1024)
        val n = file.inputStream().use { it.read(buf) }
        val head = if (n > 0) buf.copyOf(n) else ByteArray(0)
        indexOf(head, "HWP Document File".toByteArray(Charsets.US_ASCII)) >= 0
    }.getOrDefault(false)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
