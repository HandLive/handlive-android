package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.crypto.identity.DeviceIdentity
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.core.protocol.testing.strOrNull
import app.handlive.android.feature.pairing.invite.PairingInvite
import app.handlive.android.feature.pairing.testing.FakeTextSocket
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's side of PAIR-01 against shared/test-vectors/pair-handshake.json. With the vector's identity, `nonce_s`
 * and TLS pin, [PairingExchange] must answer the vector's `pair/hello` with exactly its `pair/offer` and its
 * `pair/confirm` with exactly its `pair/done`, store the same `PRK`, attestation and signatures and report the same
 * Security Code (PAIR-02 field 10). Every negative `hello` and `confirm` must end in its `pair/error` code with
 * nothing stored, and a mistyped PIN must give exactly the vector's wrong offer MAC. QR codes and TXT `pr` go
 * through [PairingInvite].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingExchangeVectorTest {
    private val pairs = PairStoreFixture()

    @After
    fun tearDown() = pairs.database.close()

    @Test
    fun qrCodesParseAndGiveThePairingRequestHint() {
        for (v in vectors.filter { it.str("mode") == PairHelloData.MODE_QR }) {
            val name = v.str("name")
            val invite = checkNotNull(PairingInvite.parse(v.str("qr_uri")))
            assertArrayEquals(name, v.hex("client_ik_dh_pub"), invite.clientDhPublicKey)
            assertArrayEquals(name, v.hex("pairing_secret"), invite.pairingSecret)
            assertEquals(name, v.str("client_name"), invite.clientName)
            assertEquals(name, v.strOrNull("qr_rv"), invite.rendezvous?.let(Hex::encode))
            assertEquals(name, v.str("pr"), invite.pairingRequestHint)
        }
        val wrongHint = invalid.single { it.str("check") == "pr" }
        val invite = checkNotNull(PairingInvite.parse(vectorOf(wrongHint).str("qr_uri")))
        assertNotEquals(wrongHint.str("name"), wrongHint.str("pr"), invite.pairingRequestHint)
    }

    @Test
    fun everyVectorPairsWithExactlyTheVectorMessages() =
        runBlocking {
            for (v in vectors) {
                val name = v.str("name")
                val run = start(v)
                run.socket.toPhone.send(v.str("hello_envelope"))
                assertEquals(name, json(v.str("offer_plaintext")), payload(run.socket.toClient.receive()))
                run.socket.toPhone.send(v.str("confirm_envelope"))
                assertEquals(name, json(v.str("done_plaintext")), payload(run.socket.toClient.receive()))
                run.socket.toPhone.close()
                val paired = run.result.await() as PairingOutcome.Paired
                assertEquals(
                    name,
                    listOf(v.str("pair_id"), v.str("client_name"), v.str("security_code")),
                    listOf(paired.pairId, paired.peerName, paired.safetyCode),
                )
                assertStored(v)
            }
        }

    @Test
    fun negativeHelloAndConfirmVectorsAreRefusedAndNothingIsStored() =
        runBlocking {
            val refused = invalid.filter { it.str("checked_by") == "android" }
            for (n in refused) {
                val name = n.str("name")
                val v = vectorOf(n)
                val run = start(v)
                if (n.str("message") == "confirm") {
                    run.socket.toPhone.send(v.str("hello_envelope"))
                    assertEquals(name, json(v.str("offer_plaintext")), payload(run.socket.toClient.receive()))
                }
                run.socket.toPhone.send(n.str("envelope"))
                val reply = decode(run.socket.toClient.receive())
                assertEquals(name, "error", UnencryptedEnvelopes.op(reply))
                assertEquals(
                    name,
                    n.str("expected_error"),
                    UnencryptedEnvelopes.read(reply, PairErrorData.serializer()).data.code,
                )
                assertEquals(name, PairingFailure.AUTH_FAILED, (run.result.await() as PairingOutcome.Failed).failure)
                assertEquals(name, 0, pairs.store.activeCount())
            }
            assertEquals(setOf("hello", "confirm"), refused.map { it.str("message") }.toSet())
        }

    @Test
    fun mistypedPinGivesTheVectorOfferMacAndReopensTheWindow() =
        runBlocking {
            val n = invalid.single { it.str("reason") == "wrong_pin" }
            val v = vectorOf(n)
            val run = start(v, pin = n.str("pin_used"))
            run.socket.toPhone.send(v.str("hello_envelope"))
            assertEquals(json(n.str("plaintext")), payload(run.socket.toClient.receive()))
            // The Mac holds the right PIN, so the MAC fails there and it answers PIN_INVALID (A5, E7).
            run.socket.toPhone.send(n.str("client_reply_envelope"))
            val failed = run.result.await() as PairingOutcome.Failed
            assertEquals(PairingFailure.PIN_INVALID to 2, failed.failure to failed.attemptsLeft)
            assertTrue("the window takes the next PIN", run.window.claim())
            assertEquals(0, pairs.store.activeCount())
        }

    private class Run(
        val socket: FakeTextSocket,
        val window: PairingWindow,
        val result: Deferred<PairingOutcome>,
    )

    /** One `/v1/pair` connection of the vector's phone, its clock at `created_at` and its window open. */
    private fun CoroutineScope.start(
        v: JsonObject,
        pin: String? = v.strOrNull("pin"),
    ): Run {
        val now = v.long("created_at")
        val expiresAt = now + PairingWindow.DURATION_MILLIS
        val window =
            if (v.str("mode") == PairHelloData.MODE_QR) {
                PairingWindow.Qr(checkNotNull(PairingInvite.parse(v.str("qr_uri"))), expiresAt)
            } else {
                PairingWindow.Pin(expiresAt).also { it.submit(checkNotNull(pin)) }
            }
        val phone =
            LocalPairingDevice(
                identity = DeviceIdentity(v.hex("android_ik_sig_seed"), v.hex("android_ik_dh_priv")),
                name = v.str("android_name"),
                model = v.str("android_model"),
                osVersion = v.str("android_os_version"),
                tlsSha256 = v.hex("tls_sha256"),
            )
        val exchange = PairingExchange(phone, window, pairs.store, clock = { now }, nonce = { v.hex("nonce_s") })
        val socket = FakeTextSocket()
        return Run(socket, window, async { exchange.run(socket) })
    }

    /** The row of `paired_device` (0.9.1) holds the client's keys, the attestation, both signatures and `PRK`. */
    private suspend fun assertStored(v: JsonObject) {
        val name = v.str("name")
        val row = checkNotNull(pairs.database.pairedDevices().find(v.str("pair_id")))
        assertEquals(
            name,
            listOf("client_device_id", "client_name", "client_platform", "client_model").map { v.str(it) },
            listOf(row.peerDeviceId, row.peerName, row.peerPlatform.wire, row.peerModel),
        )
        assertEquals(
            name,
            listOf("client_ik_sig_pub", "client_ik_dh_pub", "attestation", "sig_s", "sig_c").map { v.str(it) },
            listOf(row.peerIkSigPub, row.peerIkDhPub, row.attestation, row.sigSelf, row.sigPeer).map(Hex::encode),
        )
        assertEquals(name, v.long("created_at"), row.createdAt)
        assertArrayEquals(name, v.hex("prk"), pairs.store.secretBlocking(v.str("pair_id"))?.prk)
    }

    private companion object {
        const val FILE = "pair-handshake.json"
        val vectors: List<JsonObject> by lazy { SharedTestVectors.vectors(FILE) }
        val invalid: List<JsonObject> by lazy { SharedTestVectors.invalidVectors(FILE) }

        fun vectorOf(negative: JsonObject): JsonObject = vectors.single { it.str("name") == negative.str("vector") }

        fun decode(envelope: String) = EnvelopeCodec.decode(envelope)

        /** The plaintext JSON of an unencrypted `pair` envelope, compared as JSON: key order is not significant. */
        fun payload(envelope: String): JsonElement =
            Json.parseToJsonElement(EnvelopeCodec.readUnencryptedPayload(decode(envelope)))

        fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    }
}
