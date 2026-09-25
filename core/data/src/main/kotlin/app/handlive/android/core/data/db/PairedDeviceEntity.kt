package app.handlive.android.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Platform of a paired client (`peer_platform`, 0.9.1: `macos`, `ios` or `ipados`). */
enum class PeerPlatform(
    val wire: String,
) {
    MACOS("macos"),
    IOS("ios"),
    IPADOS("ipados"),
    ;

    companion object {
        fun fromWire(value: String): PeerPlatform? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One row of `paired_device` (0.9.1): a Mac, iPhone or iPad paired with this phone. [prkEnc] is the pair's `PRK`
 * sealed by the Tink AEAD bound to `hl_master` (empty for a tombstone, PAIR-03 step 7). Not a data class: byte
 * arrays have no value equality.
 */
@Entity(
    tableName = "paired_device",
    indices = [Index(value = ["peer_device_id"], unique = true)],
)
class PairedDeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "pair_id")
    val pairId: String,
    @ColumnInfo(name = "peer_device_id")
    val peerDeviceId: String,
    @ColumnInfo(name = "peer_name")
    val peerName: String,
    @ColumnInfo(name = "peer_platform")
    val peerPlatform: PeerPlatform,
    @ColumnInfo(name = "peer_model")
    val peerModel: String?,
    @ColumnInfo(name = "peer_ik_sig_pub", typeAffinity = ColumnInfo.BLOB)
    val peerIkSigPub: ByteArray,
    @ColumnInfo(name = "peer_ik_dh_pub", typeAffinity = ColumnInfo.BLOB)
    val peerIkDhPub: ByteArray,
    @ColumnInfo(name = "peer_bt_address")
    val peerBtAddress: String? = null,
    @ColumnInfo(name = "prk_enc", typeAffinity = ColumnInfo.BLOB)
    val prkEnc: ByteArray,
    @ColumnInfo(name = "attestation", typeAffinity = ColumnInfo.BLOB)
    val attestation: ByteArray,
    @ColumnInfo(name = "sig_self", typeAffinity = ColumnInfo.BLOB)
    val sigSelf: ByteArray,
    @ColumnInfo(name = "sig_peer", typeAffinity = ColumnInfo.BLOB)
    val sigPeer: ByteArray,
    @ColumnInfo(name = "features_json", defaultValue = "{}")
    val featuresJson: String = "{}",
    @ColumnInfo(name = "relay_registered", defaultValue = "0")
    val relayRegistered: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long? = null,
    @ColumnInfo(name = "revoked_at")
    val revokedAt: Long? = null,
)
