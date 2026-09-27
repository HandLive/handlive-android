package app.handlive.android.feature.call.testing

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.context.CallNumbers
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.context.SimLabels
import app.handlive.android.feature.call.log.AccountSubIds
import app.handlive.android.feature.call.log.CallLogProvider
import app.handlive.android.feature.call.log.CallLogRow
import app.handlive.android.feature.call.module.CallAccess
import app.handlive.android.feature.call.module.CallTelecom
import app.handlive.android.feature.call.module.MissedCall
import app.handlive.android.feature.call.module.OfflineCallDelivery
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer

const val BASE_TS = 1_727_150_400_000L

/** `feature.call` and the runtime permissions; every call permission granted by default. */
class FakeAccess : CallAccess {
    var enabled = true
    val granted =
        mutableSetOf(
            AndroidPermissions.READ_PHONE_STATE,
            AndroidPermissions.READ_CALL_LOG,
            AndroidPermissions.ANSWER_PHONE_CALLS,
            AndroidPermissions.READ_CONTACTS,
        )

    override fun enabled() = enabled

    override fun granted(permission: String) = permission in granted
}

/** `TelecomManager` that records what it was asked; [phone] is what `isInCall()` would tell. */
class FakeTelecom : CallTelecom {
    val calls = mutableListOf<String>()
    var endCallResult = true
    var phone: PhoneState? = PhoneState.RINGING
    var securityException = false

    override fun acceptRingingCall() {
        if (securityException) throw SecurityException("ANSWER_PHONE_CALLS")
        calls += "accept"
    }

    override fun endCall(): Boolean {
        if (securityException) throw SecurityException("ANSWER_PHONE_CALLS")
        calls += "end"
        return endCallResult
    }

    override fun phoneState() = phone
}

/**
 * Vietnamese numbers: a leading `0` becomes `+84`; anything with a letter stays as it is. [names] are the contacts;
 * [lookups] counts the queries that reach them.
 */
class FakeNumbers : CallNumbers {
    val names = mutableMapOf("+84900000123" to "Nguyễn Văn A", "+84900000456" to "Trần Thị B")
    var contacts = true
    var lookups = 0

    override fun normalize(
        raw: String,
        subId: Int?,
    ): String = if (raw.startsWith("0") && raw.all(Char::isDigit)) "+84" + raw.drop(1) else raw

    override fun name(number: String): String? {
        if (!contacts) return null
        lookups++
        return names[number]
    }
}

/** Two SIMs, "SIM 1" and "SIM 2"; with [single] the phone has one SIM and no label is shown. */
class FakeSimLabels : SimLabels {
    var single = false

    override fun label(subId: Int): String? = if (single) null else "SIM $subId"
}

/** The call log provider in memory, with the semantics of the CALL-04 queries. */
class FakeCallLog : CallLogProvider {
    val rows = mutableListOf<CallLogRow>()
    var failing = false
    private var nextId = 5_000L

    /** A row with the next `_ID` unless [id] is given; [change] sets the other columns. */
    fun add(
        type: Int,
        date: Long,
        number: String? = "0900000123",
        id: Long = nextId,
        change: (CallLogRow) -> CallLogRow = { it },
    ): CallLogRow {
        nextId = maxOf(nextId, id) + 1
        val row = change(CallLogRow(id, number, PRESENTATION_ALLOWED, null, type, date, 0, ACCOUNT_COMPONENT, "sim1"))
        rows += row
        return row
    }

    fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override fun maxId(): Long? = check().let { rows.maxOfOrNull { it.id } }

    override fun newestIdsSince(
        since: Long,
        limit: Int,
    ): List<Long> =
        check().let {
            rows
                .filter { it.date >= since }
                .map { it.id }
                .sortedDescending()
                .take(limit)
        }

    override fun rows(
        afterId: Long,
        inclusive: Boolean,
        limit: Int,
    ): List<CallLogRow> =
        check().let {
            rows
                .filter { if (inclusive) it.id >= afterId else it.id > afterId }
                .sortedBy { it.id }
                .take(limit)
        }

    private fun check() = check(!failing) { "call log unavailable" }

    companion object {
        const val PRESENTATION_ALLOWED = 1
        const val ACCOUNT_COMPONENT = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"

        /** Phone accounts `sim1` and `sim2` are the subscriptions 1 and 2 (API 30+). */
        val SUB_IDS =
            AccountSubIds { component, id ->
                if (component ==
                    ACCOUNT_COMPONENT
                ) {
                    id?.removePrefix("sim")?.toIntOrNull()
                } else {
                    null
                }
            }
    }
}

/** What the relay module was asked, with the virtual time of each call. */
class FakeOffline(
    private val now: () -> Long,
) : OfflineCallDelivery {
    class Push(
        val at: Long,
        val state: CallStateData,
        val connected: Set<String>,
    )

    val ringing = mutableListOf<Pair<Long, String>>()
    val incoming = mutableListOf<Push>()
    val ringingEnded = mutableListOf<Pair<Long, String>>()
    val missed = mutableListOf<Pair<MissedCall, Set<String>>>()

    override fun ringing(callId: String) {
        ringing += now() to callId
    }

    override fun incoming(
        state: CallStateData,
        connected: Set<String>,
    ) {
        incoming += Push(now(), state, connected)
    }

    override fun ringingEnded(callId: String) {
        ringingEnded += now() to callId
    }

    override fun missed(
        missed: MissedCall,
        connected: Set<String>,
    ) {
        this.missed += missed to connected
    }
}

/** A connected client as the phone sees it: everything Android sends it, decoded. */
class FakeClient(
    val name: String,
    val pairId: String,
    private val clock: () -> Long,
    val platform: PeerPlatform = PeerPlatform.MACOS,
) {
    class Sent(
        val type: MessageType,
        val plaintext: ByteArray,
    )

    val sent = mutableListOf<Sent>()
    val effective = MutableStateFlow(setOf(Feature.CALL))
    var session = newSession()
        private set

    private fun newSession() =
        PeerSession(
            PeerSession.PeerInfo(pairId, "$pairId-device", name, platform),
            PeerSession.Channel.LAN,
            effective,
            MutableStateFlow<CapabilityData?>(null),
            { type, plaintext, _ -> sent += Sent(type, plaintext) },
            clock,
        )

    /** A new `/v1/ctl` session of the same pair (CONN-02 reconnect). */
    fun reconnect() {
        session = newSession()
    }

    fun acks(): List<Ack> = sent.filter { it.type == MessageType.ACK }.map { PlaintextCodec.decodeAck(it.plaintext) }

    fun states(): List<CallStateData> = ops(CallOp.STATE, CallStateData.serializer())

    fun logNews(): List<CallLogNewData> = ops(CallOp.LOG_NEW, CallLogNewData.serializer())

    fun events(): List<String> = sent.filter { it.type == MessageType.CALL_EVENT }.map { String(it.plaintext) }

    private fun <T> ops(
        op: String,
        serializer: KSerializer<T>,
    ): List<T> =
        sent
            .filter { it.type == MessageType.CALL_EVENT && PlaintextCodec.decodePayload(it.plaintext).op == op }
            .map { PlaintextCodec.decodeOp(it.plaintext, serializer).data }
}
