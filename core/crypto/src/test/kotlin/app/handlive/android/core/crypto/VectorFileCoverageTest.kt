package app.handlive.android.core.crypto

import app.handlive.android.core.protocol.testing.SharedTestVectors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chặn bỏ sót vector: mọi file trong shared/test-vectors phải có test Kotlin tương ứng.
 * Thêm file vector mới mà chưa có test → test này đỏ.
 */
class VectorFileCoverageTest {
    private val covered =
        mapOf(
            "xchacha20-poly1305.json" to "AeadPrimitiveVectorTest",
            "hchacha20.json" to "AeadPrimitiveVectorTest",
            "chacha20-poly1305.json" to "AeadPrimitiveVectorTest",
            "x25519.json" to "PrimitiveKdfAndIdentityVectorTest",
            "hkdf-sha256.json" to "PrimitiveKdfAndIdentityVectorTest",
            "device-id.json" to "PrimitiveKdfAndIdentityVectorTest",
            "pair-prk.json" to "PairingAndSessionVectorTest",
            "session-handshake.json" to "PairingAndSessionVectorTest, TypedMessageVectorTest",
            "session-rekey.json" to "PairingAndSessionVectorTest, TypedMessageVectorTest",
            "stream-keys.json" to "StreamKeysVectorTest, TypedMessageVectorTest",
            "envelope.json" to "MessageCipherVectorTest, EnvelopeCodecVectorTest",
            "ack.json" to "MessageCipherVectorTest, EnvelopeCodecVectorTest",
            "clipboard-chunk.json" to "MessageCipherVectorTest, ClipboardChunkPlaintextVectorTest",
            "hl-frame.json" to "MessageCipherVectorTest, HlFrameVectorTest",
            "ed25519.json" to "SignatureVectorTest",
            "relay-auth.json" to "SignatureVectorTest",
            "pair-handshake.json" to "PairHandshakeVectorTest, PairingExchangeVectorTest",
            "discovery-hint.json" to "DiscoveryHintVectorTest",
            "push-envelope.json" to "PushEnvelopeVectorTest, PushRequestVectorTest",
            "relay-frame.json" to "RelayFrameVectorTest",
            "revoke.json" to "RevokeStatementVectorTest",
            "clipboard-html.json" to "HtmlClipSanitizerVectorTest",
            "bt-link.json" to "BtLinkKeyDerivationTest, NoiseNNpsk0Test " +
                "(core:transport: BtTransportCipherTest, BluetoothPreauthSessionTest, BluetoothLinkAcceptorTest, " +
                "BluetoothAdvertisementDataTest)",
            "bt-frame.json" to "BtRecordCodecTest, BtPreauthRecordTest, BtTransportFrameTest " +
                "(core:transport: BtTransportCipherTest, BluetoothLongLaneReassemblerTest)",
            "bt-advertisement.json" to "(core:transport: BluetoothAdvertisementDataTest)",
            // 1 vector dưới chưa có test Android (ghi nhận ở #3447): bt-pairing.json thuộc A7.2 (PAIR-01 qua
            // Bluetooth, CPace, chưa bắt đầu). Ghi vào đây để test này KHÔNG ngầm bỏ sót — xoá ghi chú và trỏ
            // đúng test owner khi thẻ tương ứng viết test.
            "bt-pairing.json" to "(chưa có test Android — A7.2, PAIR-01 qua Bluetooth, chưa bắt đầu)",
            "cpace.json" to "CPaceX25519SHA512VectorTest",
            EnvelopeRoundtripTest.ANDROID_FILE to "EnvelopeRoundtripTest",
            EnvelopeRoundtripTest.APPLE_FILE to "EnvelopeRoundtripTest",
        )

    @Test
    fun everyVectorFileHasATest() {
        val present =
            SharedTestVectors.vectorsDir
                .listFiles { file -> file.name.endsWith(".json") }
                .orEmpty()
                .map { it.name }
                .toSortedSet()
        assertEquals(emptySet<String>(), present - covered.keys)
    }
}
