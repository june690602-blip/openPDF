package io.github.june690602_blip.cleanpdf.io

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentsTest {
    @Test fun viewReturnsData() {
        assertEquals("DATA", Intents.incomingUri(Intent.ACTION_VIEW, "DATA", "STREAM"))
    }
    @Test fun sendReturnsStream() {
        assertEquals("STREAM", Intents.incomingUri(Intent.ACTION_SEND, null, "STREAM"))
    }
    @Test fun launcherReturnsNull() {
        assertNull(Intents.incomingUri(Intent.ACTION_MAIN, "DATA", "STREAM"))
    }
    @Test fun nullActionReturnsNull() {
        assertNull(Intents.incomingUri<String>(null, "DATA", "STREAM"))
    }
}
