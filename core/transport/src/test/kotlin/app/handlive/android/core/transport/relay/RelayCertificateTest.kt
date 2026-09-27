package app.handlive.android.core.transport.relay

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * CONN-03 E7 on both relay paths: a certificate refused because no pin matches or because the platform does not trust
 * the chain — here a relay whose key is pinned but whose CA the JVM does not know, as behind a foreign CA.
 */
class RelayCertificateTest {
    private val tls = TestRelayTls()
    private val server =
        MockWebServer().apply {
            useHttps(tls.serverSockets, false)
            start()
        }
    private val config = RelayConfig("${server.hostName}:${server.port}", listOf(tls.pin))
    private val client = OkHttpRelayTransport.client(config)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun aChainThePlatformDoesNotTrustIsRefusedOnTheRestCalls() =
        runBlocking {
            val failure =
                runCatching {
                    OkHttpRelayTransport.http(client, config).send("POST", "/v1/auth/challenge", "{}", null)
                }.exceptionOrNull()
            assertTrue("$failure", failure is RelayPinMismatchException)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun aChainThePlatformDoesNotTrustIsRefusedOnTheLink() =
        runBlocking {
            val link = OkHttpRelayLinkFactory(client, config).open("jwt")
            val closed = withTimeout(WAIT) { link.events.receive() } as RelayLinkEvent.Closed
            assertTrue(closed.pinMismatch)
            assertNull(closed.httpStatus)
        }

    @Test
    fun aRelayThatSpeaksNoTlsIsOnlyUnreachable() =
        runBlocking {
            val plain = MockWebServer().apply { start() }
            try {
                val plainConfig = RelayConfig("${plain.hostName}:${plain.port}", listOf(tls.pin))
                val http = OkHttpRelayTransport.http(OkHttpRelayTransport.client(plainConfig), plainConfig)
                val failure = runCatching { http.send("GET", "/v1/pairs", null, null) }.exceptionOrNull()
                assertTrue("$failure", failure is RelayUnreachableException && failure !is RelayPinMismatchException)
            } finally {
                plain.shutdown()
            }
        }

    @Test
    fun aRestCallReportsARefusedCertificateBeforeItFails() =
        runBlocking {
            var refused = 0
            val http = OkHttpRelayTransport.http(client, config).reportingRefusedCertificates { refused++ }
            val failure = runCatching { http.send("GET", "/v1/pairs", null, "jwt") }.exceptionOrNull()
            assertTrue("$failure", failure is RelayPinMismatchException)
            assertEquals(1, refused)

            val offline =
                RelayHttp { _, _, _, _ -> throw RelayUnreachableException("offline") }
                    .reportingRefusedCertificates { refused++ }
            assertTrue(runCatching { offline.send("GET", "/v1/pairs", null, "jwt") }.isFailure)
            val answered = RelayHttp { _, _, _, _ -> RelayResponse(401, "") }.reportingRefusedCertificates { refused++ }
            assertEquals(401, answered.send("GET", "/v1/pairs", null, "jwt").status)
            assertEquals("only the refused certificate is reported", 1, refused)
        }

    @Test
    fun onlyCertificateFailuresOfTheHandshakeCountAsRefused() {
        assertTrue(SSLPeerUnverifiedException("Certificate pinning failure!").refusesCertificate())
        val untrusted =
            SSLHandshakeException("handshake").apply {
                initCause(
                    CertificateException(CertPathValidatorException("Trust anchor for certification path not found.")),
                )
            }
        assertTrue(untrusted.refusesCertificate())
        assertFalse(SSLHandshakeException("Remote host terminated the handshake").refusesCertificate())
        assertFalse(IOException("timeout").refusesCertificate())
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
