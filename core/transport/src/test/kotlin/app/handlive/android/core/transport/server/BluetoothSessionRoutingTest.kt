package app.handlive.android.core.transport.server

import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityOp
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.TransportConstants
import app.handlive.android.core.transport.bluetooth.BluetoothLinkAdmission
import app.handlive.android.core.transport.handshake.PairRecord
import app.handlive.android.core.transport.session.SessionCipher
import app.handlive.android.core.transport.testing.LoopbackServerFixture
import app.handlive.android.core.transport.testing.TestClientPeer
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `ControlServer.serveBluetoothPeer` nhận một liên kết đã qua bước 5 (admission kênh nằm ở `BluetoothLinkAcceptor`):
 * KHÔNG qua [ConnectionAdmission] theo địa chỉ IP (BLE không có địa chỉ ổn định), `AUTH_FAILED` của `session/hello`
 * đếm vào [BluetoothLinkAdmission], và hello phải nêu đúng cặp của psk liên kết (0.4.5 bước 6). Không đụng radio/TLS
 * thật: [FakeSocket] là một đôi socket thuần trong bộ nhớ, gọi trực tiếp `serveBluetoothPeer` không qua Netty.
 */
class BluetoothSessionRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pairs = ConcurrentHashMap<String, PairRecord>()
    private val established = LinkedBlockingQueue<ControlSession>()
    private val bluetoothAdmission = BluetoothLinkAdmission(authFailBlock = AUTH_FAIL_BLOCK, authFailWindow = 1.minutes)
    private val server =
        ControlServer(
            ControlServerConfig(
                tls = LoopbackServerFixture.sharedIdentity,
                localDeviceId = SERVER_DEVICE_ID,
                pairs = { pairs[it] },
                localCapability = { LoopbackServerFixture.ANDROID_CAPABILITY },
                onSessionEstablished = { established.add(it) },
                options = ControlServerOptions(handshakeTimeout = 3.seconds),
            ),
            bluetoothAdmission = bluetoothAdmission,
        )

    @After
    fun tearDown() = scope.cancel()

    private fun addPair(): TestClientPeer {
        val pairId = UUID.randomUUID().toString()
        val clientDeviceId = UuidV7Generator().next()
        val prk = SecureRandomBytes.next(PRK_SIZE)
        pairs[pairId] = PairRecord(pairId, clientDeviceId, prk, revoked = false)
        return TestClientPeer(pairId, clientDeviceId, prk, SERVER_DEVICE_ID)
    }

    @Test
    fun aHandshakeOverBluetoothGetsTransportBluetoothNotLan() =
        runBlocking {
            val peer = addPair()
            val (clientEnd, serverEnd) = FakeSocket.pair(scope)
            scope.launch { server.serveBluetoothPeer(serverEnd, peer.pairId) }
            completeHandshake(peer, clientEnd)
            val session = established.poll(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(SessionTransport.BLUETOOTH, session?.transport)
        }

    @Test
    fun manyBluetoothHandshakesAreNotRateLimitedByTheLanPerAddressCap() =
        runBlocking {
            // More than the LAN's per-address cap (4) — none of these go through ConnectionAdmission at all.
            repeat(TransportConstants.MAX_UNAUTHENTICATED_PER_ADDRESS + 2) {
                val peer = addPair()
                val (clientEnd, serverEnd) = FakeSocket.pair(scope)
                val job = scope.launch { server.serveBluetoothPeer(serverEnd, peer.pairId) }
                completeHandshake(peer, clientEnd)
                assertEquals(SessionTransport.BLUETOOTH, established.poll(WAIT_SECONDS, TimeUnit.SECONDS)?.transport)
                job.cancelAndJoin()
            }
        }

    @Test
    fun authFailedOverBluetoothCountsTowardTheListenerWideBlockNotThePerAddressOne() =
        runBlocking {
            val goodPeer = addPair()
            repeat(AUTH_FAIL_BLOCK) {
                val (clientEnd, serverEnd) = FakeSocket.pair(scope)
                val job = scope.launch { server.serveBluetoothPeer(serverEnd, goodPeer.pairId) }
                val wrongKey = SecureRandomBytes.next(PRK_SIZE)
                val wrongKeyPeer = TestClientPeer(goodPeer.pairId, goodPeer.deviceId, wrongKey, SERVER_DEVICE_ID)
                val (hello, _) = wrongKeyPeer.hello()
                clientEnd.outgoing.send(Frame.Text(hello))
                withTimeout(WAIT_MILLIS) { job.join() }
            }
            assertTrue(bluetoothAdmission.isBlocked)
        }

    @Test
    fun aHelloForAnotherPairThanTheLinkPskIsAuthFailedAndCounted() =
        runBlocking {
            // 0.4.5 step 6: session/hello must name the pair whose K_bt_link verified noise1.
            val linkPair = addPair()
            val otherPair = addPair()
            repeat(AUTH_FAIL_BLOCK) {
                val (clientEnd, serverEnd) = FakeSocket.pair(scope)
                val job = scope.launch { server.serveBluetoothPeer(serverEnd, linkPair.pairId) }
                val (hello, _) = otherPair.hello()
                clientEnd.outgoing.send(Frame.Text(hello))
                withTimeout(WAIT_MILLIS) { job.join() }
            }
            assertTrue(bluetoothAdmission.isBlocked)
            assertTrue(established.isEmpty())
        }

    /** The client side of 0.6.3 steps 1-4, through [socket] directly — no TLS, no real network. */
    private suspend fun completeHandshake(
        peer: TestClientPeer,
        socket: WebSocketSession,
    ) {
        val (hello, state) = peer.hello()
        socket.outgoing.send(Frame.Text(hello))
        val welcomeText = (withTimeout(WAIT_MILLIS) { socket.incoming.receive() } as Frame.Text).readText()
        val keys = peer.acceptWelcome(welcomeText, state)
        val cipher = SessionCipher(keys, PeerRole.CLIENT)
        val macCapability = LoopbackServerFixture.MAC_CAPABILITY
        val capability = PlaintextCodec.encodeOp(CapabilityOp.HELLO, CapabilityData.serializer(), macCapability)
        val header = EnvelopeHeader(MessageType.CAPABILITY.wire, peer.ids.next(), System.currentTimeMillis())
        val envelope = cipher.seal(header, capability)
        socket.outgoing.send(Frame.Text(EnvelopeCodec.encode(envelope)))
        withTimeout(WAIT_MILLIS) { socket.incoming.receive() } // capability/hello of the phone
    }

    /** A plain in-memory pair of [WebSocketSession] — no Netty, no TLS, just two cross-wired frame channels. */
    private class FakeSocket(
        parent: CoroutineScope,
        override val incoming: ReceiveChannel<Frame>,
        override val outgoing: SendChannel<Frame>,
    ) : WebSocketSession {
        override val coroutineContext: CoroutineContext = parent.coroutineContext
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Envelope.MAX_BYTES.toLong()

        override suspend fun flush() = Unit

        @Deprecated("Use cancel() instead.", ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
        override fun terminate() = Unit

        companion object {
            fun pair(scope: CoroutineScope): Pair<WebSocketSession, WebSocketSession> {
                val clientToServer = Channel<Frame>(Channel.UNLIMITED)
                val serverToClient = Channel<Frame>(Channel.UNLIMITED)
                val client = FakeSocket(scope, incoming = serverToClient, outgoing = clientToServer)
                val server = FakeSocket(scope, incoming = clientToServer, outgoing = serverToClient)
                return client to server
            }
        }
    }

    private companion object {
        const val SERVER_DEVICE_ID = "39f713d0-a644-853f-8452-9421b9f51b9c"
        const val PRK_SIZE = 32
        const val AUTH_FAIL_BLOCK = 3
        const val WAIT_SECONDS = 5L
        const val WAIT_MILLIS = 5_000L
    }
}
