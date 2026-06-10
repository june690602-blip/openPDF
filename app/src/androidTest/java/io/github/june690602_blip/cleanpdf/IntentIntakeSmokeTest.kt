package io.github.june690602_blip.cleanpdf

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IntentIntakeSmokeTest {
    @Test fun opensPdfFromViewIntent() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 번들 샘플을 외부 파일로 복사해 file:// VIEW 인텐트로 전달.
        val out = File(ctx.getExternalFilesDir(null), "intake_smoke.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(out), "application/pdf")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            Thread.sleep(1500)
            scenario.onActivity { a ->
                val reader = a.findViewById<PdfReaderView>(R.id.reader)
                assertTrue("intent-opened PDF should have pages", reader.pageCount >= 1)
            }
        }
    }
}
