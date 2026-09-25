// A-SVC: HandLiveService (foreground service connectedDevice), the TLS server lifecycle, mDNS advertising with
// hourly hints and the routing of decrypted envelopes to feature modules (CONN-01, CONN-02, SET-01 API 3).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.handlive.android.feature.connection"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The instrumented test APK packs the Netty jars of core:transport: drop their duplicate descriptors.
    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                    "META-INF/license/*",
                    "META-INF/native-image/**",
                )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Discovery hint tests use the PRKs of shared/test-vectors/pair-prk.json.
                val sharedDir = rootProject.layout.projectDirectory.dir("../shared")
                test.inputs
                    .file(sharedDir.file("test-vectors/pair-prk.json"))
                    .withPropertyName("pairPrkVector")
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
    api(project(":core:transport"))
    api(project(":core:data"))
    implementation(project(":core:strings"))
    implementation(project(":core:design"))
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:protocol")))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
