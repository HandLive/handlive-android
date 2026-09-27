package app.handlive.android.core.data.pairing

import app.handlive.android.core.crypto.keystore.SecretSealer
import app.handlive.android.core.data.db.PairedDeviceDao
import app.handlive.android.core.data.db.PairedDeviceEntity
import app.handlive.android.core.data.db.PeerPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** A paired client as the UI sees it (PAIR-02): no key material. */
data class PairedDevice(
    val pairId: String,
    val peerDeviceId: String,
    val peerName: String,
    val peerPlatform: PeerPlatform,
    val peerModel: String?,
    val featuresJson: String,
    val createdAt: Long,
    val lastSeenAt: Long?,
    /** PAIR-02 field 10: first 8 hex digits of SHA-256(`attestation`), identical on both devices of the pair. */
    val safetyCode: String,
    /** The relay knows the pair (`relay_registered`, PAIR-01 API 8). */
    val relayRegistered: Boolean = false,
)

/** What the session handshake and the discovery hints need of a pair; [prk] is `null` for a tombstone. */
class PairSecret(
    val pairId: String,
    val peerDeviceId: String,
    val prk: ByteArray?,
    val revoked: Boolean,
)

/** Public keys of the paired client, checked during PAIR-01 (`device_id` = UUIDv8 of `ik_sig_pub`). */
class PeerKeys(
    val deviceId: String,
    val ikSigPub: ByteArray,
    val ikDhPub: ByteArray,
)

/** The pairing attestation (0.6.2) and both Ed25519 signatures over it. */
class SignedAttestation(
    val bytes: ByteArray,
    val sigSelf: ByteArray,
    val sigPeer: ByteArray,
    val createdAt: Long,
)

/** A pair confirmed by PAIR-01 (`pair/confirm` verified), ready to be stored with its `PRK`. */
class NewPair(
    val pairId: String,
    val peer: PeerKeys,
    val peerName: String,
    val peerPlatform: PeerPlatform,
    val peerModel: String?,
    val attestation: SignedAttestation,
)

/**
 * Pairs of this phone (0.9.1 `paired_device`). The `PRK` is sealed with [sealer] and bound to its `pair_id`, so a
 * row copied onto another pair cannot be opened. Never logs keys or peer data.
 */
class PairStore(
    private val dao: PairedDeviceDao,
    sealerProvider: () -> SecretSealer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Created on first use: opening the Keystore is slow and must stay off the main thread. */
    private val sealer by lazy(sealerProvider)

    fun observeActive(): Flow<List<PairedDevice>> = dao.observeActive().map { rows -> rows.map(::toModel) }

    suspend fun activeCount(): Int = dao.activeCount()

    suspend fun find(pairId: String): PairedDevice? = dao.find(pairId)?.takeIf { it.revokedAt == null }?.let(::toModel)

    /** PAIR-01 API 4 rule 4: replaces any older pair of the same client, in one transaction. */
    suspend fun save(
        pair: NewPair,
        prk: ByteArray,
    ) {
        dao.replaceForPeer(
            PairedDeviceEntity(
                pairId = pair.pairId,
                peerDeviceId = pair.peer.deviceId,
                peerName = pair.peerName,
                peerPlatform = pair.peerPlatform,
                peerModel = pair.peerModel,
                peerIkSigPub = pair.peer.ikSigPub,
                peerIkDhPub = pair.peer.ikDhPub,
                prkEnc = sealer.seal(prk, prkContext(pair.pairId)),
                attestation = pair.attestation.bytes,
                sigSelf = pair.attestation.sigSelf,
                sigPeer = pair.attestation.sigPeer,
                createdAt = pair.attestation.createdAt,
            ),
        )
    }

    /**
     * CONN-01 step 7, called from the TLS server thread. A `PRK` that cannot be opened (the Keystore lost its
     * master key) makes the pair unknown, so the client is told `PAIR_UNKNOWN` and pairs again.
     */
    fun secretBlocking(pairId: String): PairSecret? = dao.findBlocking(pairId)?.toSecret(sealer)

    /** CONN-01 API 1: active pairs with their `PRK`, for the discovery hints. */
    suspend fun activeSecrets(): List<PairSecret> =
        dao.active().mapNotNull { row -> row.toSecret(sealer)?.takeIf { it.prk != null } }

    /** CONN-01 step 10: remember when the client was last connected and its latest capability. */
    suspend fun recordSeen(
        pairId: String,
        featuresJson: String,
    ) = dao.recordSeen(pairId, clock(), featuresJson)

    /**
     * PAIR-03 step 7 then 9: wipes the key into a tombstone, then drops the row unless the relay knows the pair —
     * that tombstone stays until the relay confirms the revocation ([RelayPairs.deleteTombstone], E3). Returns
     * `false` if the pair was not active.
     */
    suspend fun revoke(pairId: String): Boolean {
        val revoked = dao.tombstone(pairId, clock()) > 0
        val row = dao.find(pairId)
        if (row != null && !row.relayRegistered) dao.deleteTombstone(pairId)
        return revoked
    }

    /** SET-02 A4/A5: forget every pair. */
    suspend fun deleteAll() = dao.deleteAll()

    companion object {
        /** Android holds at most 8 active pairs (detailed design §4, PAIR-01 E6). */
        const val MAX_ACTIVE_PAIRS = 8
        private const val SAFETY_CODE_BYTES = 4

        fun safetyCode(attestation: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(attestation)
                .copyOf(SAFETY_CODE_BYTES)
                .joinToString("") { "%02x".format(it) }

        internal fun prkContext(pairId: String) = "prk/$pairId"
    }
}

/** A `PRK` that cannot be opened (the Keystore lost its master key) makes the pair unknown (`null`). */
internal fun PairedDeviceEntity.toSecret(sealer: SecretSealer): PairSecret? =
    if (revokedAt != null) {
        PairSecret(pairId, peerDeviceId, prk = null, revoked = true)
    } else {
        try {
            PairSecret(pairId, peerDeviceId, sealer.open(prkEnc, PairStore.prkContext(pairId)), revoked = false)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

private fun toModel(row: PairedDeviceEntity) =
    PairedDevice(
        pairId = row.pairId,
        peerDeviceId = row.peerDeviceId,
        peerName = row.peerName,
        peerPlatform = row.peerPlatform,
        peerModel = row.peerModel,
        featuresJson = row.featuresJson,
        createdAt = row.createdAt,
        lastSeenAt = row.lastSeenAt,
        safetyCode = PairStore.safetyCode(row.attestation),
        relayRegistered = row.relayRegistered,
    )
