// A fake calling app that the end-to-end harness drives over adb (CALL-05): it rings with a CallStyle notification,
// goes in call with an ordinary ongoing notification like Telegram's, shows an unrelated upload, and logs every intent
// it receives. Debug only; no network permission, no data of any kind.
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
    namespace = "app.handlive.e2e.fakecall"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.handlive.e2e.fakecall"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-e2e"
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
    implementation(libs.androidx.core)
}
