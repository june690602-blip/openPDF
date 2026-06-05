package io.github.june690602_blip.cleanpdf.io

import android.content.Intent

/** Pure intent helpers — operate on already-extracted pieces (no Android Intent instance needed). */
object Intents {
    /** The incoming document for a VIEW (→ [viewData]) or SEND (→ [sendStream]) intent; else null. */
    fun <T> incomingUri(action: String?, viewData: T?, sendStream: T?): T? = when (action) {
        Intent.ACTION_VIEW -> viewData
        Intent.ACTION_SEND -> sendStream
        else -> null
    }
}
