package io.github.june690602_blip.cleanpdf

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollZoomSmokeTest {
    @Test fun opensSampleAndScrolls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500) // allow async open+first render (dev sample)
            onView(withId(R.id.reader)).perform(swipeUp(), swipeUp())
            scenario.onActivity { a ->
                val reader = a.findViewById<PdfReaderView>(R.id.reader)
                assertTrue("reader should have pages", reader.pageCount >= 1)
            }
        }
    }
}
