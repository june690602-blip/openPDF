package io.github.june690602_blip.cleanpdf

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSelectionSmokeTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test fun longPressShowsSelectionBar() {
        Thread.sleep(2000) // let the bundled sample render

        // Long-press in the upper band (y = 12% from top) where "CleanPDF - Page N" text lives.
        // A center long-press often lands on whitespace and misses the text.
        val longPressUpper = GeneralClickAction(
            Tap.LONG,
            CoordinatesProvider { v ->
                val xy = IntArray(2)
                v.getLocationOnScreen(xy)
                floatArrayOf(xy[0] + v.width / 2f, xy[1] + v.height * 0.12f)
            },
            Press.FINGER,
            InputDevice.SOURCE_TOUCHSCREEN,
            MotionEvent.BUTTON_PRIMARY,
        )
        onView(withId(R.id.reader)).perform(longPressUpper)
        Thread.sleep(1000) // text extraction + apply

        onView(withId(R.id.selection_bar)).check(matches(isDisplayed()))
    }
}
