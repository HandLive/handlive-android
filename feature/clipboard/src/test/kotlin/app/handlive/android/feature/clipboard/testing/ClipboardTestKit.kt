package app.handlive.android.feature.clipboard.testing

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityFeatures
import app.handlive.android.core.protocol.capability.ClipboardFeature
import app.handlive.android.core.protocol.clipboard.ClipboardAckData
import app.handlive.android.core.protocol.clipboard.ClipboardCancelData
import app.handlive.android.core.protocol.clipboard.ClipboardChunkPlaintext
import app.handlive.android.core.protocol.clipboard.ClipboardConflictData
import app.handlive.android.core.protocol.clipboard.ClipboardOp
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.ClipNotices
import app.handlive.android.feature.clipboard.TransferProgress
import app.handlive.android.feature.clipboard.module.ClipClock
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.ClipPlatform
import app.handlive.android.feature.clipboard.module.ClipboardModule
import app.handlive.android.feature.clipboard.module.FocusProbe
import app.handlive.android.feature.clipboard.module.ImageNormalizer
import app.handlive.android.feature.clipboard.module.LocalDevice
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.NormalizedImage
import app.handlive.android.feature.clipboard.system.ClipLabel
import app.handlive.android.feature.clipboard.system.ClipboardWriter
import app.handlive.android.feature.clipboard.system.OwnWrite
import app.handlive.android.feature.clipboard.system.SystemClipboard
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.connection.session.SessionRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

const val PHONE_ID = "8c7d6e5f-4a3b-8c2d-9e1f-0a1b2c3d4e5f"
const val MAC_ID = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718"
const val IPAD_ID = "2c3d4e5f-6a7b-8c9d-8e0f-1a2b3c4d5e6f"
const val PHONE_NAME = "Pixel của Lan"
const val BASE_WALL = 1_727_150_000_000L

/** The system clipboard: records writes; [current] is what the description read with focus returns. */
class FakeWriter(
    private val wall: () -> Long,
    private val elapsed: () -> Long,
) : ClipboardWriter {
    class Written(
        val clipId: String,
        val text: String?,
        val file: File?,
        val sensitive: Boolean,
    )

    val writes = mutableListOf<Written>()
    var failWrites = false
    var cleared = 0
    var current: ClipLabel? = null

    override fun writeText(
        clipId: String,
        sha256: ByteArray,
        text: String,
        sensitive: Boolean,
    ) = record(Written(clipId, text, null, sensitive), sha256)

    override fun writeFile(
        clipId: String,
        sha256: ByteArray,
        file: File,
        sensitive: Boolean,
    ) = record(Written(clipId, null, file, sensitive), sha256)

    override fun clear() {
        cleared++
        current = null
    }

    override fun describe(): ClipLabel? = current

    /** The user copies something in another app. */
    fun userCopies() {
        current = ClipLabel("Chrome", wall())
    }

    private fun record(
        written: Written,
        sha256: ByteArray,
    ): OwnWrite {
        if (failWrites) throw SecurityException("clipboard write refused")
        val at = wall()
        current = ClipLabel(SystemClipboard.LABEL, at)
        writes += written
        return OwnWrite(written.clipId, sha256, at, at, elapsed(), written.file)
    }
}

class FakeNotices : ClipNotices {
    val messages = mutableListOf<ClipMessage>()
    var sensitiveBlocked = 0
    val conflicts = mutableListOf<Pair<String, String>>()
    val progress = mutableListOf<TransferProgress>()

    override fun show(message: ClipMessage) {
        messages += message
    }

    override fun sensitiveBlocked() {
        sensitiveBlocked++
    }

    override fun conflict(
        deviceName: String,
        clipId: String,
    ) {
        conflicts += deviceName to clipId
    }

    override fun progress(progress: TransferProgress) {
        this.progress += progress
    }
}

class FakeFocus : FocusProbe {
    override var appFocused = false
    override var accessibilityRunning = false
    var verificationsStarted = 0
    var canStart = true

    override fun startVerification(): Boolean {
        verificationsStarted++
        return canStart
    }
}

fun clientCapability(
    mimes: List<String> = listOf(ClipboardValues.MIME_TEXT, ClipboardValues.MIME_PNG, ClipboardValues.MIME_JPEG),
    maxTextBytes: Long = 1_048_576,
    appVersion: String = "1.0.0 (1)",
) = CapabilityData(
    protocol = 1,
    appVersion = appVersion,
    platform = "macos",
    osVersion = "15.4",
    model = "MacBookPro18,3",
    features =
        CapabilityFeatures(
            clipboard = ClipboardFeature(true, true, maxTextBytes, 10_485_760, mimes),
        ),
)

/** A connected client as the phone sees it: what Android sends it, decoded. */
class FakeClient(
    val name: String,
    val deviceId: String,
    val pairId: String,
    clock: () -> Long,
) {
    class Sent(
        val type: MessageType,
        val plaintext: ByteArray,
        val id: String,
    )

    val sent = mutableListOf<Sent>()
    var connected = true
    val effective = MutableStateFlow(setOf(Feature.CLIPBOARD))
    val capability = MutableStateFlow<CapabilityData?>(clientCapability())
    val session =
        PeerSession(
            PeerSession.PeerInfo(pairId, deviceId, name, PeerPlatform.MACOS),
            PeerSession.Channel.LAN,
            effective,
            capability,
            { type, plaintext, id ->
                check(connected) { "session closed" }
                sent += Sent(type, plaintext, id)
            },
            clock,
        )

    private val clipboardJson get() =
        sent.filter {
            it.type == MessageType.CLIPBOARD &&
                it.plaintext.first() != 0.toByte()
        }

    /** `(envelope id, data)` of every `clipboard/push` sent to this client. */
    fun pushes(): List<Pair<String, ClipboardPushData>> = ops(ClipboardOp.PUSH, ClipboardPushData.serializer())

    fun cancels(): List<ClipboardCancelData> =
        ops(ClipboardOp.CANCEL, ClipboardCancelData.serializer()).map { it.second }

    fun conflicts(): List<ClipboardConflictData> =
        ops(ClipboardOp.CONFLICT, ClipboardConflictData.serializer()).map {
            it.second
        }

    fun chunks(): List<ClipboardChunkPlaintext> =
        sent
            .filter { it.type == MessageType.CLIPBOARD && it.plaintext.first() == 0.toByte() }
            .map { ClipboardChunkPlaintext.decode(it.plaintext) }

    fun acks(): List<Ack> = sent.filter { it.type == MessageType.ACK }.map { PlaintextCodec.decodeAck(it.plaintext) }

    fun ackData(): List<ClipboardAckData> =
        acks().map { ack ->
            ProtocolJson.decodeFromJsonElement(
                ClipboardAckData.serializer(),
                ack.data ?: checkNotNull(ack.error?.details),
            )
        }

    private fun <T> ops(
        op: String,
        serializer: KSerializer<T>,
    ): List<Pair<String, T>> =
        clipboardJson
            .filter { PlaintextCodec.decodePayload(it.plaintext).op == op }
            .map { it.id to PlaintextCodec.decodeOp(it.plaintext, serializer).data }
}

/** The clipboard module on the test scheduler, with fake Android pieces and a router for incoming envelopes. */
class ClipboardHarness(
    private val scope: TestScope,
    dir: File,
) {
    val wall = { BASE_WALL + scope.testScheduler.currentTime }
    val elapsed = { scope.testScheduler.currentTime }
    val writer = FakeWriter(wall, elapsed)
    val notices = FakeNotices()
    val focus = FakeFocus()
    var freeSpace = Long.MAX_VALUE
    val clipDir = File(dir, "clip")
    val sessions = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
    val settings = MutableStateFlow(HandLiveSettings(clipA11yConsentAt = BASE_WALL))
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val ids = UuidV7Generator(wall)
    private val images = ImageNormalizer { source, mime, _ -> NormalizedImage(source, mime, WIDTH, HEIGHT) }
    val module =
        ClipboardModule(
            dispatcher,
            sessions,
            settings,
            ClipPlatform(writer, notices, ClipFiles(clipDir, wall) { freeSpace }, images, focus, dispatcher),
            { LocalDevice(PHONE_ID, PHONE_NAME) },
            ClipClock(wall, elapsed),
        )
    private val router = SessionRouter(wall).apply { register(MessageType.CLIPBOARD, module.handler) }

    val mac = FakeClient("MacBook của Lan", MAC_ID, "pair-mac", wall)
    val ipad = FakeClient("iPad của Lan", IPAD_ID, "pair-ipad", wall)

    init {
        module.start()
    }

    fun connect(vararg clients: FakeClient) {
        clients.forEach { it.connected = true }
        sessions.value = sessions.value + clients.associate { it.pairId to it.session }
        run()
    }

    fun disconnect(client: FakeClient) {
        client.connected = false
        sessions.value = sessions.value - client.pairId
        run()
    }

    fun run() = scope.testScheduler.runCurrent()

    fun readText(
        text: String,
        source: String = ClipboardValues.SOURCE_AUTO,
        sensitiveExtra: Boolean = false,
    ) {
        module.onLocalRead(LocalRead.Text(text, sensitiveExtra, source))
        run()
    }

    fun readImage(
        bytes: ByteArray,
        source: String = ClipboardValues.SOURCE_AUTO,
        sensitiveExtra: Boolean = false,
    ) {
        val file = File(clipDir.apply { mkdirs() }, "${ids.next()}.src").apply { writeBytes(bytes) }
        module.onLocalRead(LocalRead.Image(file, ClipboardValues.MIME_PNG, sensitiveExtra, source))
        run()
    }

    /** An envelope from [client]; returns its `id`. */
    suspend fun deliver(
        client: FakeClient,
        type: MessageType,
        plaintext: ByteArray,
    ): String {
        val id = ids.next()
        router.route(client.session, InboundEnvelope(type.wire, id, wall(), plaintext))
        run()
        return id
    }

    suspend fun push(
        client: FakeClient,
        data: ClipboardPushData,
    ) = deliver(
        client,
        MessageType.CLIPBOARD,
        PlaintextCodec.encodeOp(ClipboardOp.PUSH, ClipboardPushData.serializer(), data),
    )

    suspend fun chunk(
        client: FakeClient,
        transferId: String,
        index: Int,
        bytes: ByteArray,
    ) = deliver(client, MessageType.CLIPBOARD, ClipboardChunkPlaintext(transferId, index, bytes).encode())

    suspend fun cancel(
        client: FakeClient,
        transferId: String,
        reason: String,
    ) = deliver(
        client,
        MessageType.CLIPBOARD,
        PlaintextCodec.encodeOp(
            ClipboardOp.CANCEL,
            ClipboardCancelData.serializer(),
            ClipboardCancelData(transferId, reason),
        ),
    )

    suspend fun conflict(
        client: FakeClient,
        data: ClipboardConflictData,
    ) = deliver(
        client,
        MessageType.CLIPBOARD,
        PlaintextCodec.encodeOp(ClipboardOp.CONFLICT, ClipboardConflictData.serializer(), data),
    )

    /** [client] answers Android's push [pushId]. */
    suspend fun ack(
        client: FakeClient,
        pushId: String,
        clipId: String,
        status: String = ClipboardValues.STATUS_APPLIED,
        reason: String? = null,
    ) = deliver(
        client,
        MessageType.ACK,
        PlaintextCodec.encodeAck(Ack.success(pushId, json(ClipboardAckData(clipId, status, reason)))),
    )

    suspend fun ackError(
        client: FakeClient,
        pushId: String,
        clipId: String,
        code: ErrorCode,
        transferId: String? = null,
    ) = deliver(
        client,
        MessageType.ACK,
        PlaintextCodec.encodeAck(
            Ack.failure(
                pushId,
                code,
                code.name,
                json(ClipboardAckData(clipId, ClipboardValues.STATUS_REJECTED, transferId = transferId)),
            ),
        ),
    )

    fun newId(): String = ids.next()

    /** A text push as the Mac sends it (CLIP-02 API 2). */
    fun macText(
        text: String,
        originTs: Long = wall(),
        clipId: String = newId(),
    ) = ClipboardPushData(
        clipId = clipId,
        kind = ClipboardValues.KIND_TEXT,
        mime = ClipboardValues.MIME_TEXT,
        text = text,
        sensitive = false,
        originTs = originTs,
        source = "mac",
        originDeviceId = MAC_ID,
    )

    private fun json(data: ClipboardAckData): JsonObject =
        ProtocolJson.encodeToJsonElement(ClipboardAckData.serializer(), data).jsonObject

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
