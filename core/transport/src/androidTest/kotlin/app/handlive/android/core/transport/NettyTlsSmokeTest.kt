package app.handlive.android.core.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.handlive.android.core.transport.server.ControlServer
import app.handlive.android.core.transport.server.ControlServerConfig
import app.handlive.android.core.transport.server.ControlServerOptions
import app.handlive.android.core.transport.tls.AndroidTlsIdentityStorage
import app.handlive.android.core.transport.tls.TlsIdentity
import app.handlive.android.core.transport.tls.TlsIdentityProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Runs on a real phone (Pixel, Samsung): the Ktor/Netty server of A-SVC with the on-device self-signed ECDSA
 * certificate negotiates TLS 1.3 through the platform provider (Conscrypt), upgrades `/v1/ctl` to a WebSocket,
 * answers a malformed `session/hello` with close 4400, refuses TLS 1.2, and the Keystore-backed TLS identity
 * survives a reload. JVM tests cover the protocol; this covers what only Android can break.
 *
 * `./gradlew :core:transport:connectedDebugAndroidTest` with a device attached.
 */
@RunWith(AndroidJUnit4::class)
class NettyTlsSmokeTest {
    private val identity = TlsIdentity.generate()
    private val server =
        ControlServer(
            ControlServerConfig(
                tls = identity,
                localDeviceId = "39f713d0-a644-853f-8452-9421b9f51b9b",
                pairs = { null },
                localCapability = { error("no session is expected") },
                options = ControlServerOptions(host = "127.0.0.1", ports = listOf(0)),
            ),
        )
    private val port = runBlocking { server.start() }

    @After
    fun tearDown() = server.stop()

    @Test
    fun tls13HandshakeUpgradesCtlAndMalformedHelloIsClosedWith4400() {
        openTls("TLSv1.3").use { socket ->
            assertEquals("TLSv1.3", socket.session.protocol)
            val input = DataInputStream(socket.inputStream)
            socket.outputStream.write(upgradeRequest("/v1/ctl").toByteArray(Charsets.US_ASCII))
            assertTrue(readHeaders(input).startsWith("HTTP/1.1 101"))

            socket.outputStream.write(maskedTextFrame("""{"v":1,"type":"session"}"""))
            val (opcode, payload) = readFrame(input)
            assertEquals(CLOSE_OPCODE, opcode)
            val code = ((payload[0].toInt() and BYTE) shl BITS_PER_BYTE) or (payload[1].toInt() and BYTE)
            assertEquals(WsCloseCode.BAD_REQUEST.toInt(), code)
        }
    }

    @Test
    fun unknownPathIsNotFound() {
        openTls("TLSv1.3").use { socket ->
            socket.outputStream.write(upgradeRequest("/v1/other").toByteArray(Charsets.US_ASCII))
            assertTrue(readHeaders(DataInputStream(socket.inputStream)).startsWith("HTTP/1.1 404"))
        }
    }

    @Test
    fun tls12IsRefused() {
        assertThrows(SSLException::class.java) { openTls("TLSv1.2").close() }
    }

    @Test
    fun keystoreBackedIdentityIsReloadedUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = AndroidTlsIdentityStorage.create(context)
        val first = TlsIdentityProvider.loadOrCreate(storage)
        val second = TlsIdentityProvider.loadOrCreate(AndroidTlsIdentityStorage.create(context))
        assertArrayEquals(first.certificateSha256(), second.certificateSha256())
    }

    private fun openTls(protocol: String): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(identity)), null) }
        val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.enabledProtocols = arrayOf(protocol)
        socket.soTimeout = TIMEOUT_MILLIS
        socket.startHandshake()
        return socket
    }

    /** Trusts exactly the server certificate by its SHA-256, like the client pin of CONN-01 API 3. */
    private class PinnedTrust(
        identity: TlsIdentity,
    ) : X509TrustManager {
        private val pin = identity.certificateSha256()

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
            val actual = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)
            if (!actual.contentEquals(pin)) throw CertificateException("TLS_PIN_MISMATCH")
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = throw CertificateException("unused")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
        const val CLOSE_OPCODE = 0x8
        const val TEXT_FIN = 0x81
        const val MASK_BIT = 0x80
        const val OPCODE_MASK = 0x0f
        const val LENGTH_MASK = 0x7f
        const val EXTENDED_16 = 126
        const val MASK_SIZE = 4
        const val KEY_SIZE = 16
        const val BYTE = 0xff
        const val BITS_PER_BYTE = 8

        fun upgradeRequest(path: String): String {
            val key = Base64.getEncoder().encodeToString(ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) })
            return "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        }

        fun readHeaders(input: InputStream): String {
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) headers.append(input.read().toChar())
            return headers.toString()
        }

        /** A client frame must be masked (RFC 6455 §5.3); payloads here stay below 126 bytes. */
        fun maskedTextFrame(text: String): ByteArray {
            val payload = text.toByteArray(Charsets.UTF_8)
            check(payload.size < EXTENDED_16)
            val mask = ByteArray(MASK_SIZE).also { SecureRandom().nextBytes(it) }
            val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % MASK_SIZE].toInt()).toByte() }
            return byteArrayOf(TEXT_FIN.toByte(), (MASK_BIT or payload.size).toByte()) + mask + masked
        }

        /** Reads one unmasked server frame with a payload below 126 bytes. */
        fun readFrame(input: DataInputStream): Pair<Int, ByteArray> {
            val opcode = input.readUnsignedByte() and OPCODE_MASK
            val length = input.readUnsignedByte() and LENGTH_MASK
            check(length < EXTENDED_16)
            return opcode to ByteArray(length).also { input.readFully(it) }
        }
    }
}
