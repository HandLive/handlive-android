package app.handlive.android.core.transport.relay

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay's OkHttp client (0.4.3): the pins apply to the host name of `RELAY_HOST`, whatever its port. */
class OkHttpRelayTransportTest {
    private val tls = TestRelayTls()
    private val server =
        MockWebServer().apply {
            useHttps(tls.serverSockets, false)
            start()
        }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun theHostNameOfARelayHostWithAPortIsPinned() {
        val pins = RelayConfig.DEFAULT_PINS + tls.pin
        mapOf(
            "10.0.2.2:18443" to "10.0.2.2",
            "relay.example.com:8443" to "relay.example.com",
            "relay.example.com" to "relay.example.com",
        ).forEach { (relayHost, name) ->
            val pinner = OkHttpRelayTransport.client(RelayConfig(relayHost, pins)).certificatePinner
            assertEquals(relayHost, pins.size, pinner.findMatchingPins(name).size)
        }
    }

    @Test
    fun aRelayOnAnotherPortIsReachedWithItsPinsChecked() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"pairs":[]}"""))
            assertEquals(200, pinned(listOf(tls.pin)).send("GET", "/v1/pairs", null, "jwt").status)
            assertEquals("Bearer jwt", server.takeRequest().getHeader("Authorization"))

            val refused = runCatching { pinned(RelayConfig.DEFAULT_PINS).send("GET", "/v1/pairs", null, "jwt") }
            assertTrue("${refused.exceptionOrNull()}", refused.exceptionOrNull() is RelayPinMismatchException)
            assertEquals(1, server.requestCount)
        }

    /** The REST client of a relay at this server's `host:port` with [pins], the test certificate trusted. */
    private fun pinned(pins: List<String>): RelayHttp {
        val config = RelayConfig("${server.hostName}:${server.port}", pins)
        return OkHttpRelayTransport.http(tls.trusting(OkHttpRelayTransport.client(config)), config)
    }
}
