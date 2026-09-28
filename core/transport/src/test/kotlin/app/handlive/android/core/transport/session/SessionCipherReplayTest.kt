package app.handlive.android.core.transport.session

import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.derivation.SessionKeys
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.TransportConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/**
 * 0.5.1 rule 2 (`DEDUP_WINDOW`): every `id` accepted in the current key epoch is kept, the previous epoch's while its
 * key is still accepted, and only after the envelope decrypted.
 */
class SessionCipherReplayTest {
    private val ids = UuidV7Generator()
    private var now = 1_000_000L
    private val initial = SessionKeys(SecureRandomBytes.next(SessionKeys.SECRET_SIZE))
    private val client = SessionCipher(initial, PeerRole.CLIENT, { now })
    private val server = SessionCipher(initial, PeerRole.SERVER, { now })

    private fun seal(
        id: String = ids.next(),
        cipher: SessionCipher = client,
    ): Envelope = cipher.seal(EnvelopeHeader("sms", id, now), "{\"op\":\"send\"}".toByteArray())

    @Test
    fun aRepeatedIdIsAReplayForTheWholeEpochNotOnlyFiveMinutes() {
        val envelope = seal()
        val first = server.accept(envelope)
        assertFalse(first.replayed)
        assertArrayEquals("{\"op\":\"send\"}".toByteArray(), first.plaintext)
        assertTrue(server.accept(envelope).replayed)
        // The relay replays the captured envelope 23 hours later, same keys: still a replay.
        now += 23.hours.inWholeMilliseconds
        assertTrue(server.accept(envelope).replayed)
    }

    @Test
    fun aForgedEnvelopeNeverTakesAnId() {
        val id = ids.next()
        val genuine = seal(id)
        val forged = genuine.copy(payload = seal(ids.next()).payload)
        assertThrows(ProtocolException::class.java) { server.accept(forged) }
        assertFalse(server.accept(genuine).replayed)
    }

    @Test
    fun thePreviousEpochsIdsAreKeptWhileItsKeyIsAccepted() {
        val before = seal()
        assertFalse(server.accept(before).replayed)
        val inFlight = seal()
        val next = SessionKeys(SecureRandomBytes.next(SessionKeys.SECRET_SIZE))
        server.install(next, 1)
        client.install(next, 1)
        // An envelope sealed with the old key and still in flight is accepted once, then a replay.
        assertFalse(server.accept(inFlight).replayed)
        assertTrue(server.accept(inFlight).replayed)
        assertTrue(server.accept(before).replayed)
        // A retry of an old id under the new key is a duplicate too while the old epoch is kept.
        val retried = seal(before.id)
        assertTrue(server.accept(retried).replayed)
        // Once the old key expires its envelopes no longer decrypt at all.
        now += TransportConstants.OLD_KEY_GRACE.inWholeMilliseconds
        assertThrows(ProtocolException::class.java) { server.accept(before) }
        // The new epoch keeps its own ids.
        assertTrue(server.accept(retried).replayed)
    }

    @Test
    fun aDuplicateGetsItsEarlierAckForTheWholeEpoch() {
        val request = seal()
        assertTrue(server.accept(request).earlierAck == null)
        val ack = "{\"re\":\"${request.id}\",\"ok\":true}".toByteArray()
        server.recordAck(request.id, ack)
        now += 23.hours.inWholeMilliseconds
        assertArrayEquals(ack, server.accept(request).earlierAck)
        // After a rekey the previous epoch's acks stay while its key is accepted.
        val keys = SessionKeys(SecureRandomBytes.next(SessionKeys.SECRET_SIZE))
        server.install(keys, 1)
        assertArrayEquals(ack, server.accept(request).earlierAck)
        // An ack for an id the phone never accepted is not kept.
        val unknown = seal()
        server.recordAck(unknown.id, ack)
        assertNull(server.accept(unknown).earlierAck)
    }

    @Test
    fun aDirectionThatReaches20000IdsWithoutARekeyOverflows() {
        repeat(TransportConstants.MAX_TRACKED_IDS - 1) { assertFalse(server.accept(seal()).overflow) }
        assertTrue(server.accept(seal()).overflow)
    }

    @Test
    fun aHugeRekeyThresholdDoesNotOverflowTheIdCap() {
        val lax = SessionCipher(initial, PeerRole.SERVER, { now }, rekeyAfterEnvelopes = Long.MAX_VALUE)
        assertFalse(lax.accept(seal()).overflow)
    }

    @Test
    fun aRekeyEmptiesTheSetOnceThePreviousEpochIsGone() {
        val first = seal()
        server.accept(first)
        repeat(2) { epoch ->
            val keys = SessionKeys(SecureRandomBytes.next(SessionKeys.SECRET_SIZE))
            server.install(keys, epoch + 1)
            client.install(keys, epoch + 1)
            now += TransportConstants.OLD_KEY_GRACE.inWholeMilliseconds
        }
        // Two epochs later the old id is no longer tracked; a new envelope may reuse nothing of it.
        assertFalse(server.accept(seal(first.id)).replayed)
        assertTrue(server.trackedIds <= 1)
    }
}
