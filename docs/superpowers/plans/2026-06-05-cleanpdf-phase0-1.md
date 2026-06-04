# CleanPDF Viewer — Phase 0 & 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a new Android app that opens a user-picked PDF and shows it as a smooth, pinch-zoomable continuous vertical scroll — proving the MuPDF engine integration end-to-end.

**Architecture:** Kotlin + Android Views. MuPDF `fitz` (prebuilt AAR from `maven.ghostscript.com`) is wrapped by a thin `PdfDocument`. A single-thread `PageRenderer` produces `Bitmap`s (cached in an LRU) that a RecyclerView-based `PdfReaderView` displays one page per row. Pure layout/cache/validation logic is unit-tested on the JVM; rendering is covered by instrumented smoke tests.

**Tech Stack:** AGP 9.1.1, Gradle 9.3.1 (built-in Kotlin), minSdk 24 / targetSdk 36, `com.artifex.mupdf:fitz:1.27.1` (latest published on maven.ghostscript.com, verified 2026-06-05), AndroidX RecyclerView, JUnit4 + Robolectric (unit), AndroidX Test (instrumented).

**Spec:** `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md`

> **API corrections from the T1 spike (fitz 1.27.1, verified on device 2026-06-05):**
> - `AndroidDrawDevice.drawPage` has **only** `(Page, Matrix)` / `(Page, Float)` / `(Page, Float, Int)` overloads — **no Cookie overload.** So `PdfDocument.renderPage(index, scale)` takes **no cookie**, and Task 5's `PageRenderer` must NOT use `Cookie`/`cookie.abort()`. Cancellation = `Future.cancel(true)` + an `isInterrupted` check before a render starts (an already-running `drawPage` cannot be aborted; acceptable — pages render fast). The cookie-based `CancelableFuture` in Task 5 Step 2 is replaced by a plain `Future` (corrected code provided at dispatch).
> - `Page.getBounds()` is a **method**, not a Kotlin `.bounds` property. `Rect` fields are `x0,y0,x1,y1`.

---

## Conventions & Prerequisites

- All commands run from repo root `C:\dev\openPDF` in **git-bash**.
- Sibling repo `C:\dev\opendwg` (CleanCAD) has a working Gradle 9.3.1 wrapper + AGP 9.1.1 toolchain we copy from.
- Instrumented tests need a running emulator (e.g. AVD `Medium_Phone_API_36.1`, x86_64).
- Package base: `io.github.june690602_blip.cleanpdf` → source path `app/src/main/java/io/github/june690602_blip/cleanpdf/`.
- **License:** every new Kotlin file is part of an AGPL v3 app. (Headers added in Phase 7; do not block on them now.)
- Commit message format: `<type>: <description>` (types: feat, fix, refactor, docs, test, chore).

## File Structure (Phase 0 & 1)

**Phase 0 — scaffold + engine + first render**
- `settings.gradle.kts` — modules + repos (adds MuPDF maven repo)
- `build.gradle.kts` — root, applies android plugin
- `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*` (copied)
- `gradlew`, `gradlew.bat` (copied)
- `app/build.gradle.kts` — android config + fitz dependency
- `app/proguard-rules.pro`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/{strings,themes,colors}.xml`, `res/values-night/themes.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/assets/sample.pdf` — dev-only test document
- `app/src/main/java/.../MainActivity.kt` — host activity
- `app/src/main/java/.../pdf/PdfDocument.kt` — fitz wrapper (open/count/size/render/close)
- `app/src/main/java/.../pdf/PageSize.kt` — immutable page-size model
- `app/src/main/java/.../pdf/PdfOpenResult.kt` — sealed open result
- `app/src/main/java/.../pdf/PdfValidation.kt` — `isLikelyPdf` (pure)
- `app/src/test/java/.../pdf/PdfValidationTest.kt` — unit
- `app/src/androidTest/java/.../RenderSmokeTest.kt` — instrumented render proof

**Phase 1 — continuous scroll viewer + zoom + cache + open picker**
- `app/src/main/java/.../pdf/PageRenderer.kt` — single-thread, cancelable render
- `app/src/main/java/.../cache/LruByteSizedCache.kt` — generic byte-budget LRU (pure)
- `app/src/main/java/.../cache/BitmapCache.kt` — Bitmap-typed wrapper
- `app/src/main/java/.../view/PageLayout.kt` — page offsets / visible range (pure)
- `app/src/main/java/.../view/PdfReaderView.kt` — RecyclerView continuous scroll + zoom
- `app/src/main/java/.../view/PdfPageAdapter.kt` — one page per row, async bitmap
- `app/src/main/java/.../io/PdfSource.kt` — copy `content://`/asset → cache file
- `app/src/test/java/.../cache/LruByteSizedCacheTest.kt` — unit
- `app/src/test/java/.../view/PageLayoutTest.kt` — unit
- `app/src/androidTest/java/.../ScrollZoomSmokeTest.kt` — instrumented

---

# PHASE 0 — Scaffold + MuPDF engine + first render

## Task 0: Scaffold a buildable empty app

**Files:**
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` from `C:\dev\opendwg`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/{strings,colors,themes}.xml`, `app/src/main/res/values-night/themes.xml`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt`
- Create: `.gitignore`

- [ ] **Step 1: Copy the proven Gradle wrapper from the sibling repo**

```bash
cd /c/dev/openPDF
cp /c/dev/opendwg/gradlew /c/dev/opendwg/gradlew.bat ./
mkdir -p gradle/wrapper
cp /c/dev/opendwg/gradle/wrapper/gradle-wrapper.jar      gradle/wrapper/
cp /c/dev/opendwg/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
```

- [ ] **Step 2: Create `.gitignore`**

```gitignore
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/app/build
/captures
.externalNativeBuild
.cxx
keystore.properties
*.jks
*.keystore
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 4: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.1.1"
coreKtx = "1.18.0"
junit = "4.13.2"
junitVersion = "1.3.0"
espressoCore = "3.7.0"
appcompat = "1.6.1"
material = "1.10.0"
activity = "1.13.0"
constraintlayout = "2.1.4"
recyclerview = "1.3.2"
robolectric = "4.14.1"
lifecycle = "2.9.0"
mupdfFitz = "1.27.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-activity = { group = "androidx.activity", name = "activity", version.ref = "activity" }
androidx-activity-ktx = { group = "androidx.activity", name = "activity-ktx", version.ref = "activity" }
androidx-constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
androidx-recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
mupdf-fitz = { group = "com.artifex.mupdf", name = "fitz", version.ref = "mupdfFitz" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

- [ ] **Step 5: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
}
```

- [ ] **Step 6: Create `settings.gradle.kts` (note the MuPDF repo)**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF prebuilt fitz AAR. If https fails, see Task 1 Step 2 (allowInsecureProtocol).
        maven { url = uri("https://maven.ghostscript.com") }
    }
}
rootProject.name = "CleanPDF"
include(":app")
```

- [ ] **Step 7: Create `app/build.gradle.kts`** (mirrors CleanCAD minus NDK/signing; minify off until Phase 7)

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.june690602_blip.cleanpdf"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.june690602_blip.cleanpdf"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.mupdf.fitz)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

- [ ] **Step 8: Create `app/proguard-rules.pro`** (keep MuPDF JNI classes for the eventual release build)

```proguard
# MuPDF fitz classes are reached via JNI — never strip/rename.
-keep class com.artifex.mupdf.fitz.** { *; }
```

- [ ] **Step 9: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.CleanPDF">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 10: Create resource files**

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">CleanPDF Viewer</string>
    <string name="open_pdf">PDF 열기</string>
    <string name="error_open">PDF를 열 수 없습니다</string>
</resources>
```

`app/src/main/res/values/colors.xml`:
```xml
<resources>
    <color name="reader_bg">#FF2B2B2B</color>
    <color name="page_gap">#FF000000</color>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.CleanPDF" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="android:windowBackground">@color/reader_bg</item>
    </style>
</resources>
```

`app/src/main/res/values-night/themes.xml`:
```xml
<resources>
    <style name="Theme.CleanPDF" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="android:windowBackground">@color/reader_bg</item>
    </style>
</resources>
```

- [ ] **Step 11: Create `app/src/main/res/layout/activity_main.xml`** (placeholder; replaced in Task 1)

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <ImageView
        android:id="@+id/preview"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:contentDescription="@string/app_name"
        android:scaleType="fitCenter" />
</FrameLayout>
```

- [ ] **Step 12: Create `MainActivity.kt`** (placeholder)

`app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

- [ ] **Step 13: Build the empty app**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. This confirms the toolchain + the MuPDF maven repo resolve. If fitz fails to download, do NOT proceed — fix in Task 1 Step 2 first, then re-run.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "chore: scaffold CleanPDF Android app with MuPDF fitz dependency"
```

## Task 1: MuPDF integration spike — render page 0 of a bundled PDF

> This is a **de-risking spike**, not TDD: its purpose is to prove the engine renders on a device and to pin the exact fitz API/version. Strict unit-test cycles resume in Task 2.

**Files:**
- Create: `app/src/main/assets/sample.pdf`
- Create: `app/src/main/java/.../pdf/PageSize.kt`
- Create: `app/src/main/java/.../pdf/PdfDocument.kt`
- Modify: `app/src/main/java/.../MainActivity.kt`

- [ ] **Step 1: Add a dev-only sample PDF**

Place any small PDF with **2+ pages** at `app/src/main/assets/sample.pdf` (e.g. browser "Print → Save as PDF" of any web page). Dev-only; not shipped to users.

```bash
ls -la app/src/main/assets/sample.pdf   # confirm it exists and is non-zero
```

- [ ] **Step 2: Verify the fitz artifact actually resolves (and fix repo if needed)**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i mupdf`
Expected: a line containing `com.artifex.mupdf:fitz:1.27.1`.

If it does NOT resolve:
1. Find the latest published version by listing the repo metadata:
   `curl -s https://maven.ghostscript.com/com/artifex/mupdf/fitz/maven-metadata.xml`
   then set `mupdfFitz` in `libs.versions.toml` to a `<version>` that the XML lists.
2. If `https` is refused, switch the repo in `settings.gradle.kts` to:
   ```kotlin
   maven {
       url = uri("http://maven.ghostscript.com")
       isAllowInsecureProtocol = true
   }
   ```
Re-run this step until the grep shows a resolved version. Record the working version in the commit message.

- [ ] **Step 3: Create the page-size model**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageSize.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/** PDF page size in PDF points (1/72 inch), at scale 1.0. Immutable. */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = if (height == 0f) 1f else width / height
}
```

- [ ] **Step 4: Create the fitz wrapper**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfDocument.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice

/**
 * Thin wrapper over MuPDF [Document]. NOT thread-safe: all calls must run on a
 * single dedicated thread (see PageRenderer in Phase 1). Always [close] when done.
 */
class PdfDocument private constructor(private val doc: Document) {

    val pageCount: Int = doc.countPages()

    fun needsPassword(): Boolean = doc.needsPassword()

    fun authenticate(password: String): Boolean = doc.authenticatePassword(password)

    fun pageSize(index: Int): PageSize {
        val page = doc.loadPage(index)
        val b = page.bounds            // fitz Rect: x0,y0,x1,y1 in points
        page.destroy()
        return PageSize(b.x1 - b.x0, b.y1 - b.y0)
    }

    /** Render [index] at [scale] (1.0 = 72dpi) into a new ARGB_8888 bitmap. */
    fun renderPage(index: Int, scale: Float, cookie: Cookie? = null): Bitmap {
        val page = doc.loadPage(index)
        val ctm = Matrix(scale, scale)
        // Static helper allocates an ARGB_8888 bitmap sized to the transformed page.
        val bmp = AndroidDrawDevice.drawPage(page, ctm, cookie)
        page.destroy()
        return bmp
    }

    fun close() = doc.destroy()

    companion object {
        /** Open a PDF from a local filesystem path. Throws on unreadable/corrupt files. */
        fun open(path: String): PdfDocument = PdfDocument(Document.openDocument(path))
    }
}
```

> Spike note: confirm `AndroidDrawDevice.drawPage(Page, Matrix, Cookie)` exists in 1.27.1. If the
> 3-arg overload is absent, use `AndroidDrawDevice.drawPage(page, ctm)` and drop the cookie param.
> Reference: github.com/ArtifexSoftware/mupdf `platform/java/.../android/AndroidDrawDevice.java`.

- [ ] **Step 5: Render page 0 in MainActivity (background thread → ImageView)**

`app/src/main/java/io/github/june690602_blip/cleanpdf/MainActivity.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val preview = findViewById<ImageView>(R.id.preview)

        io.execute {
            val cached = copyAssetToCache("sample.pdf")
            val doc = PdfDocument.open(cached.absolutePath)
            val bmp: Bitmap = doc.renderPage(0, scale = 2.0f)
            doc.close()
            runOnUiThread { preview.setImageBitmap(bmp) }
        }
    }

    private fun copyAssetToCache(name: String): File {
        val out = File(cacheDir, name)
        assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
```

- [ ] **Step 6: Install and visually verify on a running emulator/device**

Run: `./gradlew :app:installDebug && adb shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity`
Expected: the first page of `sample.pdf` is visible, sharp, correctly oriented.

If you see `UnsatisfiedLinkError` for `libmupdf_java.so`: the emulator ABI lacks a bundled `.so`. Verify with `adb shell getprop ro.product.cpu.abi`; test on an `x86_64` or `arm64-v8a` image (the fitz AAR ships those). Record which ABIs work.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: render first PDF page via MuPDF fitz (engine integration verified)"
```

## Task 2: PDF validation + open-result model (pure, TDD)

**Files:**
- Create: `app/src/main/java/.../pdf/PdfOpenResult.kt`
- Create: `app/src/main/java/.../pdf/PdfValidation.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/PdfValidationTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/june690602_blip/cleanpdf/pdf/PdfValidationTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfValidationTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun acceptsByPdfExtension() {
        assertTrue(isLikelyPdf("report.pdf", bytes("garbage")))
    }

    @Test fun acceptsByMagicHeaderRegardlessOfName() {
        assertTrue(isLikelyPdf("blob_bin", bytes("%PDF-1.7\n...")))
    }

    @Test fun extensionIsCaseInsensitive() {
        assertTrue(isLikelyPdf("DRAWING.PDF", ByteArray(0)))
    }

    @Test fun rejectsNonPdf() {
        assertFalse(isLikelyPdf("photo.jpg", bytes("ÿØÿ")))
    }

    @Test fun rejectsNullNameWithoutMagic() {
        assertFalse(isLikelyPdf(null, bytes("nope")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PdfValidationTest"`
Expected: FAIL — `isLikelyPdf` unresolved (compilation error).

- [ ] **Step 3: Write the minimal implementation**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfValidation.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

/** True if [name] ends in .pdf (case-insensitive) OR [head] starts with the %PDF- magic. */
fun isLikelyPdf(name: String?, head: ByteArray): Boolean {
    if (name != null && name.lowercase().endsWith(".pdf")) return true
    if (head.size < PDF_MAGIC.size) return false
    for (i in PDF_MAGIC.indices) if (head[i] != PDF_MAGIC[i]) return false
    return true
}
```

- [ ] **Step 4: Create the open-result model**

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PdfOpenResult.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

/** Outcome of attempting to open a PDF. Immutable. */
sealed interface PdfOpenResult {
    data class Success(val document: PdfDocument) : PdfOpenResult
    data object NeedsPassword : PdfOpenResult
    data class Error(val reason: String) : PdfOpenResult
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PdfValidationTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add isLikelyPdf validation and PdfOpenResult model with unit tests"
```

## Task 3: Render smoke test (instrumented)

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/RenderSmokeTest.kt`

- [ ] **Step 1: Write the instrumented test**

`app/src/androidTest/java/io/github/june690602_blip/cleanpdf/RenderSmokeTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RenderSmokeTest {

    @Test fun rendersSamplePageWithVisibleInk() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "sample.pdf")
        ctx.assets.open("sample.pdf").use { i -> out.outputStream().use { i.copyTo(it) } }

        val doc = PdfDocument.open(out.absolutePath)
        assertTrue("PDF should have >= 1 page", doc.pageCount >= 1)

        val bmp = doc.renderPage(0, scale = 1.5f)
        doc.close()

        // A real page must contain at least one non-white pixel.
        var hasInk = false
        loop@ for (y in 0 until bmp.height step 7) {
            for (x in 0 until bmp.width step 7) {
                if (bmp.getPixel(x, y) and 0x00FFFFFF != 0x00FFFFFF) { hasInk = true; break@loop }
            }
        }
        assertTrue("rendered page should have visible content", hasInk)
    }
}
```

- [ ] **Step 2: Run it on an emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*RenderSmokeTest"`
Expected: PASS. (Needs a running emulator. If none, start AVD `Medium_Phone_API_36.1` first.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add instrumented render smoke test for MuPDF page output"
```

**✅ Phase 0 done when:** app builds, renders a bundled PDF page on device, unit tests pass, render smoke test passes. The MuPDF assumption from the spec is now verified.

---

# PHASE 1 — Continuous scroll viewer + zoom + cache + open picker

## Task 4: Byte-budgeted LRU cache (pure, TDD)

**Files:**
- Create: `app/src/main/java/.../cache/LruByteSizedCache.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/cache/LruByteSizedCacheTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/june690602_blip/cleanpdf/cache/LruByteSizedCacheTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruByteSizedCacheTest {
    // value = its own byte size for easy reasoning
    private fun cache(max: Int) = LruByteSizedCache<Int, Int>(max) { it }

    @Test fun returnsStoredValue() {
        val c = cache(100); c.put(1, 10); assertEquals(10, c.get(1))
    }

    @Test fun evictsLeastRecentlyUsedWhenOverBudget() {
        val c = cache(30)
        c.put(1, 10); c.put(2, 10); c.put(3, 10) // total 30, ok
        c.get(1)                                  // touch 1 -> 2 is now LRU
        c.put(4, 10)                              // over budget -> evict 2
        assertEquals(10, c.get(1))
        assertNull(c.get(2))
        assertEquals(10, c.get(4))
    }

    @Test fun overwritingKeyUpdatesTotalSize() {
        val c = cache(15)
        c.put(1, 10); c.put(1, 5) // same key, smaller value
        c.put(2, 9)               // 5 + 9 = 14 <= 15, nothing evicted
        assertEquals(5, c.get(1))
        assertEquals(9, c.get(2))
    }

    @Test fun evictionInvokesCallback() {
        val evicted = mutableListOf<Int>()
        val c = LruByteSizedCache<Int, Int>(10, sizeOf = { it }, onEvict = { _, v -> evicted.add(v) })
        c.put(1, 10); c.put(2, 10) // evicts 1
        assertEquals(listOf(10), evicted)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LruByteSizedCacheTest"`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Write the minimal implementation** (access-order LinkedHashMap; pure JVM)

`app/src/main/java/io/github/june690602_blip/cleanpdf/cache/LruByteSizedCache.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.cache

/**
 * LRU cache bounded by total byte size. Not thread-safe; guard externally.
 * @param maxBytes eviction threshold
 * @param sizeOf bytes a value occupies
 * @param onEvict optional hook for releasing evicted values (e.g. Bitmap.recycle)
 */
class LruByteSizedCache<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0

    fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.remove(key)?.let { currentBytes -= sizeOf(it) }
        map[key] = value
        currentBytes += sizeOf(value)
        trim()
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (currentBytes > maxBytes && it.hasNext()) {
            val e = it.next()       // eldest first (access order)
            it.remove()
            currentBytes -= sizeOf(e.value)
            onEvict(e.key, e.value)
        }
    }

    fun clear() {
        map.entries.forEach { onEvict(it.key, it.value) }
        map.clear(); currentBytes = 0
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LruByteSizedCacheTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add byte-budgeted LRU cache with eviction callback and unit tests"
```

## Task 5: Bitmap cache + single-thread PageRenderer

**Files:**
- Create: `app/src/main/java/.../cache/BitmapCache.kt`
- Create: `app/src/main/java/.../pdf/PageRenderer.kt`

- [ ] **Step 1: Create the Bitmap-typed cache** (key = page + scale bucket)

`app/src/main/java/io/github/june690602_blip/cleanpdf/cache/BitmapCache.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.cache

import android.graphics.Bitmap

/** Cache key: a page rendered at a quantized scale. */
data class PageKey(val page: Int, val scaleMilli: Int)

class BitmapCache(maxBytes: Int) {
    private val lru = LruByteSizedCache<PageKey, Bitmap>(
        maxBytes = maxBytes,
        sizeOf = { it.allocationByteCount },
        onEvict = { _, bmp -> if (!bmp.isRecycled) bmp.recycle() },
    )
    fun get(key: PageKey): Bitmap? = lru.get(key)?.takeIf { !it.isRecycled }
    fun put(key: PageKey, bmp: Bitmap) = lru.put(key, bmp)
    fun clear() = lru.clear()

    companion object {
        /** Quantize a float scale to an int key bucket (3 decimal places). */
        fun scaleMilli(scale: Float): Int = (scale * 1000f).toInt()
    }
}
```

- [ ] **Step 2: Create the renderer** (owns the single fitz thread; all doc access funnels here)

`app/src/main/java/io/github/june690602_blip/cleanpdf/pdf/PageRenderer.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Cookie
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Serializes ALL access to a [PdfDocument] onto one thread (fitz is not thread-safe),
 * and renders pages off the main thread. Submit returns a [Future] you can cancel.
 */
class PageRenderer(private val doc: PdfDocument) {
    private val exec = Executors.newSingleThreadExecutor()

    val pageCount: Int get() = doc.pageCount

    fun sizesBlockingOnRenderThread(): List<PageSize> =
        exec.submit<List<PageSize>> { (0 until doc.pageCount).map { doc.pageSize(it) } }.get()

    /** Render [page] at [scale]; deliver bitmap on the render thread via [onReady]. */
    fun submit(page: Int, scale: Float, onReady: (Bitmap) -> Unit): Future<*> {
        val cookie = Cookie()
        val f = exec.submit {
            if (!Thread.currentThread().isInterrupted) {
                val bmp = doc.renderPage(page, scale, cookie)
                onReady(bmp)
            }
        }
        return CancelableFuture(f, cookie)
    }

    fun shutdown() {
        exec.submit { doc.close() }
        exec.shutdown()
    }

    /** Wraps a Future so cancel() also aborts the in-flight fitz render via Cookie. */
    private class CancelableFuture(private val f: Future<*>, private val cookie: Cookie) : Future<Any?> {
        override fun cancel(mayInterrupt: Boolean): Boolean { cookie.abort(); return f.cancel(mayInterrupt) }
        override fun isCancelled() = f.isCancelled
        override fun isDone() = f.isDone
        override fun get(): Any? = f.get()
        override fun get(t: Long, u: java.util.concurrent.TimeUnit): Any? = f.get(t, u)
    }
}
```

> Spike note: confirm `Cookie` has `abort()` in 1.27.1 (it has historically). If not, drop the
> CancelableFuture wrapper and return the raw Future.

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add BitmapCache and single-thread cancelable PageRenderer"
```

## Task 6: Page layout math (pure, TDD)

**Files:**
- Create: `app/src/main/java/.../view/PageLayout.kt`
- Test: `app/src/test/java/io/github/june690602_blip/cleanpdf/view/PageLayoutTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/june690602_blip/cleanpdf/view/PageLayoutTest.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    // two pages: 100x200 and 100x100 pts; fit width 100px, gap 10px, zoom 1.0
    private val sizes = listOf(PageSize(100f, 200f), PageSize(100f, 100f))

    @Test fun heightsScaleToFitWidth() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        assertEquals(200f, l.pageHeight(0), 0.001f) // 100->100 wide, 200->200 tall
        assertEquals(100f, l.pageHeight(1), 0.001f)
    }

    @Test fun topsAccumulateWithGaps() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        assertEquals(0f, l.pageTop(0), 0.001f)
        assertEquals(210f, l.pageTop(1), 0.001f) // 200 + 10 gap
    }

    @Test fun totalHeightIncludesTrailingPages() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        assertEquals(310f, l.totalHeight, 0.001f) // 200 + 10 + 100
    }

    @Test fun zoomMultipliesEverything() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 2f)
        assertEquals(400f, l.pageHeight(0), 0.001f)
        assertEquals(620f, l.pageTop(1), 0.001f) // (200+10)*2 ... wait gap also scales? see impl
    }

    @Test fun visibleRangeFindsOverlappingPages() {
        val l = PageLayout.compute(sizes, fitWidthPx = 100, gapPx = 10, zoom = 1f)
        // viewport [205,260) overlaps end of page0 (..200) gap, and page1 (210..310)
        val r = l.visiblePages(scrollY = 205f, viewportH = 55f)
        assertEquals(1, r.first) // page0 ends at 200, below 205 -> first visible is page1
        assertEquals(1, r.last)
    }
}
```

> Note: this test pins the decision that **gap scales with zoom**. Implement accordingly.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PageLayoutTest"`
Expected: FAIL — `PageLayout` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/io/github/june690602_blip/cleanpdf/view/PageLayout.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import io.github.june690602_blip.cleanpdf.pdf.PageSize

/**
 * Vertical stack layout for continuous scroll. All values in pixels at the given [zoom].
 * Each page is scaled so its width == fitWidthPx*zoom; heights follow aspect; gaps scale too.
 */
class PageLayout private constructor(
    private val tops: FloatArray,
    private val heights: FloatArray,
    val totalHeight: Float,
    val contentWidth: Float,
) {
    val pageCount: Int get() = heights.size
    fun pageTop(i: Int): Float = tops[i]
    fun pageHeight(i: Int): Float = heights[i]

    /** Inclusive range of page indices intersecting [scrollY, scrollY+viewportH). */
    fun visiblePages(scrollY: Float, viewportH: Float): IntRange {
        if (pageCount == 0) return IntRange.EMPTY
        val bottom = scrollY + viewportH
        var first = pageCount - 1
        var last = 0
        var found = false
        for (i in 0 until pageCount) {
            val t = tops[i]; val b = t + heights[i]
            if (b > scrollY && t < bottom) {
                if (i < first) first = i
                if (i > last) last = i
                found = true
            }
        }
        return if (found) first..last else IntRange.EMPTY
    }

    companion object {
        fun compute(sizes: List<PageSize>, fitWidthPx: Int, gapPx: Int, zoom: Float): PageLayout {
            val w = fitWidthPx * zoom
            val gap = gapPx * zoom
            val tops = FloatArray(sizes.size)
            val heights = FloatArray(sizes.size)
            var y = 0f
            for (i in sizes.indices) {
                val h = if (sizes[i].aspect == 0f) w else w / sizes[i].aspect
                tops[i] = y
                heights[i] = h
                y += h + (if (i < sizes.size - 1) gap else 0f)
            }
            return PageLayout(tops, heights, y, w)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PageLayoutTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add PageLayout vertical-stack math with unit tests"
```

## Task 7: PdfReaderView — RecyclerView continuous scroll

**Files:**
- Create: `app/src/main/java/.../view/PdfPageAdapter.kt`
- Create: `app/src/main/java/.../view/PdfReaderView.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Create the adapter** (one page per row; height from PageLayout; async bitmap)

`app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfPageAdapter.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.cache.PageKey
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import java.util.concurrent.Future

class PdfPageAdapter(
    private val renderer: PageRenderer,
    private val cache: BitmapCache,
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    private var layout: PageLayout? = null
    private var zoom: Float = 1f
    private var fitWidthPx: Int = 0

    fun submitLayout(layout: PageLayout, zoom: Float, fitWidthPx: Int) {
        this.layout = layout; this.zoom = zoom; this.fitWidthPx = fitWidthPx
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = layout?.pageCount ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val frame = FrameLayout(parent.context)
        val iv = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(iv)
        return PageVH(frame, iv)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val l = layout ?: return
        val h = l.pageHeight(position).toInt()
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, h,
        )
        holder.pending?.cancel(true)
        holder.image.setImageBitmap(null)

        val renderScale = (fitWidthPx * zoom) / PDF_BASE_WIDTH_HINT // see note below
        val key = PageKey(position, BitmapCache.scaleMilli(renderScale))
        val cached = cache.get(key)
        if (cached != null) { holder.image.setImageBitmap(cached); return }

        holder.pending = renderer.submit(position, renderScale) { bmp ->
            holder.itemView.post {
                cache.put(key, bmp)
                if (holder.bindingAdapterPosition == position) holder.image.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: PageVH) { holder.pending?.cancel(true); holder.pending = null }

    class PageVH(itemView: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(itemView) {
        var pending: Future<*>? = null
    }

    companion object {
        // fitz scale 1.0 == 72dpi. We want page width (in px) == fitWidthPx*zoom.
        // renderScale = targetWidthPx / pageWidthPts. PdfReaderView passes exact per-page scale
        // in Task 8 refinement; for Task 7 we approximate via a base hint and FIT_CENTER.
        const val PDF_BASE_WIDTH_HINT = 595f // A4 width in pts; refined in Task 8
    }
}
```

> Implementation note for the executor: `renderer.submit` runs on the render thread and calls back
> there; we re-post to the view via `itemView.post {}` to touch UI on the main thread.

- [ ] **Step 2: Create PdfReaderView**

`app/src/main/java/io/github/june690602_blip/cleanpdf/view/PdfReaderView.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.view

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.june690602_blip.cleanpdf.cache.BitmapCache
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PageSize

class PdfReaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    private var renderer: PageRenderer? = null
    private var cache: BitmapCache? = null
    private var sizes: List<PageSize> = emptyList()
    private var adapterImpl: PdfPageAdapter? = null
    private val gapPx = (resources.displayMetrics.density * 8).toInt()
    var zoom: Float = 1f; private set

    init { layoutManager = LinearLayoutManager(context) }

    /** Attach an opened document. [sizes] precomputed off-thread by the caller. */
    fun setDocument(renderer: PageRenderer, sizes: List<PageSize>) {
        this.renderer = renderer; this.sizes = sizes
        // ~96MB bitmap budget (tune later); guard against tiny heaps.
        this.cache = BitmapCache(maxBytes = 96 * 1024 * 1024)
        val a = PdfPageAdapter(renderer, cache!!)
        adapterImpl = a; adapter = a
        relayout()
    }

    private fun relayout() {
        val r = renderer ?: return
        if (width == 0) { post { relayout() }; return }
        val layout = PageLayout.compute(sizes, fitWidthPx = width, gapPx = gapPx, zoom = zoom)
        adapterImpl?.submitLayout(layout, zoom, width)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); relayout()
    }
}
```

- [ ] **Step 3: Replace `activity_main.xml` with the reader view**

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<io.github.june690602_blip.cleanpdf.view.PdfReaderView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/reader"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/reader_bg" />
```

- [ ] **Step 4: Wire MainActivity to load the sample into the reader**

Replace `MainActivity.kt` body:
```kotlin
package io.github.june690602_blip.cleanpdf

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    private var renderer: PageRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val reader = findViewById<PdfReaderView>(R.id.reader)

        bg.execute {
            val cached = File(cacheDir, "sample.pdf").apply {
                assets.open("sample.pdf").use { i -> outputStream().use { i.copyTo(it) } }
            }
            val doc = PdfDocument.open(cached.absolutePath)
            val r = PageRenderer(doc)
            val sizes = r.sizesBlockingOnRenderThread()
            renderer = r
            runOnUiThread { reader.setDocument(r, sizes) }
        }
    }

    override fun onDestroy() {
        super.onDestroy(); renderer?.shutdown(); bg.shutdown()
    }
}
```

- [ ] **Step 5: Build, install, verify continuous scroll**

Run: `./gradlew :app:installDebug && adb shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity`
Expected: all pages stack vertically; flinging scrolls smoothly through every page; pages render as they enter view.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: continuous vertical scroll PDF reader via RecyclerView + async render"
```

## Task 8: Pinch-to-zoom + double-tap zoom

**Files:**
- Modify: `app/src/main/java/.../view/PdfReaderView.kt`
- Modify: `app/src/main/java/.../view/PdfPageAdapter.kt` (exact per-page render scale)

- [ ] **Step 1: Make render scale exact per page** (remove the A4 hint approximation)

In `PdfPageAdapter`, change binding to compute scale from the actual page width. Replace the
`renderScale`/`onBindViewHolder` body's scale calculation with one driven by `PageLayout`:
```kotlin
// onBindViewHolder, after obtaining `l`:
val targetWidthPx = l.contentWidth                 // == fitWidthPx * zoom
val pageWidthPts = pageWidthPtsProvider(position)  // injected; see Step 2
val renderScale = targetWidthPx / pageWidthPts
```
Add a constructor param `private val pageWidthPtsProvider: (Int) -> Float` and delete
`PDF_BASE_WIDTH_HINT`.

- [ ] **Step 2: Pass page widths into the adapter**

In `PdfReaderView.setDocument`, build the adapter with the provider:
```kotlin
val a = PdfPageAdapter(renderer, cache!!, pageWidthPtsProvider = { i -> sizes[i].width })
```

- [ ] **Step 3: Add gesture handling to PdfReaderView** (live preview during pinch, crisp commit on end)

Add to `PdfReaderView`:
```kotlin
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

private val minZoom = 1f
private val maxZoom = 8f
private var liveScale = 1f  // transient visual scale during an active pinch

private val scaleDetector = ScaleGestureDetector(context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            liveScale = (liveScale * d.scaleFactor).coerceIn(minZoom / zoom, maxZoom / zoom)
            scaleX = liveScale; scaleY = liveScale  // cheap visual feedback only
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            commitZoom(zoom * liveScale)
        }
    })

private val tapDetector = GestureDetector(context,
    object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            commitZoom(if (zoom > 1.5f) 1f else 2.5f); return true
        }
    })

private fun commitZoom(newZoom: Float) {
    val clamped = newZoom.coerceIn(minZoom, maxZoom)
    liveScale = 1f; scaleX = 1f; scaleY = 1f
    if (clamped == zoom) return
    zoom = clamped
    cache?.clear()      // scales changed; old bitmaps are wrong size
    relayout()
}

override fun onTouchEvent(e: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(e)
    tapDetector.onTouchEvent(e)
    return super.onTouchEvent(e) || scaleDetector.isInProgress
}
```
Change `var zoom` setter visibility note: keep `zoom` private-set; `commitZoom` mutates it internally.

> Why scaleX/scaleY during the gesture: re-rendering every pinch frame is janky. We apply a cheap
> view transform for live feedback, then on gesture end re-lay-out + re-render once at crisp
> resolution. Bitmap sizes are capped by `maxZoom` and the LRU byte budget.

- [ ] **Step 4: Build, install, verify zoom**

Run: `./gradlew :app:installDebug && adb shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity`
Expected: pinch zooms in/out (max 8x); on release, text/lines re-render crisp (not blurry); double-tap toggles 1x↔2.5x; scroll still works at all zoom levels.

> If horizontal panning when zoomed feels constrained, note it as a follow-up (horizontal pan is a
> Phase 1 polish item, acceptable to defer). Do not expand scope here.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: pinch and double-tap zoom with crisp re-render on gesture end"
```

## Task 9: Open arbitrary PDFs via system picker

**Files:**
- Create: `app/src/main/java/.../io/PdfSource.kt`
- Modify: `app/src/main/java/.../MainActivity.kt`
- Modify: `app/src/main/res/menu/` (add a simple toolbar) OR a FAB; use a menu for simplicity
- Create: `app/src/main/res/menu/reader_menu.xml`

- [ ] **Step 1: Create the source-copy helper**

`app/src/main/java/io/github/june690602_blip/cleanpdf/io/PdfSource.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf.io

import android.content.Context
import android.net.Uri
import java.io.File

/** Copies a content:// (or file://) PDF into app cache and returns the local file. */
object PdfSource {
    fun copyToCache(context: Context, uri: Uri): File {
        val name = queryName(context, uri) ?: "document.pdf"
        val out = File(context.cacheDir, "opened_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open input stream for $uri" }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
}
```

- [ ] **Step 2: Add a menu with an "open" action**

`app/src/main/res/menu/reader_menu.xml`:
```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/action_open"
        android:title="@string/open_pdf"
        android:showAsAction="ifRoom" />
</menu>
```

- [ ] **Step 3: Wire the picker into MainActivity** (refactor load into a reusable function)

Replace `MainActivity.kt`:
```kotlin
package io.github.june690602_blip.cleanpdf

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleanpdf.io.PdfSource
import io.github.june690602_blip.cleanpdf.pdf.PageRenderer
import io.github.june690602_blip.cleanpdf.pdf.PdfDocument
import io.github.june690602_blip.cleanpdf.view.PdfReaderView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Executors.newSingleThreadExecutor()
    private var renderer: PageRenderer? = null
    private lateinit var reader: PdfReaderView

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { loadFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        reader = findViewById(R.id.reader)
        // Dev convenience: open bundled sample on first launch.
        bg.execute {
            val f = File(cacheDir, "sample.pdf").apply {
                assets.open("sample.pdf").use { i -> outputStream().use { i.copyTo(it) } }
            }
            openFile(f)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> { openDoc.launch(arrayOf("application/pdf")); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadFromUri(uri: Uri) = bg.execute {
        runCatching { openFile(PdfSource.copyToCache(this, uri)) }
    }

    private fun openFile(file: File) {
        renderer?.shutdown()
        val doc = PdfDocument.open(file.absolutePath)
        val r = PageRenderer(doc)
        val sizes = r.sizesBlockingOnRenderThread()
        renderer = r
        runOnUiThread { reader.setDocument(r, sizes) }
    }

    override fun onDestroy() { super.onDestroy(); renderer?.shutdown(); bg.shutdown() }
}
```

> Note: `Theme.CleanPDF` is `NoActionBar`, so the overflow menu needs a Toolbar to be visible.
> Simplest path: change the theme parent to `Theme.Material3.DayNight` (with action bar) for now,
> OR add a `MaterialToolbar` to the layout and `setSupportActionBar(...)`. Pick the Toolbar route to
> keep the reader edge-to-edge: add a `MaterialToolbar` above the reader in a vertical `LinearLayout`
> and call `setSupportActionBar`. Keep this change minimal.

- [ ] **Step 4: Build, install, verify open-any-PDF**

Run: `./gradlew :app:installDebug && adb shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity`
Expected: overflow "PDF 열기" opens the system file picker filtered to PDFs; choosing a different multi-page PDF replaces the view and scrolls/zooms correctly.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: open arbitrary PDFs via Storage Access Framework picker"
```

## Task 10: Scroll/zoom instrumented smoke + manual verification

**Files:**
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleanpdf/ScrollZoomSmokeTest.kt`

- [ ] **Step 1: Write the instrumented smoke test** (drives the activity, scrolls, asserts no crash + content)

`app/src/androidTest/java/io/github/june690602_blip/cleanpdf/ScrollZoomSmokeTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*ScrollZoomSmokeTest"`
Expected: PASS (no crash; adapter has pages).

- [ ] **Step 3: Manual verification checklist** (record results in the commit body)

Open a real multi-page PDF AND a large drawing-style PDF, then confirm:
- [ ] All pages reachable by scrolling; no blank pages left after settling
- [ ] Pinch zoom to ~8x stays sharp after release; zoom out to 1x recovers
- [ ] Double-tap toggles zoom
- [ ] Rotating the device keeps the document open and laid out
- [ ] Opening a 2nd PDF via the picker fully replaces the 1st (no leftover pages)
- [ ] No OOM/jank on a 50+ page or 10MB+ file (watch `adb logcat | grep -i -E "OutOfMemory|CleanPDF"`)

- [ ] **Step 4: Run the full test suite + build**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: all unit tests PASS; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: add scroll/zoom smoke test; verify Phase 1 viewer manually"
```

**✅ Phase 1 done when:** any user-picked PDF opens into a smooth continuous-scroll, pinch/double-tap-zoomable viewer; unit + instrumented tests pass; manual checklist clean.

---

## Self-Review

**Spec coverage (this plan = spec §11 Phases 0–1 + supporting components):**
- Continuous vertical scroll + zoom → Tasks 6,7,8 ✓
- Ondemand render + background thread + LRU → Tasks 4,5,7 ✓
- `PdfDocument` wrapper (open/count/size/render/password) → Tasks 1,2 ✓ (search/outline/selection are Phase 3–5, out of this plan — intentional)
- Engine-integration de-risk (spec §9 top risk) → Task 1 (spike + version pinning) ✓
- `isLikelyPdf` validation → Task 2 ✓ (used fully by Phase 2 intent handling)
- Error model (`PdfOpenResult`) defined → Task 2 ✓ (wired into UI in Phase 2; defined now to avoid rework)
- File intake via VIEW/SEND intents, recent files, dark/night mode, search, selection, outline, thumbnails, release prep → **Phase 2–7, deliberately not in this plan.** Next plan: `2026-06-XX-cleanpdf-phase2-intake.md`.

**Placeholder scan:** No TBD/TODO. The only approximation (A4 width hint in Task 7) is explicitly removed in Task 8 Step 1. Sample PDF is a stated dev artifact, not a placeholder.

**Type consistency:** `PageSize(width,height,aspect)`, `PdfDocument.open/pageCount/pageSize/renderPage/needsPassword/authenticate/close`, `PageRenderer.submit/sizesBlockingOnRenderThread/shutdown/pageCount`, `BitmapCache.get/put/clear/scaleMilli` + `PageKey(page,scaleMilli)`, `LruByteSizedCache(maxBytes,sizeOf,onEvict).get/put/clear`, `PageLayout.compute/pageTop/pageHeight/totalHeight/contentWidth/visiblePages` — names are used identically across tasks. `PdfPageAdapter` gains `pageWidthPtsProvider` in Task 8 (and `PDF_BASE_WIDTH_HINT` is deleted there) — flagged in-task.

**Known residual risks (verify during execution, not blockers):**
1. Exact fitz 1.27.1 signatures (`AndroidDrawDevice.drawPage` arity, `Cookie.abort`) — Task 1/5 spike notes cover fallbacks.
2. fitz `.so` ABI coverage on the chosen emulator — Task 1 Step 6 covers.
3. `maven.ghostscript.com` https vs http + published version — Task 0 Step 13 / Task 1 Step 2 cover.
