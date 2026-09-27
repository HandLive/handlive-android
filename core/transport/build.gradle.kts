// Kênh điều khiển `/v1/ctl` phía Android (S): Ktor WSS (engine Netty), TLS tự ký, bắt tay phiên, capability, rekey.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.handlive.android.core.transport"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // The instrumented test APK packs the same Netty jars as the app: drop their duplicate descriptors.
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

    testOptions {
        unitTests.all { test ->
            // Test bắt tay tất định đọc shared/test-vectors/session-handshake.json.
            val sharedDir = rootProject.layout.projectDirectory.dir("../shared")
            test.inputs
                .file(sharedDir.file("test-vectors/session-handshake.json"))
                .withPropertyName("sessionHandshakeVector")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            test.inputs
                .file(sharedDir.file("schemas/common.schema.json"))
                .withPropertyName("commonSchema")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            test.systemProperty("hl.shared.dir", sharedDir.asFile.absolutePath)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// ktor-server-core kéo theo qua nhiều đường (core, netty, websockets) nên loại ở mức cấu hình:
// - kotlin-reflect: chỉ dùng ở chế độ phát triển (tự nạp lại module) và `call.receive<T>()`; server này không dùng.
// - typesafe config: chỉ dùng khi nạp cấu hình HOCON từ file; server dựng cấu hình bằng mã.
// Chỉ áp cho `implementation` (classpath của mã và test kế thừa); không đụng classpath của trình biên dịch Kotlin.
// Test JVM chạy server Netty thật trên chính classpath đã loại này.
configurations.named("implementation") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "com.typesafe", module = "config")
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:crypto"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty) {
        // HTTP/3 (QUIC) không dùng: Ktor chỉ chạm tới các lớp này khi bật enableHttp3.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
        exclude(group = "io.netty", module = "netty-codec-http3")
        exclude(group = "io.netty", module = "netty-codec-classes-quic")
        // netty-codec 4.2 là gói gom; protobuf và JBoss Marshalling không dùng.
        exclude(group = "io.netty", module = "netty-codec-protobuf")
        exclude(group = "io.netty", module = "netty-codec-marshalling")
        // ALPN của Jetty cho JDK 8; server tắt HTTP/2 nên không cần ALPN.
        exclude(group = "org.eclipse.jetty.alpn", module = "alpn-api")
        // Giữ netty-transport-classes-epoll/kqueue: Ktor gọi Epoll/KQueue.isAvailable() khi chọn event loop.
    }
    implementation(libs.ktor.server.websockets)
    // Relay client (CONN-03): HTTPS and WebSocket with SPKI pinning of the relay's certificate chain.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.java)
    testImplementation(testFixtures(project(":core:protocol")))
    testImplementation(libs.okhttp.mockwebserver)

    // Smoke test on a real device: Netty + TLS 1.3 (Conscrypt) and the Keystore-backed TLS identity.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
