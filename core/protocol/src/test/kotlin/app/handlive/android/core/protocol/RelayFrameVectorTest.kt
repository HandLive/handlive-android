package app.handlive.android.core.protocol

import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.relay.RelayFrame
import app.handlive.android.core.protocol.relay.RelayIncoming
import app.handlive.android.core.protocol.relay.RelayWire
import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** relay-frame.json: the `HR` binary frame both ways and the `to` → `from` text wrapper (0.4.3, CONN-03 API 6). */
class RelayFrameVectorTest {
    private val vectors = SharedTestVectors.vectors(FILE)

    @Test
    fun hrFramesEncodeAndDecodeByteForByte() {
        val frames = vectors.filter { it.str("kind") == "frame" }
        frames.forEach { v ->
            val frame = RelayFrame(v.str("device_id"), v.hex("inner"))
            assertEquals(v.str("name"), v.str("header"), Hex.encode(RelayFrame.header(v.str("device_id"))))
            assertArrayEquals(v.str("name"), v.hex("frame"), frame.encode())
            val decoded = RelayFrame.decode(v.hex("frame"))
            assertEquals(v.str("device_id"), decoded.deviceId)
            assertArrayEquals(v.hex("inner"), decoded.inner)
        }
        assertEquals(4, frames.size)
    }

    @Test
    fun theRelayOnlySwapsTheDeviceId() {
        vectors.filter { it.str("kind") == "rewrite" }.forEach { v ->
            val outbound = RelayFrame.decode(v.hex("outbound"))
            assertEquals(v.str("recipient_device_id"), outbound.deviceId)
            assertArrayEquals(v.hex("inbound"), RelayFrame(v.str("sender_device_id"), outbound.inner).encode())
        }
    }

    @Test
    fun textWrappersCarryTheEnvelopeToAndFrom() {
        val rewrites = vectors.filter { it.str("kind") == "text_rewrite" }
        rewrites.forEach { v ->
            val env = EnvelopeCodec.decode(v.str("env"))
            val inbound = RelayWire.decode(v.str("inbound")) as RelayIncoming.Envelope
            assertEquals(v.str("name"), v.str("sender_device_id"), inbound.message.from)
            assertEquals(env, inbound.message.env)
        }
        // What this phone writes is compact, `to` first, the envelope as the codec writes it.
        val compact = rewrites.first()
        val written = RelayWire.outbound(compact.str("recipient_device_id"), EnvelopeCodec.decode(compact.str("env")))
        assertEquals(compact.str("outbound"), written)
        assertEquals(3, rewrites.size)
    }

    @Test
    fun malformedFramesAreBadRequests() {
        SharedTestVectors.invalidVectors(FILE).filter { it.str("kind") == "frame" }.forEach { v ->
            val error = assertThrows(v.str("name"), ProtocolException::class.java) { RelayFrame.decode(v.hex("frame")) }
            assertEquals(v.str("expected_error"), error.code.name)
        }
        // A wrapper the relay refuses never parses as an envelope from a peer either.
        SharedTestVectors.invalidVectors(FILE).filter { it.str("kind") == "text" }.forEach { v ->
            val outbound = Json.parseToJsonElement(v.str("outbound")).jsonObject
            assertTrue(v.str("name"), "from" !in outbound)
        }
    }

    private companion object {
        const val FILE = "relay-frame.json"
    }
}
