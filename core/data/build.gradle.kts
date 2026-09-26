// Local persistence of the Android hub: Room `handlive.db` (0.9.1) and the DataStore settings keys (0.9.5).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.handlive.android.core.data"
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
            // Room and DataStore run on Robolectric's Android runtime in JVM tests.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Exported schema JSON is committed so future migrations can be tested against it.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:crypto"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.tink.android)
    testImplementation(libs.androidx.test.core)
}
