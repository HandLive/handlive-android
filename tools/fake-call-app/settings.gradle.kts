// A fake calling app for the end-to-end harness (handlive-shared tools/e2e, scenario `app_calls`): a standalone
// Gradle build, NOT included in the product build, its APK or CI. It reuses the product's version catalog so the
// Android Gradle Plugin and Kotlin versions stay in step. From the repository root:
//   ./gradlew -p tools/fake-call-app assembleDebug
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

rootProject.name = "HandLiveFakeCallApp"
