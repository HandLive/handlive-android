// App-call spike (Phase 3 extension, card T3.2): a standalone Gradle build, NOT included in the product build or CI.
// It reuses the product's version catalog so the Android Gradle Plugin and Kotlin versions stay in step. Build from the
// repository root:
//   ./gradlew -p tools/call-spike assembleDebug testDebugUnitTest
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "HandLiveCallSpike"
