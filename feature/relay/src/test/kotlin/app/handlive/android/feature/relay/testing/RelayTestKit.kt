package app.handlive.android.feature.relay.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.crypto.keystore.SecretSealer
import app.handlive.android.core.data.db.HandLiveDatabase
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.NewPair
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.data.pairing.PeerKeys
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.data.pairing.SignedAttestation
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityFeatures
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.transport.relay.RelayApi
import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayHttp
import app.handlive.android.core.transport.relay.RelayIdentity
import app.handlive.android.core.transport.relay.RelayResponse
import app.handlive.android.core.transport.relay.RelayUnreachableException
import java.util.UUID
import app.handlive.android.core.protocol.capability.RelayFeature as RelayCapability
import app.handlive.android.core.protocol.capability.SmsFeature as SmsCapability

/** The phone's device id in these tests. */
const val PHONE_DEVICE_ID = "21fe31df-a154-8261-a26b-f854046fd227"

/** A client's latest capability as `features_json` stores it (the whole `capability` data, CONN-01). */
object Features {
    val SMS_NOTIFY = of(SmsCapability(enabled = true, notify = true))
    val SMS_SILENT = of(SmsCapability(enabled = true, notify = false))
    val RELAY_OFF = of(SmsCapability(enabled = true, notify = true), RelayCapability(enabled = false))

    fun of(
        sms: SmsCapability?,
        relay: RelayCapability? = null,
    ): String =
        ProtocolJson.encodeToString(
            CapabilityData.serializer(),
            CapabilityData(1, "1.0.0 (100)", "ios", "18.0", "iPhone16,1", CapabilityFeatures(sms = sms, relay = relay)),
        )
}

/**
 * A relay answering the phone's REST calls: registration, challenge and token by default, anything else from a
 * script (`enqueue`) or 500. Records every call.
 */
class FakeRelayHttp(
    private val clock: () -> Long,
) : RelayHttp {
    class Call(
        val method: String,
        val path: String,
        val body: String?,
        val bearer: String?,
    )

    val calls = mutableListOf<Call>()
    private val scripted = HashMap<String, ArrayDeque<RelayResponse>>()
    private var tokens = 0

    /** No response at all (CONN-03 E1). */
    var unreachable = false

    fun enqueue(
        method: String,
        path: String,
        status: Int,
        body: String = "",
        retryAfterMillis: Long? = null,
    ) {
        scripted.getOrPut("$method $path") { ArrayDeque() }.addLast(RelayResponse(status, body, retryAfterMillis))
    }

    fun calls(
        method: String,
        path: String,
    ): List<Call> = calls.filter { it.method == method && it.path == path }

    fun pushes(): List<PushRequest> =
        calls("POST", "/v1/push").map { ProtocolJson.decodeFromString(PushRequest.serializer(), it.body!!) }

    override suspend fun send(
        method: String,
        path: String,
        body: String?,
        bearer: String?,
    ): RelayResponse {
        if (unreachable) throw RelayUnreachableException("offline")
        calls += Call(method, path, body, bearer)
        scripted["$method $path"]?.removeFirstOrNull()?.let { return it }
        return when (path) {
            "/v1/devices" -> {
                RelayResponse(201, """{"device_id":"$PHONE_DEVICE_ID","created_at":${clock()}}""")
            }

            "/v1/auth/challenge" -> {
                RelayResponse(
                    200,
                    """{"challenge":"$CHALLENGE","expires_at":${clock() + 60_000}}""",
                )
            }

            "/v1/auth/token" -> {
                RelayResponse(200, """{"access_token":"jwt-${++tokens}","expires_in":900}""")
            }

            else -> {
                RelayResponse(500, """{"error":{"code":"INTERNAL","message":"not scripted"}}""")
            }
        }
    }

    fun error(code: String) = """{"error":{"code":"$code","message":"$code"}}"""

    private companion object {
        /** 32 zero bytes, base64url without padding. */
        val CHALLENGE = "A".repeat(43)
    }
}

/** Seals nothing: the stored `prk_enc` is the PRK itself (the Keystore is not part of these tests). */
object PlainSealer : SecretSealer {
    override fun seal(
        plaintext: ByteArray,
        context: String,
    ): ByteArray = plaintext.copyOf()

    override fun open(
        sealed: ByteArray,
        context: String,
    ): ByteArray = sealed.copyOf()
}

/** An in-memory database, the pair stores and a relay client on [FakeRelayHttp], all on the virtual clock [now]. */
class RelayFixture {
    var now = 1_727_150_000_000L
    val clock: () -> Long = { now }
    val database: HandLiveDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HandLiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val pairs = PairStore(database.pairedDevices(), { PlainSealer }, clock = clock)
    val relayPairs = RelayPairs(database.pairedDevices()) { PlainSealer }
    val http = FakeRelayHttp(clock)
    val auth =
        RelayAuth(http, RelayIdentity(PHONE_DEVICE_ID, ByteArray(32) { 1 }, "0.0.1 (1)") { ByteArray(64) }, clock)
    val api = RelayApi(http, auth)

    /** A stored pair; [registered] with the relay, [features] = the client's latest capability. */
    suspend fun addPair(
        platform: PeerPlatform = PeerPlatform.IOS,
        registered: Boolean = true,
        features: String = Features.SMS_NOTIFY,
        prk: ByteArray = ByteArray(32) { 9 },
        peerDeviceId: String = UUID.randomUUID().toString(),
    ): String {
        val pairId = UUID.randomUUID().toString()
        pairs.save(
            NewPair(
                pairId = pairId,
                peer = PeerKeys(peerDeviceId, ikSigPub = ByteArray(32) { 3 }, ikDhPub = ByteArray(32) { 4 }),
                peerName = "iPhone của Lan",
                peerPlatform = platform,
                peerModel = null,
                attestation =
                    SignedAttestation(
                        bytes = "HLPAIR1".toByteArray() + ByteArray(120) { it.toByte() },
                        sigSelf = ByteArray(64) { 5 },
                        sigPeer = ByteArray(64) { 6 },
                        createdAt = now,
                    ),
            ),
            prk,
        )
        relayPairs.markRegistered(pairId, registered)
        pairs.recordSeen(pairId, features)
        return pairId
    }

    fun close() = database.close()
}
