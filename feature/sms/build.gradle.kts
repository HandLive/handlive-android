// SMS on the phone (A-SMS, group 5): history sync with cursors (SMS-01, SMS-03), new messages through a
// ContentObserver without RECEIVE_SMS (SMS-02, C18), sending through SmsManager (SMS-04) and read status (SMS-05).
// The logic lives behind interfaces (`provider/`, `send/SmsRadio`) so it runs in JVM tests.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.handlive.android.feature.sms"
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
    implementation(project(":core:strings"))
    implementation(project(":core:design"))
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.runtime)
}
