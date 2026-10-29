package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.core.transport.bluetooth.testing.InMemoryBluetoothLinkIo
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Luồng B (QR) của ghép đôi qua Bluetooth (0.4.5 "Pairing mode", A7.2-B): `noise1` xác thực bằng `K_bt_plink` của
 * cửa sổ QR đang mở đi vào [BluetoothLinkAcceptor.servePairing] (không có `pair_id`), chiếm [BtQrPairingSlot] cho
 * tới khi liên kết đó kết thúc — một liên kết khác xác thực đúng cùng psk trong lúc đó không được thử, rơi xuống
 * `K_bt_link` và bị `reject`. Client dựng từ `bt-pairing.json` (`n_s` cố định) qua [InMemoryBluetoothLinkIo] —
 * không radio, không PAIR-01 thật (đó là `PairingCoordinator` của feature/pairing, cắm vào qua
 * [BluetoothPairingKeySource]/[BtQrPairingKeys]; [FakeNoiseInitiator] chỉ đóng vai C để lái bắt tay tới bước 5,
 * độc lập với [NoiseNNpsk0] như `FakePairingClient` độc lập với phía phone của PAIR-01).
 */
class BtQrPairingAcceptorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val firstFrame = SharedTestVectors.vectors("bt-pairing.json").first { it.str("kind") == "first_frame" }
    private val goodMessage1 = firstFrame.hex("message1")
    private val plinkKey = firstFrame.hex("psk")
    private val prologue = NoiseNNpsk0.prologue(firstFrame.hex("n_s"))

    @After
    fun tearDown() = scope.cancel()

    private fun admission(linkLimit: Int = 4) =
        BluetoothLinkAdmission(linkLimit = linkLimit, preauthLimit = linkLimit, authFailWindow = 1.minutes)

    private class Served(
        val text: String,
    )

    private fun acceptor(
        admission: BluetoothLinkAdmission,
        slot: BtQrPairingSlot,
        pairingKey: ByteArray? = plinkKey,
        served: CompletableDeferred<Served> = CompletableDeferred(),
        holdLink: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) = BluetoothLinkAcceptor(
        admission = admission,
        serve = { _, _ -> error("liên kết ghép đôi QR không có pair_id, không được vào serve() của cặp đã ghép") },
        newSession = {
            BluetoothPreauthSession(
                pairKeys = BluetoothPairKeys { emptyList() },
                pairingKeySource = BluetoothPairingKeySource { pairingKey },
                pairingSlot = slot,
                nonce = { firstFrame.hex("n_s") },
            )
        },
        servePairing = { socket: WebSocketSession ->
            val first = (socket.incoming.receive() as Frame.Text).readText()
            served.complete(Served(first))
            holdLink.await()
        },
    )

    /** Client phía bước 1-3 với [message1]; trả bản ghi S đáp (`noise2` hoặc `reject`). */
    private suspend fun clientNoise(
        client: InMemoryBluetoothLinkIo,
        message1: ByteArray = goodMessage1,
    ): BtPreauthRecord.Decoded {
        val challenge = BtPreauthRecord.decode(client.readPreauthRecord())
        assertEquals(BtPreauthRecord.KIND_CHALLENGE, challenge.kind)
        client.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE1, message1))
        return BtPreauthRecord.decode(client.readPreauthRecord())
    }

    @Test
    fun aValidFirstFrameClaimsTheSlotAndReachesServePairingWithPairIdNull() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot()
            val served = CompletableDeferred<Served>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission(), slot, served = served).accept(server) }
            val noise2 = clientNoise(client)
            assertEquals(BtPreauthRecord.KIND_NOISE2, noise2.kind)
            val handshake = checkNotNull(openMessage2(noise2.payload))
            client.writeRecord(firstTransportRecord(handshake.c1, "pair/hello"))
            val result = withTimeout(WAIT) { served.await() }
            assertEquals("pair/hello", result.text)
            withTimeout(WAIT) { link.await() }
        }

    @Test
    fun aNoise1WithAWrongPlinkKeyNeverClaimsTheSlotAndGetsRejected() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link =
                scope.async {
                    acceptor(admission(), slot, pairingKey = SecureRandomBytes.next(plinkKey.size)).accept(server)
                }
            val answer = clientNoise(client)
            assertEquals(BtPreauthRecord.KIND_REJECT, answer.kind)
            withTimeout(WAIT) { link.await() }
            // Slot chưa từng bị chiếm: một kênh khác xin ngay vẫn chiếm được.
            assertTrue(slot.tryClaim())
        }

    @Test
    fun aWrongFirstFrameWithTheRightSizeButNoValidMacNeverClaimsTheSlot() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission(), slot).accept(server) }
            val answer = clientNoise(client, message1 = SecureRandomBytes.next(goodMessage1.size))
            assertEquals(BtPreauthRecord.KIND_REJECT, answer.kind)
            withTimeout(WAIT) { link.await() }
            assertTrue(slot.tryClaim())
        }

    @Test
    fun aFirstFrameMissingItsMacBytesIsRejectedInsteadOfCrashing() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission(), slot).accept(server) }
            // e_pub trọn, nhưng không còn chỗ cho 16 byte MAC/tag: "khung đầu thiếu MAC", khác trường hợp MAC sai.
            val answer = clientNoise(client, message1 = goodMessage1.copyOfRange(0, 32))
            assertEquals(BtPreauthRecord.KIND_REJECT, answer.kind)
            withTimeout(WAIT) { link.await() }
            assertTrue(slot.tryClaim())
        }

    @Test
    fun aSecondClaimantWhileTheSlotIsHeldGetsRejectedEvenWithTheSamePsk() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot(holdTimeout = 10.seconds)
            val served = CompletableDeferred<Served>()
            val hold = CompletableDeferred<Unit>()
            val (client1, server1) = InMemoryBluetoothLinkIo.pair()
            val link1 = scope.async { acceptor(admission(), slot, served = served, holdLink = hold).accept(server1) }
            val noise2a = clientNoise(client1)
            val handshake1 = checkNotNull(openMessage2(noise2a.payload))
            client1.writeRecord(firstTransportRecord(handshake1.c1, "pair/hello"))
            // Liên kết 1 đã xác nhận và đang chạy PAIR-01 (bị giữ ở servePairing bởi `hold`): slot còn bị chiếm.
            withTimeout(WAIT) { served.await() }

            // Cùng psk, cùng n_s (test dùng nonce cố định): nếu được thử, noise1 này cũng xác thực đúng — nhưng
            // slot của liên kết 1 còn giữ nên S không thử K_bt_plink với kênh này ("one client per window").
            val (client2, server2) = InMemoryBluetoothLinkIo.pair()
            val link2 = scope.async { acceptor(admission(), slot).accept(server2) }
            val answer2 = clientNoise(client2)
            assertEquals(BtPreauthRecord.KIND_REJECT, answer2.kind)
            withTimeout(WAIT) { link2.await() }

            hold.complete(Unit)
            withTimeout(WAIT) { link1.await() }
            // servePairing() của liên kết 1 đã xong: slot trống lại cho kênh kế tiếp.
            assertTrue(slot.tryClaim())
        }

    @Test
    fun theSlotFreesItselfAfterItsHoldTimeoutSoTheNextClaimantGetsIn() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot(holdTimeout = SHORT)
            assertTrue(slot.tryClaim())
            kotlinx.coroutines.delay(SHORT + 100.milliseconds)
            assertTrue(slot.tryClaim())
        }

    @Test
    fun theSlotReleasesAssoonAsServePairingReturnsSoTheNextLinkCanClaimIt() =
        runBlocking<Unit> {
            val slot = BtQrPairingSlot(holdTimeout = 10.seconds)
            val served = CompletableDeferred<Served>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission(), slot, served = served).accept(server) }
            val noise2 = clientNoise(client)
            val handshake = checkNotNull(openMessage2(noise2.payload))
            client.writeRecord(firstTransportRecord(handshake.c1, "pair/hello"))
            withTimeout(WAIT) { served.await() }
            // servePairing() của acceptor() này trả về ngay (holdLink mặc định đã complete) — slot phải trống lại.
            withTimeout(WAIT) { link.await() }
            assertTrue(slot.tryClaim())
        }

    private fun firstTransportRecord(
        c1: ByteArray,
        text: String,
    ): ByteArray =
        BtTransportCipher(c1).seal(
            BtTransportFrame.encode(BtLinkConstants.CHAN_CONTROL, more = false, long = false, text.toByteArray()),
        )

    /** Phía C của bước 4-5: mở `message2` và tính `Split()` — không phụ thuộc [NoiseNNpsk0] (chỉ dùng [split]). */
    private fun openMessage2(message2: ByteArray): FakeNoiseInitiator.Handshake? {
        val ePrivC = firstFrame.hex("e_priv")
        val ePubC = X25519Keys.publicFromPrivate(ePrivC)
        assertArrayEquals(firstFrame.hex("e_pub"), ePubC)
        return FakeNoiseInitiator.openMessage2(plinkKey, prologue, ePrivC, goodMessage1, message2)
    }

    private companion object {
        val WAIT = 5.seconds
        val SHORT = 300.milliseconds
    }
}

/**
 * Phía C độc lập của `Noise_NNpsk0_25519_ChaChaPoly_SHA256` (rev. 34) — dựng từ đặc tả, không dùng lại [NoiseNNpsk0]
 * (phía S), để test có thể lái bắt tay tới bước 5 mà không cần một bộ vector `message2`/`c1`/`c2` riêng cho khoá
 * ghép đôi QR. Cùng tinh thần `FakePairingClient` của feature/pairing cho PAIR-01.
 */
private object FakeNoiseInitiator {
    private const val HASH_SIZE = 32
    private val ZERO_NONCE = ByteArray(ChaCha20Poly1305Aead.NONCE_SIZE)

    class Handshake(
        val c1: ByteArray,
        val c2: ByteArray,
    )

    /** [message1] = `e.pub` của C (dẫn từ [ePrivC]) ‖ tag đã gửi; [message2] = `noise2` S đáp. */
    fun openMessage2(
        psk: ByteArray,
        prologue: ByteArray,
        ePrivC: ByteArray,
        message1: ByteArray,
        message2: ByteArray,
    ): Handshake? {
        val ePubC = message1.copyOfRange(0, X25519Keys.KEY_SIZE)
        val message1Tag = message1.copyOfRange(X25519Keys.KEY_SIZE, message1.size)
        val (h0, ck0) = initializeSymmetric()
        val h1 = mixHash(h0, prologue)
        val pskStep = mixKeyAndHashPsk(ck0, h1, psk)
        val h3 = mixHash(pskStep.h, ePubC)
        val eStep1 = mixKey(pskStep.ck, ePubC)
        val h4 = mixHash(h3, message1Tag)
        val ePubS = message2.copyOfRange(0, X25519Keys.KEY_SIZE)
        val tag2 = message2.copyOfRange(X25519Keys.KEY_SIZE, message2.size)
        val h5 = mixHash(h4, ePubS)
        val eStep2 = mixKey(eStep1.ck, ePubS)
        val dh = X25519Keys.sharedSecret(ePrivC, ePubS)
        val eeStep = mixKey(eStep2.ck, dh)
        return try {
            ChaCha20Poly1305Aead.open(eeStep.tempK, ZERO_NONCE, tag2, h5)
            val (c1, c2) = NoiseNNpsk0.split(eeStep.ck)
            Handshake(c1, c2)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun mixHash(
        h: ByteArray,
        data: ByteArray,
    ): ByteArray = sha256(h + data)

    private fun initializeSymmetric(): Pair<ByteArray, ByteArray> {
        val name = NoiseNNpsk0.PROTOCOL_NAME
        val h = if (name.size <= HASH_SIZE) name else sha256(name)
        return h to h
    }

    private class MixKeyResult(
        val ck: ByteArray,
        val tempK: ByteArray,
    )

    private fun mixKey(
        ck: ByteArray,
        ikm: ByteArray,
    ): MixKeyResult {
        val tempKey = HmacSha256.mac(ck, ikm)
        val out1 = HmacSha256.mac(tempKey, byteArrayOf(0x01))
        val out2 = HmacSha256.mac(tempKey, out1 + byteArrayOf(0x02))
        return MixKeyResult(ck = out1, tempK = out2)
    }

    private class PskMixResult(
        val ck: ByteArray,
        val h: ByteArray,
    )

    private fun mixKeyAndHashPsk(
        ck: ByteArray,
        h: ByteArray,
        psk: ByteArray,
    ): PskMixResult {
        val tempKey = HmacSha256.mac(ck, psk)
        val out1 = HmacSha256.mac(tempKey, byteArrayOf(0x01))
        val out2 = HmacSha256.mac(tempKey, out1 + byteArrayOf(0x02))
        return PskMixResult(ck = out1, h = mixHash(h, out2))
    }
}
