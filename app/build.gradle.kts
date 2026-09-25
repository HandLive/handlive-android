plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.handlive.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.handlive.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
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

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
