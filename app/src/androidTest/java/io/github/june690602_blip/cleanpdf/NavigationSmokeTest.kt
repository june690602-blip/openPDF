package io.github.june690602_blip.cleanpdf

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @Test fun scrollToLastPageDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500) // async open + first render (dev sample, 3 pages)
            scenario.onActivity { a ->
                val reader = a.findViewById<PdfReaderView>(R.id.reader)
                assertTrue("sample should have pages", reader.pageCount >= 1)
                reader.scrollToPage(reader.pageCount - 1) // jump to last page
            }
            Thread.sleep(500)
            scenario.onActivity { a ->
                val reader = a.findViewById<PdfReaderView>(R.id.reader)
                assertTrue("still has pages after jump", reader.pageCount >= 1)
            }
        }
    }
}
