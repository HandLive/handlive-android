package app.handlive.android.feature.pairing.revoke

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.NewPair
import app.handlive.android.core.data.pairing.PeerKeys
import app.handlive.android.core.data.pairing.SignedAttestation
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.protocol.pairing.PairRevokeData
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.connection.SessionEnded
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.connection.session.SessionRouter
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** PAIR-03 flow A on the phone: initiator (E2 included), receiver of `pair/revoke`, and the `bye revoked` fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnpairControllerTest {
    private val pairs = PairStoreFixture()
    private val pairId = UUID.randomUUID().toString()
    private val ids = UuidV7Generator()
    private val router = SessionRouter()
    private val sent = mutableListOf<Pair<MessageType, ByteArray>>()
    private val closed = mutableListOf<Pair<String, String>>()
    private var answerRevoke = true
    private lateinit var scope: CoroutineScope
    private lateinit var session: PeerSession

    @After
    fun tearDown() = pairs.database.close()

    private fun newSession(channel: PeerSession.Channel = PeerSession.Channel.LAN) =
        PeerSession(
            peer = PeerSession.PeerInfo(pairId, "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718", "MacBook", PeerPlatform.MACOS),
            channel = channel,
            effectiveFeatures = MutableStateFlow(emptySet()),
            peerCapability = MutableStateFlow(null),
            sender = { type, plaintext, id ->
                sent += type to plaintext
                if (type == MessageType.PAIR && answerRevoke) {
                    // The Mac acknowledges before it cleans up (API 1 rule 1).
                    scope.launch {
                        router.route(
                            session,
                            inbound(MessageType.ACK, PlaintextCodec.encodeAck(Ack.success(id))),
                        )
                    }
                }
            },
            clock = System::currentTimeMillis,
        )

    private fun controller(withSession: Boolean) =
        UnpairController(pairs.store, { if (withSession) mapOf(pairId to session) else emptyMap() }) { id, reason ->
            closed += id to reason
        }

    @Test
    fun connectedClientAcknowledgesAndBothSidesAreDone() =
        runTest {
            scope = backgroundScope
            session = newSession()
            pairs.store.save(pair(), ByteArray(32))
            assertEquals(UnpairResult.DONE, controller(withSession = true).unpair(pairId))
            val (type, plaintext) = sent.single()
            assertEquals(MessageType.PAIR, type)
            val request = PlaintextCodec.decodeOp(plaintext, PairRevokeData.serializer())
            assertEquals(PairOp.REVOKE, request.op)
            assertEquals(PairRevokeData(pairId, "user"), request.data)
            assertNull("key wiped", pairs.store.secretBlocking(pairId))
            assertEquals(listOf(pairId to "revoked"), closed)
        }

    @Test
    fun withoutSessionThePhoneCleansUpAloneAndReportsPendingRemote() =
        runTest {
            pairs.store.save(pair(), ByteArray(32))
            assertEquals(UnpairResult.DONE_PENDING_REMOTE, controller(withSession = false).unpair(pairId))
            assertEquals(0, pairs.store.activeCount())
        }

    @Test
    fun noAckWithinTenSecondsFallsBackToPendingRemote() =
        runTest {
            scope = backgroundScope
            answerRevoke = false
            session = newSession()
            pairs.store.save(pair(), ByteArray(32))
            assertEquals(UnpairResult.DONE_PENDING_REMOTE, controller(withSession = true).unpair(pairId))
            assertEquals(0, pairs.store.activeCount())
        }

    @Test
    fun revokeFromTheClientIsAcknowledgedFirstThenCleanedUp() =
        runTest {
            scope = backgroundScope
            answerRevoke = false
            session = newSession()
            pairs.store.save(pair(), ByteArray(32))
            val controller = controller(withSession = true)
            val request = revoke(pairId)
            controller.handler.handle(session, request)
            val ack = PlaintextCodec.decodeAck(sent.single().second)
            assertTrue(ack.ok)
            assertEquals(request.id, ack.re)
            assertEquals(0, pairs.store.activeCount())
            assertEquals(listOf(pairId to "revoked"), closed)
            assertEquals("MacBook", controller.unpairedByPeer.value?.peerName)
            controller.noticeShown()
            assertNull(controller.unpairedByPeer.value)
        }

    @Test
    fun revokeOfAnotherPairIsBadRequestAndKeepsThePair() =
        runTest {
            scope = backgroundScope
            session = newSession()
            pairs.store.save(pair(), ByteArray(32))
            controller(withSession = true).handler.handle(session, revoke(UUID.randomUUID().toString()))
            assertEquals(ErrorCode.BAD_REQUEST.name, PlaintextCodec.decodeAck(sent.single().second).error?.code)
            assertEquals(1, pairs.store.activeCount())
        }

    @Test
    fun byeRevokedCleansUpWhenPairRevokeWasLost() =
        runTest {
            pairs.store.save(pair(), ByteArray(32))
            val controller = controller(withSession = false)
            controller.onSessionEnded(SessionEnded(pairId, "shutdown"))
            assertEquals(1, pairs.store.activeCount())
            controller.onSessionEnded(SessionEnded(pairId, "revoked"))
            assertEquals(0, pairs.store.activeCount())
        }

    @Test
    fun deleteAllTellsLanClientsWithReinstallAndKeepsNoTombstone() =
        runTest {
            scope = backgroundScope
            session = newSession()
            pairs.store.save(pair(), ByteArray(32))
            pairs.database.pairedDevices().setRelayRegistered(pairId, true)

            controller(withSession = true).revokeAllForReinstall()

            val request = PlaintextCodec.decodeOp(sent.single().second, PairRevokeData.serializer())
            assertEquals(PairRevokeData(pairId, "reinstall"), request.data)
            assertEquals(listOf(pairId to "revoked"), closed)
            // SET-02 A4: deleted outright, even a pair the relay knew (the relay already dropped it).
            assertNull(pairs.database.pairedDevices().findBlocking(pairId))
        }

    @Test
    fun deleteAllGoesOnWithoutAnAckAndSkipsRelayedSessions() =
        runTest {
            scope = backgroundScope
            answerRevoke = false
            session = newSession()
            pairs.store.save(pair(), ByteArray(32))
            controller(withSession = true).revokeAllForReinstall()
            assertEquals(1, sent.size)
            assertNull(pairs.database.pairedDevices().findBlocking(pairId))

            sent.clear()
            closed.clear()
            session = newSession(PeerSession.Channel.RELAY)
            pairs.store.save(pair(), ByteArray(32))
            controller(withSession = true).revokeAllForReinstall()
            assertTrue(sent.isEmpty())
            assertTrue(closed.isEmpty())
            assertEquals(0, pairs.store.activeCount())
        }

    private fun revoke(target: String) =
        inbound(
            MessageType.PAIR,
            PlaintextCodec.encodeOp(PairOp.REVOKE, PairRevokeData.serializer(), PairRevokeData(target, "user")),
        )

    private fun inbound(
        type: MessageType,
        plaintext: ByteArray,
    ) = InboundEnvelope(type.wire, ids.next(), System.currentTimeMillis(), plaintext)

    private fun pair() =
        NewPair(
            pairId = pairId,
            peer = PeerKeys("5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718", ByteArray(32), ByteArray(32)),
            peerName = "MacBook",
            peerPlatform = PeerPlatform.MACOS,
            peerModel = "Mac15,3",
            attestation = SignedAttestation(ByteArray(8), ByteArray(64), ByteArray(64), 0),
        )
}
