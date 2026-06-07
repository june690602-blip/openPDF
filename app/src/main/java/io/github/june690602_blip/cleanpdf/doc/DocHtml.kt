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

    /** 구조 블록 → 오프라인 HTML(문단=<p>, 표=<table>, 이미지=base64 <img>). */
    fun render(blocks: List<DocBlock>): String {
        val body = StringBuilder()
        for (b in blocks) when (b) {
            is DocBlock.Para -> body.append("<p>").append(escape(b.text)).append("</p>")
            is DocBlock.Table -> {
                body.append("<table>")
                for (row in b.rows) {
                    body.append("<tr>")
                    for (c in row) body.append("<td>").append(escape(c)).append("</td>")
                    body.append("</tr>")
                }
                body.append("</table>")
            }
            is DocBlock.Image -> {
                val b64 = android.util.Base64.encodeToString(b.bytes, android.util.Base64.NO_WRAP)
                body.append("<img src=\"data:").append(b.mime).append(";base64,").append(b64).append("\">")
            }
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" +
            "body{font-family:sans-serif;font-size:1rem;line-height:1.6;margin:12px;word-wrap:break-word}" +
            "p{white-space:pre-wrap;margin:0 0 .6em}" +
            "table{border-collapse:collapse;margin:.6em 0;max-width:100%}" +
            "td{border:1px solid #999;padding:4px 8px;vertical-align:top}" +
            "img{max-width:100%;height:auto;margin:.6em 0}</style></head><body>" +
            body + "</body></html>"
    }

    /** 구조 블록 목록으로 위임. */
    fun toHtml(text: DocText): String = render(text.blocks)
}
