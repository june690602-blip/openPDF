package io.github.june690602_blip.cleanpdf

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                val rv = a.findViewById<RecyclerView>(R.id.reader)
                assertTrue("adapter should have pages", (rv.adapter?.itemCount ?: 0) >= 1)
            }
        }
    }
}
