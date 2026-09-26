package app.handlive.android.core.transport.relay

import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.relay.RelayIncoming
import app.handlive.android.core.protocol.relay.RelayWire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The `/v1/relay` WebSocket (CONN-03 API 4): bearer header, frames both ways, refused upgrades, closes. */
class RelayLinkTest {
    private val server = MockWebServer().apply { start() }
    private val config =
        RelayConfig.unpinned(
            server.url("/").toString().trimEnd('/'),
            server.url("/v1/relay").toString().replace("http", "ws"),
        )
    private val factory = OkHttpRelayLinkFactory(OkHttpRelayTransport.client(config), config)
    private val fromPhone = LinkedBlockingQueue<String>()
    private var relaySide: WebSocket? = null

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun framesFlowBothWaysWithTheBearerToken() =
        runBlocking {
            server.enqueue(MockResponse().withWebSocketUpgrade(relay()))
            val link = factory.open("jwt-1")
            assertTrue(withTimeout(WAIT) { link.events.receive() } is RelayLinkEvent.Opened)
            assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))

            val presence = """{"op":"presence","pair_id":"$PAIR","peer_device_id":"$PEER","online":true}"""
            relaySide!!.send(presence)
            val text = withTimeout(WAIT) { link.events.receive() } as RelayLinkEvent.Text
            assertTrue(RelayWire.decode(text.text) is RelayIncoming.Presence)

            assertTrue(link.sendText(RelayWire.outbound(PEER, EnvelopeCodec.decode(ENVELOPE))))
            assertEquals("""{"to":"$PEER","env":$ENVELOPE}""", fromPhone.poll(WAIT, TimeUnit.MILLISECONDS))

            relaySide!!.close(1000, "bye")
            val closed = withTimeout(WAIT) { link.events.receive() } as RelayLinkEvent.Closed
            assertEquals(1000, closed.code)
        }

    @Test
    fun aRefusedUpgradeCarriesItsStatusAndCode() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":{"code":"TOKEN_EXPIRED","message":"expired"}}"""),
            )
            val link = factory.open("old")
            val closed = withTimeout(WAIT) { link.events.receive() } as RelayLinkEvent.Closed
            assertEquals(401, closed.httpStatus)
            assertEquals("TOKEN_EXPIRED", closed.errorCode)
        }

    private fun relay() =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                relaySide = webSocket
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                fromPhone.add(text)
            }
        }

    private companion object {
        const val WAIT = 5_000L
        const val PAIR = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val PEER = "21fe31df-a154-8261-a26b-f854046fd227"
        const val ENVELOPE =
            """{"v":1,"type":"sms","id":"0192f3e2-4b5d-7e6f-8a70-9b0c1d2e3f40","ts":1727150170000,"payload":"AAAA"}"""
    }
}
