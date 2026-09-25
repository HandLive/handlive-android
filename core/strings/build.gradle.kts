// UI strings of every Android module, generated from ../shared/strings/ui-strings.json (detailed design 0.12.2).
import app.handlive.buildlogic.strings.GenerateStringResourcesTask

plugins {
    alias(libs.plugins.android.library)
}

val generatedResDir = layout.buildDirectory.dir("generated/res/uiStrings")
val uiStringCatalog = rootProject.layout.projectDirectory.file("../shared/strings/ui-strings.json")

android {
    namespace = "app.handlive.android.core.strings"
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
            // The catalog test reads the generated resources through Robolectric in both languages.
            isIncludeAndroidResources = true
            all { test ->
                test.inputs.file(uiStringCatalog).withPathSensitivity(PathSensitivity.NONE)
                test.systemProperty("hl.catalog.file", uiStringCatalog.asFile.absolutePath)
                // The source guard scans every module of this repository.
                test.systemProperty("hl.android.root", rootProject.projectDir.absolutePath)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Regenerated at every build from the catalog; never committed, so the resources cannot drift from it.
val generateStringResources =
    tasks.register<GenerateStringResourcesTask>("generateStringResources") {
        catalogFile.set(uiStringCatalog)
        platform.set("android")
        outputDirectory.set(generatedResDir)
    }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateStringResources,
            GenerateStringResourcesTask::outputDirectory,
        )
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.serialization.json)
}
