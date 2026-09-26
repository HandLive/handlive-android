package app.handlive.android.core.data.pairing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.crypto.keystore.AeadSecretSealer
import app.handlive.android.core.data.db.HandLiveDatabase
import app.handlive.android.core.data.db.PairedDeviceEntity
import app.handlive.android.core.data.db.PeerPlatform
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.UUID

/** `paired_device` (0.9.1) through [PairStore]: PAIR-01 save/replace, CONN-01 lookup, PAIR-02 list, PAIR-03 revoke. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairStoreTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HandLiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private var now = 1_727_150_000_000L
    private val sealer = AeadSecretSealer(newAead())
    private val store = PairStore(database.pairedDevices(), { sealer }, clock = { now })

    @After
    fun tearDown() = database.close()

    @Test
    fun savedPairIsListedAndItsPrkOpensForTheHandshake() =
        runTest {
            val pair = newPair()
            val prk = ByteArray(32) { it.toByte() }
            store.save(pair, prk)

            assertEquals(1, store.activeCount())
            val secret = store.secretBlocking(pair.pairId)!!
            assertArrayEquals(prk, secret.prk)
            assertFalse(secret.revoked)
            val listed = store.observeActive().first().single()
            assertEquals(pair.peerName, listed.peerName)
            assertEquals(PeerPlatform.MACOS, listed.peerPlatform)
            // The stored column is sealed, never the raw PRK.
            val row = database.pairedDevices().findBlocking(pair.pairId)!!
            assertFalse(row.prkEnc.contentEquals(prk))
        }

    @Test
    fun safetyCodeIsTheFirstEightHexDigitsOfTheAttestationHash() =
        runTest {
            val pair = newPair()
            store.save(pair, ByteArray(32))
            val expected =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(pair.attestation.bytes)
                    .copyOf(4)
                    .joinToString("") { "%02x".format(it) }
            assertEquals(expected, store.find(pair.pairId)!!.safetyCode)
            assertEquals(8, expected.length)
        }

    @Test
    fun pairingTheSameClientAgainReplacesItsOldPair() =
        runTest {
            val first = newPair()
            store.save(first, ByteArray(32) { 1 })
            val second = newPair(peerDeviceId = first.peer.deviceId)
            store.save(second, ByteArray(32) { 2 })

            assertEquals(1, store.activeCount())
            assertNull(store.secretBlocking(first.pairId))
            assertArrayEquals(ByteArray(32) { 2 }, store.secretBlocking(second.pairId)!!.prk)
        }

    @Test
    fun revokeWipesTheKeyAndDropsTheTombstoneWhenThePairNeverReachedTheRelay() =
        runTest {
            val pair = newPair()
            store.save(pair, ByteArray(32))
            assertTrue(store.revoke(pair.pairId))
            assertEquals(0, store.activeCount())
            assertNull(database.pairedDevices().findBlocking(pair.pairId))
            assertFalse("second revoke is a no-op", store.revoke(pair.pairId))
        }

    @Test
    fun tombstoneOfARelayRegisteredPairIsKeptAndReportedRevoked() =
        runTest {
            val pair = newPair()
            database.pairedDevices().insert(entity(pair, relayRegistered = true))
            assertTrue(store.revoke(pair.pairId))
            val row = database.pairedDevices().findBlocking(pair.pairId)!!
            assertEquals(now, row.revokedAt)
            assertEquals(0, row.prkEnc.size)
            assertEquals("{}", row.featuresJson)
            val secret = store.secretBlocking(pair.pairId)!!
            assertTrue(secret.revoked)
            assertNull(secret.prk)
        }

    @Test
    fun prkMovedToAnotherPairCannotBeOpened() =
        runTest {
            val victim = newPair()
            store.save(victim, ByteArray(32) { 7 })
            val sealed = database.pairedDevices().findBlocking(victim.pairId)!!.prkEnc
            val attacker = newPair()
            database.pairedDevices().insert(entity(attacker, prkEnc = sealed))
            assertNull(store.secretBlocking(attacker.pairId))
            assertTrue(store.activeSecrets().none { it.pairId == attacker.pairId })
        }

    @Test
    fun recordSeenStoresTimeAndCapability() =
        runTest {
            val pair = newPair()
            store.save(pair, ByteArray(32))
            now += 5_000
            store.recordSeen(pair.pairId, """{"protocol":1}""")
            val device = store.find(pair.pairId)!!
            assertEquals(now, device.lastSeenAt)
            assertEquals("""{"protocol":1}""", device.featuresJson)
        }

    private fun newPair(peerDeviceId: String = UUID.randomUUID().toString()) =
        NewPair(
            pairId = UUID.randomUUID().toString(),
            peer = PeerKeys(peerDeviceId, ikSigPub = ByteArray(32) { 3 }, ikDhPub = ByteArray(32) { 4 }),
            peerName = "MacBook của Lan",
            peerPlatform = PeerPlatform.MACOS,
            peerModel = "Mac15,3",
            attestation =
                SignedAttestation(
                    bytes = UUID.randomUUID().toString().toByteArray(),
                    sigSelf = ByteArray(64) { 5 },
                    sigPeer = ByteArray(64) { 6 },
                    createdAt = now,
                ),
        )

    private fun entity(
        pair: NewPair,
        prkEnc: ByteArray = sealer.seal(ByteArray(32), "prk/${pair.pairId}"),
        relayRegistered: Boolean = false,
    ) = PairedDeviceEntity(
        pairId = pair.pairId,
        peerDeviceId = pair.peer.deviceId,
        peerName = pair.peerName,
        peerPlatform = pair.peerPlatform,
        peerModel = pair.peerModel,
        peerIkSigPub = pair.peer.ikSigPub,
        peerIkDhPub = pair.peer.ikDhPub,
        prkEnc = prkEnc,
        attestation = pair.attestation.bytes,
        sigSelf = pair.attestation.sigSelf,
        sigPeer = pair.attestation.sigPeer,
        relayRegistered = relayRegistered,
        createdAt = pair.attestation.createdAt,
    )

    private companion object {
        fun newAead(): Aead {
            AeadConfig.register()
            return KeysetHandle
                .generateNew(PredefinedAeadParameters.AES256_GCM)
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }
}
