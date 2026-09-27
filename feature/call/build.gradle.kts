// Calls on the phone (A-CALL, group 6): the call context from the public call state APIs and the PHONE_STATE
// broadcast (CALL-01), answering, declining and ending through TelecomManager (CALL-02, CALL-03 over WebSocket), and
// the call log with its ContentObserver (CALL-04). No InCallService (C12). The logic lives behind interfaces
// (`context/`, `module/`, `log/`) so it runs in JVM tests; `system/` holds the Android side.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.handlive.android.feature.call"
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
            all { test ->
                // The emitted call_event messages are checked against shared/schemas.
                val sharedDir = rootProject.layout.projectDirectory.dir("../shared")
                test.inputs
                    .dir(sharedDir.dir("schemas"))
                    .withPropertyName("sharedSchemas")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                test.systemProperty("hl.shared.dir", sharedDir.asFile.absolutePath)
            }
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
    testImplementation(libs.json.schema.validator)
    testImplementation(testFixtures(project(":core:protocol")))
}
