// A development-only probe for gate G6 (plans/20260928-web-handoff/plan.md W3, W8): an AccessibilityService limited
// to the supported browsers that logs which page is open (host and a salted hash only) so the adapters, the
// private-mode detection and the cost of the service can be measured on real devices. No network permission.
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
    namespace = "app.handlive.spike.web"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.handlive.spike.web"
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
