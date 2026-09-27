package app.handlive.android.core.transport.relay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import javax.net.ssl.SSLPeerUnverifiedException

/** What happens on the `/v1/relay` WebSocket (CONN-03 API 4). */
sealed interface RelayLinkEvent {
    data object Opened : RelayLinkEvent

    class Text(
        val text: String,
    ) : RelayLinkEvent

    /** An `HR` frame (0.4.3); the stream channels of Phase 4 use them. */
    class Binary(
        val bytes: ByteArray,
    ) : RelayLinkEvent

    /**
     * The link ended: closed by either side ([code]), refused at the upgrade ([httpStatus] 401 `TOKEN_EXPIRED`, 404
     * `DEVICE_NOT_FOUND`, 410 `DEVICE_REVOKED`, with [errorCode]), or failed ([pinMismatch] for CONN-03 E7).
     */
    class Closed(
        val code: Int?,
        val httpStatus: Int? = null,
        val errorCode: String? = null,
        val pinMismatch: Boolean = false,
    ) : RelayLinkEvent
}

/** One open `/v1/relay` connection: send text (wrappers, control ops) and binary (`HR`) frames, or close it. */
interface RelayLink {
    val events: ReceiveChannel<RelayLinkEvent>

    fun sendText(text: String): Boolean

    fun sendBinary(bytes: ByteArray): Boolean

    fun close(
        code: Int,
        reason: String,
    )
}

/** Opens `/v1/relay` with the device JWT (`Authorization: Bearer`, CONN-03 API 4). */
fun interface RelayLinkFactory {
    fun open(bearer: String): RelayLink
}

/** [RelayLinkFactory] on OkHttp: pinned TLS, WebSocket pings every 15 s ([OkHttpRelayTransport.client]). */
class OkHttpRelayLinkFactory(
    private val client: OkHttpClient,
    private val config: RelayConfig,
) : RelayLinkFactory {
    override fun open(bearer: String): RelayLink {
        val events = Channel<RelayLinkEvent>(Channel.UNLIMITED)
        val request =
            Request
                .Builder()
                .url(config.webSocketUrl)
                .header("Authorization", "Bearer $bearer")
                .build()
        val socket = client.newWebSocket(request, Listener(events))
        return object : RelayLink {
            override val events = events

            override fun sendText(text: String) = socket.send(text)

            override fun sendBinary(bytes: ByteArray) = socket.send(bytes.toByteString())

            override fun close(
                code: Int,
                reason: String,
            ) {
                if (!socket.close(code, reason)) socket.cancel()
            }
        }
    }

    private class Listener(
        private val events: Channel<RelayLinkEvent>,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            events.trySend(RelayLinkEvent.Opened)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            events.trySend(RelayLinkEvent.Text(text))
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            events.trySend(RelayLinkEvent.Binary(bytes.toByteArray()))
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            events.trySend(RelayLinkEvent.Closed(code))
            events.close()
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            val body = response?.let { runCatching { it.body?.string() }.getOrNull() }.orEmpty()
            val errorCode = response?.let { RelayResponse(it.code, body).errorCode }
            events.trySend(
                RelayLinkEvent.Closed(null, response?.code, errorCode, pinMismatch = t is SSLPeerUnverifiedException),
            )
            events.close()
        }
    }
}
