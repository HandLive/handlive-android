// A development-only probe for the app-call extension (hub plans/20260925-implementation/phase-03-cuoc-goi-app.md,
// card T3.2): a NotificationListenerService that logs the shape of calling apps' call notifications (never names or
// numbers), sends their answer/decline/hang-up intents from the background on an adb command, and tries to move the
// call audio to a Bluetooth HFP device. No network permission.
plugins {
    alias(libs.plugins.android.application)
    // Not applied: puts the product's Kotlin Gradle Plugin version on the classpath instead of AGP's default.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlint)
    android.set(true)
}

android {
    namespace = "app.handlive.spike.call"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.handlive.spike.call"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
