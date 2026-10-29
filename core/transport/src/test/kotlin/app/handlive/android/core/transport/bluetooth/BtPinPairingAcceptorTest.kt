package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.CPaceX25519SHA512
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Luồng C (PIN) của ghép đôi qua Bluetooth (0.4.5 "PIN", A7.2-C): `cpace1`/`cpace2` rồi `noise1` khoá bằng
 * `K_bt_pinlink` vừa dẫn qua CPace đi vào [BluetoothLinkAcceptor.servePinPairing] cùng `K_bt_pin` (không có
 * `pair_id`) — PAIR-01 thật là `PairingCoordinator` (feature/pairing); đây chỉ lái CPace rồi Noise tới bước 5 bằng
 * [FakePinClient], dùng lại [CPaceX25519SHA512] (đã có vector riêng, `CPaceX25519SHA512VectorTest`) cho phần CPace
 * nhưng tự dựng Noise phía C độc lập với [NoiseNNpsk0] (phía S) — như [BtQrPairingAcceptorTest].
 */
class BtPinPairingAcceptorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pinVectors = SharedTestVectors.vectors("cpace.json").filter { it.str("kind") == "handlive_pin" }
    private val vector = pinVectors.first { it.str("name") == "HandLive PIN 482917" }
    private val invalid = SharedTestVectors.invalidVectors("cpace.json")

    @After
    fun tearDown() = scope.cancel()

    private fun admission(linkLimit: Int = 4) =
        BluetoothLinkAdmission(linkLimit = linkLimit, preauthLimit = linkLimit, authFailWindow = 1.minutes)

    private class Served(
        val text: String,
        val kBtPin: ByteArray,
    )

    private class FakePinWindow(
        @Volatile var pin: ByteArray?,
    ) : BluetoothPinPairingWindow {
        @Volatile
        var failures = 0

        override fun currentPin(): ByteArray? = pin

        override fun onPinChannelFailedAfterCpace1() {
            failures++
        }
    }

    private fun acceptor(
        admission: BluetoothLinkAdmission,
        window: FakePinWindow,
        nS: ByteArray = vector.hex("n_s"),
        ybScalar: ByteArray = vector.hex("yb"),
        served: CompletableDeferred<Served> = CompletableDeferred(),
    ) = BluetoothLinkAcceptor(
        admission = admission,
        serve = { _, _ -> error("liên kết ghép đôi PIN không có pair_id, không được vào serve() của cặp đã ghép") },
        newSession = {
            BluetoothPreauthSession(
                pairKeys = BluetoothPairKeys { emptyList() },
                pinPairing = BtPinPairingResponder(window, scalar = { ybScalar }),
                nonce = { nS },
            )
        },
        servePinPairing = { socket: WebSocketSession, kBtPin: ByteArray ->
            val first = (socket.incoming.receive() as Frame.Text).readText()
            served.complete(Served(first, kBtPin))
        },
    )

    @Test
    fun theRightPinReachesServePinPairingWithTheDerivedKBtPin() =
        runBlocking<Unit> {
            val window = FakePinWindow(vector.str("pin").toByteArray(Charsets.UTF_8))
            val served = CompletableDeferred<Served>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission(), window, served = served).accept(server) }
            val pin = vector.str("pin").toByteArray(Charsets.UTF_8)
            val fakeClient = FakePinClient(pin, vector.hex("n_s"), vector.hex("ya"))
            val handshake = checkNotNull(runCpaceAndNoise(client, fakeClient))
            client.writeRecord(firstTransportRecord(handshake.c1, "pair/hello"))
            val result = withTimeout(WAIT) { served.await() }
            assertEquals("pair/hello", result.text)
            assertArrayEquals(vector.hex("k_bt_pin"), result.kBtPin)
            withTimeout(WAIT) { link.await() }
            assertEquals(0, window.failures)
        }

    @Test
    fun noPinWindowOpenClosesTheChannelWithoutAnAnswer() =
        runBlocking<Unit> {
            val window = FakePinWindow(pin = null)
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission(), window).accept(server) }
            val challenge = BtPreauthRecord.decode(client.readPreauthRecord())
            assertEquals(BtPreauthRecord.KIND_CHALLENGE, challenge.kind)
            client.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_CPACE1, vector.hex("Ya")))
            // Không trả lời gì (không `cpace2`, không `reject`): kênh chỉ đóng.
            assertTrue(client.readPreauthRecord2OrClosed())
            withTimeout(WAIT) { link.await() }
            assertEquals(0, window.failures)
        }

    @Test
    fun aLowOrderYaClosesTheChannelWithoutAnAnswer() =
        runBlocking<Unit> {
            val lowOrder = invalid.single { it.str("reason") == "low_order_ya" }
            val window = FakePinWindow(lowOrder.str("pin").toByteArray(Charsets.UTF_8))
            val (client, server) =
                InMemoryBluetoothLinkIo.pair()
            val link =
                scope.async {
                    acceptor(admission(), window, nS = lowOrder.hex("n_s"), ybScalar = lowOrder.hex("yb"))
                        .accept(server)
                }
            val challenge = BtPreauthRecord.decode(client.readPreauthRecord())
            assertEquals(BtPreauthRecord.KIND_CHALLENGE, challenge.kind)
            client.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_CPACE1, lowOrder.hex("Ya_attacker")))
            assertTrue(client.readPreauthRecord2OrClosed())
            withTimeout(WAIT) { link.await() }
            // Không phải một lần thử PIN thật (điểm bậc thấp, không cần biết PIN): không tính vào bộ đếm.
            assertEquals(0, window.failures)
        }

    @Test
    fun aWrongPinOnTheClientSideIsRejectedAndCountsOneFailure() =
        runBlocking<Unit> {
            // Vector âm "PIN sai ở một phía": client gõ pin_c, S đang chờ pin_s — generator mỗi bên khác nhau.
            val wrongPin = invalid.single { it.str("reason") == "wrong_pin" }
            val window = FakePinWindow(wrongPin.str("pin_s").toByteArray(Charsets.UTF_8))
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link =
                scope.async {
                    acceptor(admission(), window, nS = wrongPin.hex("n_s"), ybScalar = wrongPin.hex("yb"))
                        .accept(server)
                }
            val pinC = wrongPin.str("pin_c").toByteArray(Charsets.UTF_8)
            val fakeClient = FakePinClient(pinC, wrongPin.hex("n_s"), wrongPin.hex("ya"))
            assertArrayEquals(wrongPin.hex("Ya"), fakeClient.ya)
            val outcome = runCpaceAndNoiseOrRejected(client, fakeClient)
            assertEquals(FakeNoiseResult.REJECTED_OR_CLOSED, outcome)
            withTimeout(WAIT) { link.await() }
            assertEquals(1, window.failures)
        }

    /** Bước 1-4 của CPace+Noise phía C (0.4.5 "PIN"); `null` khi `noise2` không mở được (PIN sai). */
    private suspend fun runCpaceAndNoise(
        io: InMemoryBluetoothLinkIo,
        fakeClient: FakePinClient,
    ): NoiseHandshake? {
        val challenge = BtPreauthRecord.decode(io.readPreauthRecord())
        assertEquals(BtPreauthRecord.KIND_CHALLENGE, challenge.kind)
        io.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_CPACE1, fakeClient.ya))
        val cpace2 = BtPreauthRecord.decode(io.readPreauthRecord())
        assertEquals(BtPreauthRecord.KIND_CPACE2, cpace2.kind)
        val keyed = fakeClient.deriveLinkKeyFromCpace2(cpace2.payload)
        val prologue = NoiseNNpsk0.prologue(fakeClient.nS)
        val initiator = NoiseInitiator(keyed, prologue, X25519Keys.generatePrivateKey())
        io.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE1, initiator.message1()))
        val noise2 = BtPreauthRecord.decode(io.readPreauthRecord())
        if (noise2.kind != BtPreauthRecord.KIND_NOISE2) return null
        return initiator.openMessage2(noise2.payload)
    }

    private enum class FakeNoiseResult { REJECTED_OR_CLOSED }

    private suspend fun runCpaceAndNoiseOrRejected(
        io: InMemoryBluetoothLinkIo,
        fakeClient: FakePinClient,
    ): FakeNoiseResult {
        val handshake = runCpaceAndNoise(io, fakeClient)
        check(handshake == null) { "PIN sai mà noise2 vẫn mở được" }
        return FakeNoiseResult.REJECTED_OR_CLOSED
    }

    /** `true` nếu đọc tiếp chỉ gặp kênh đóng (không có `reject` lẫn `cpace2`) — "đóng không trả lời". */
    private suspend fun InMemoryBluetoothLinkIo.readPreauthRecord2OrClosed(): Boolean =
        try {
            readPreauthRecord()
            false
        } catch (_: BtLinkException) {
            true
        }

    private fun firstTransportRecord(
        c1: ByteArray,
        text: String,
    ): ByteArray =
        BtTransportCipher(c1).seal(
            BtTransportFrame.encode(BtLinkConstants.CHAN_CONTROL, more = false, long = false, text.toByteArray()),
        )

    private companion object {
        val WAIT = 5.seconds
    }
}

/**
 * Phía C của CPace+Noise cho ghép đôi PIN (0.4.5 "PIN"): CPace dùng lại [CPaceX25519SHA512] (đã có vector riêng,
 * không phải mục tiêu của test này); Noise dựng độc lập với [NoiseNNpsk0] qua [NoiseInitiator], như
 * [BtQrPairingAcceptorTest]'s `FakeNoiseInitiator`.
 */
private class FakePinClient(
    prs: ByteArray,
    val nS: ByteArray,
    private val yaScalar: ByteArray,
) {
    private val ci = BtPinPairingKeys.CPACE_CI.toByteArray(Charsets.US_ASCII)
    private val g = CPaceX25519SHA512.calculateGenerator(prs, ci, nS)
    val ya: ByteArray = checkNotNull(CPaceX25519SHA512.scalarMultVfy(yaScalar, g))

    /** `K_bt_pinlink` từ `cpace2` (`Yb`) — client của test luôn tính ra một khoá, dù (nếu PIN sai ở client) nó sẽ
     * khác khoá S dùng, nên `noise1` sẽ không mở được (hành vi cần kiểm). */
    fun deriveLinkKeyFromCpace2(yb: ByteArray): ByteArray {
        val k = checkNotNull(CPaceX25519SHA512.scalarMultVfy(yaScalar, yb))
        val isk = CPaceX25519SHA512.isk(nS, k, ya, EMPTY, yb, EMPTY)
        return BtPinPairingKeys.kBtPinlink(isk, nS)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

private class NoiseHandshake(
    val c1: ByteArray,
    val c2: ByteArray,
)

/** Phía C độc lập của `Noise_NNpsk0_25519_ChaChaPoly_SHA256` (rev. 34) — như `FakeNoiseInitiator` của
 * [BtQrPairingAcceptorTest], ở đây keyed bằng `K_bt_pinlink` thay vì `K_bt_plink`. */
private class NoiseInitiator(
    private val psk: ByteArray,
    private val prologue: ByteArray,
    private val ePrivC: ByteArray,
) {
    private lateinit var ck1: ByteArray
    private lateinit var h4: ByteArray

    fun message1(): ByteArray {
        val (h0, ck0) = initializeSymmetric()
        val h1 = mixHash(h0, prologue)
        val pskStep = mixKeyAndHashPsk(ck0, h1, psk)
        val ePubC = X25519Keys.publicFromPrivate(ePrivC)
        val h3 = mixHash(pskStep.h, ePubC)
        val eStep = mixKey(pskStep.ck, ePubC)
        val tag = ChaCha20Poly1305Aead.seal(eStep.tempK, ZERO_NONCE, ByteArray(0), h3)
        ck1 = eStep.ck
        h4 = mixHash(h3, tag)
        return ePubC + tag
    }

    fun openMessage2(message2: ByteArray): NoiseHandshake? {
        val ePubS = message2.copyOfRange(0, X25519Keys.KEY_SIZE)
        val tag = message2.copyOfRange(X25519Keys.KEY_SIZE, message2.size)
        val h5 = mixHash(h4, ePubS)
        val eStep = mixKey(ck1, ePubS)
        val eeStep = mixKey(eStep.ck, X25519Keys.sharedSecret(ePrivC, ePubS))
        return try {
            ChaCha20Poly1305Aead.open(eeStep.tempK, ZERO_NONCE, tag, h5)
            val (c1, c2) = NoiseNNpsk0.split(eeStep.ck)
            NoiseHandshake(c1, c2)
        } catch (_: GeneralSecurityException) {
            null
        }
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

    private fun initializeSymmetric(): Pair<ByteArray, ByteArray> {
        val name = NoiseNNpsk0.PROTOCOL_NAME
        val h = if (name.size <= HASH_SIZE) name else sha256(name)
        return h to h
    }

    private fun mixHash(
        h: ByteArray,
        data: ByteArray,
    ): ByteArray = sha256(h + data)

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private companion object {
        const val HASH_SIZE = 32
        val ZERO_NONCE = ByteArray(ChaCha20Poly1305Aead.NONCE_SIZE)
    }
}
