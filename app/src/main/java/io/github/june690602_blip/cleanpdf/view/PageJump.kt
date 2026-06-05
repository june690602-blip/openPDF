package io.github.june690602_blip.cleanpdf.view

/** Pure page-number parsing for "go to page" input. */
object PageJump {
    /** Parse a 1-based [input] into a 0-based page index clamped to [1, total]; null if invalid. */
    fun parse(input: String, total: Int): Int? {
        if (total <= 0) return null
        val n = input.trim().toIntOrNull() ?: return null
        val oneBased = n.coerceIn(1, total)
        return oneBased - 1
    }
}
