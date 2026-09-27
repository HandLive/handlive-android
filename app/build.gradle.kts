plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Build-time settings that are not in git: a Gradle property (`~/.gradle/gradle.properties`, `-P`) or an environment
 * variable; empty when neither is set, and the build still succeeds (CI builds without secrets).
 */
fun buildSetting(
    property: String,
    environment: String,
): String =
    providers
        .gradleProperty(property)
        .orElse(providers.environmentVariable(environment))
        .orElse("")
        .get()

/** A Kotlin string literal for `buildConfigField`. */
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.handlive.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.handlive.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        // CONN-03: `{RELAY_HOST}` is configured at build time (0.4.3); without it the build has no relay. The pins are
        // ISRG Root X1 and X2 (in the code) plus the project's backup key, `sha256/<base64>` values separated by commas.
        buildConfigField("String", "RELAY_HOST", quoted(buildSetting("handlive.relayHost", "HANDLIVE_RELAY_HOST")))
        buildConfigField(
            "String",
            "RELAY_EXTRA_PINS",
            quoted(buildSetting("handlive.relayExtraPins", "HANDLIVE_RELAY_EXTRA_PINS")),
        )
    }

    // Plan decision I8: the default flavor ships no Play Services and no Firebase (F-Droid and direct APK); `gms` adds
    // FCM wake-ups (CONN-04). Firebase options come from Gradle properties or the environment, never from a
    // google-services.json in git; without them the gms build runs without push.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            isDefault = true
        }
        create("gms") {
            dimension = "distribution"
            buildConfigField(
                "String",
                "FCM_APPLICATION_ID",
                quoted(buildSetting("handlive.fcm.applicationId", "HANDLIVE_FCM_APPLICATION_ID")),
            )
            buildConfigField(
                "String",
                "FCM_API_KEY",
                quoted(buildSetting("handlive.fcm.apiKey", "HANDLIVE_FCM_API_KEY")),
            )
            buildConfigField(
                "String",
                "FCM_PROJECT_ID",
                quoted(buildSetting("handlive.fcm.projectId", "HANDLIVE_FCM_PROJECT_ID")),
            )
            buildConfigField(
                "String",
                "FCM_SENDER_ID",
                quoted(buildSetting("handlive.fcm.senderId", "HANDLIVE_FCM_SENDER_ID")),
            )
        }
    }

    buildTypes {
        debug {
            // en-XA (long accented text) and ar-XB (right-to-left) catch clipping and hard-coded text (0.12.5).
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        // Only the catalog languages ship; libraries' other translations are dropped. Pseudo-locales exist in debug.
        localeFilters += listOf("en", "vi", "en-rXA", "ar-rXB")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            // Tệp mô tả trùng lặp giữa các jar Netty (Ktor server); không cần lúc chạy.
            excludes +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                    "META-INF/license/*",
                    "META-INF/native-image/**",
                )
        }
        // Không cần excludes cho jniLibs: `:core:transport` đã loại QUIC, còn epoll/kqueue không kèm tệp .so
        // (APK chỉ còn libandroidx.graphics.path.so của Compose).
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Lint the app together with its modules: a string resource added anywhere without its Vietnamese
        // translation fails MissingTranslation against the generated catalog (lint.xml, 0.12.5).
        checkDependencies = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:crypto"))
    implementation(project(":core:transport"))
    implementation(project(":core:design"))
    implementation(project(":core:data"))
    implementation(project(":feature:connection"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:clipboard"))
    implementation(project(":feature:sms"))
    implementation(project(":feature:relay"))

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    "gmsImplementation"(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// The flavor sources go through detekt too, and `check` compiles the gms flavor without any Firebase setting, as CI
// does (the UI tests are the same for both flavors; they run once, on the default flavor).
detekt {
    source.from("src/foss/kotlin", "src/gms/kotlin")
}

tasks.named("check") {
    dependsOn("compileGmsDebugKotlin")
}

androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "gms")) { variant ->
        variant.hostTests.values.forEach { it.enable = false }
    }
}
