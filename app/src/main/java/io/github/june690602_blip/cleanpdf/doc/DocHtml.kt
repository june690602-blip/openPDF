package io.github.june690602_blip.cleanpdf.doc

object DocHtml {
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** 문단을 줄바꿈으로 이어 <pre>(줄바꿈·탭 보존, 자동 줄나눔)로 감싼 오프라인 HTML. */
    fun toHtml(text: DocText): String {
        val body = text.paragraphs.joinToString("\n") { escape(it) }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
            "<body><pre style=\"white-space:pre-wrap; word-wrap:break-word; " +
            "font-family:sans-serif; font-size:1rem; line-height:1.5; margin:12px\">" +
            body + "</pre></body></html>"
    }
}
