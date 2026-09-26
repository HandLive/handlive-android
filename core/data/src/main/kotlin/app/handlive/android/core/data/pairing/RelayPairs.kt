package app.handlive.android.core.data.pairing

import app.handlive.android.core.crypto.keystore.SecretSealer
import app.handlive.android.core.data.db.PairedDeviceDao
import app.handlive.android.core.data.db.PeerPlatform

/** What `POST /v1/pairs` sends for a pair (PAIR-01 API 8): this phone is `device_a`, the client `device_b`. */
class RelayPairRegistration(
    val pairId: String,
    val peerDeviceId: String,
    val createdAt: Long,
    val attestation: ByteArray,
    val sigSelf: ByteArray,
    val sigPeer: ByteArray,
)

/** An iPhone or iPad pair registered with the relay, with the `PRK` that `K_push` derives from (CONN-04). */
class PushTarget(
    val pairId: String,
    val peerDeviceId: String,
    val peerPlatform: PeerPlatform,
    /** The client's latest capability (`features_json`): `features.sms.notify`, `features.relay.enabled`. */
    val featuresJson: String,
    val prk: ByteArray,
)

/**
 * The relay's view of the pairs (`relay_registered`, tombstones; PAIR-01 API 8, PAIR-02 API 1, PAIR-03 E3, CONN-04),
 * next to [PairStore] on the same `paired_device` rows. Never logs keys or peer data.
 */
class RelayPairs(
    private val dao: PairedDeviceDao,
    sealerProvider: () -> SecretSealer,
) {
    private val sealer by lazy(sealerProvider)

    /** PAIR-03 step 9: the relay confirmed the revocation (or never knew the pair). */
    suspend fun deleteTombstone(pairId: String): Boolean = dao.deleteTombstone(pairId) > 0

    /** PAIR-03 E3: tombstones still waiting for `POST /v1/pairs/{pair_id}/revoke`. */
    suspend fun tombstonesToRevoke(): List<String> = dao.tombstonesToRevoke()

    /** CONN-03 step 3, SET-02 API 6: pairs to register with `POST /v1/pairs`. */
    suspend fun unregistered(): List<RelayPairRegistration> =
        dao.unregisteredWithRelay().map { row ->
            RelayPairRegistration(
                row.pairId,
                row.peerDeviceId,
                row.createdAt,
                row.attestation,
                row.sigSelf,
                row.sigPeer,
            )
        }

    suspend fun markRegistered(
        pairId: String,
        registered: Boolean,
    ) = dao.setRelayRegistered(pairId, registered)

    /** SET-02 A4 "Remove Device from Server": every pair stays, none is known to the relay any more. */
    suspend fun forgetRegistrations() = dao.clearRelayRegistration()

    /** SMS-02 step 10: iPhone and iPad pairs registered with the relay whose `PRK` opens (CONN-04). */
    suspend fun pushTargets(): List<PushTarget> =
        dao.pushTargets().mapNotNull { row ->
            row.toSecret(sealer)?.prk?.let { prk ->
                PushTarget(row.pairId, row.peerDeviceId, row.peerPlatform, row.featuresJson, prk)
            }
        }
}
