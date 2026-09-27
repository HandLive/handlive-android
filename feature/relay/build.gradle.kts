// The phone's side of the relay (CONN-03, CONN-04): when to connect to /v1/relay and when to leave it, registration
// of the device and its pairs, remote revocation (PAIR-03 flow B), pairing through a rendezvous (PAIR-01), and alert
// pushes to iPhone and iPad with the push outbox. No Play Services here: FCM lives in the app's `gms` flavor.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.handlive.android.feature.relay"
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
                // Push envelopes are checked against shared/test-vectors/push-envelope.json and shared/schemas.
                val sharedDir = rootProject.layout.projectDirectory.dir("../shared")
                test.inputs
                    .files(sharedDir.file("test-vectors/push-envelope.json"), sharedDir.dir("schemas"))
                    .withPropertyName("sharedPushFiles")
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
    implementation(project(":feature:pairing"))
    implementation(project(":feature:sms"))
    implementation(libs.androidx.core)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.json.schema.validator)
    testImplementation(testFixtures(project(":core:protocol")))
}
