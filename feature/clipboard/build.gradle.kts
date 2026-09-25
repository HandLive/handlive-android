// Clipboard sync on the phone: CLIP-01 (Accessibility copy detection, ClipboardReadActivity, notification button,
// Quick Settings tile, Share target), CLIP-02 (writes and forwarding), CLIP-03 (chunked images) and CLIP-05
// (safe auto-clear). The logic lives in `module/` behind interfaces so it runs in JVM tests.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.handlive.android.feature.clipboard"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":feature:connection"))
    implementation(project(":core:design"))
    implementation(project(":core:strings"))
    implementation(libs.androidx.core)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.tink.android)
    testImplementation(libs.room.runtime)
}
