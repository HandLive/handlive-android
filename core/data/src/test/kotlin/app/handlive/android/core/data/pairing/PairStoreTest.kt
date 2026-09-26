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
    private val relay = RelayPairs(database.pairedDevices()) { sealer }

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

    @Test
    fun relayRegistrationFollowsPostPairsAndRemoveFromServer() =
        runTest {
            val first = newPair()
            val second = newPair()
            store.save(first, ByteArray(32))
            store.save(second, ByteArray(32))
            val pending = relay.unregistered()
            assertEquals(setOf(first.pairId, second.pairId), pending.map { it.pairId }.toSet())
            val registration = pending.first { it.pairId == first.pairId }
            assertArrayEquals(first.attestation.bytes, registration.attestation)
            assertArrayEquals(first.attestation.sigSelf, registration.sigSelf)
            assertArrayEquals(first.attestation.sigPeer, registration.sigPeer)

            relay.markRegistered(first.pairId, registered = true)
            assertEquals(listOf(second.pairId), relay.unregistered().map { it.pairId })
            assertTrue(store.find(first.pairId)!!.relayRegistered)

            relay.forgetRegistrations()
            assertEquals(2, relay.unregistered().size)
        }

    @Test
    fun aRevokedRelayPairWaitsForTheRelayThenGoes() =
        runTest {
            val pair = newPair()
            store.save(pair, ByteArray(32))
            relay.markRegistered(pair.pairId, registered = true)
            store.revoke(pair.pairId)
            assertEquals(listOf(pair.pairId), relay.tombstonesToRevoke())
            assertTrue(relay.deleteTombstone(pair.pairId))
            assertEquals(emptyList<String>(), relay.tombstonesToRevoke())
        }

    @Test
    fun pushTargetsAreRegisteredIphoneAndIpadPairsWithTheirPrk() =
        runTest {
            val mac = newPair()
            val iphone = newPair(platform = PeerPlatform.IOS)
            val ipad = newPair(platform = PeerPlatform.IPADOS)
            val unregistered = newPair(platform = PeerPlatform.IOS)
            store.save(mac, ByteArray(32) { 1 })
            store.save(iphone, ByteArray(32) { 2 })
            store.save(ipad, ByteArray(32) { 3 })
            store.save(unregistered, ByteArray(32) { 4 })
            listOf(mac, iphone, ipad).forEach { relay.markRegistered(it.pairId, registered = true) }

            val targets = relay.pushTargets().associateBy { it.pairId }
            assertEquals(setOf(iphone.pairId, ipad.pairId), targets.keys)
            assertArrayEquals(ByteArray(32) { 2 }, targets.getValue(iphone.pairId).prk)
            assertEquals(PeerPlatform.IPADOS, targets.getValue(ipad.pairId).peerPlatform)
        }

    private fun newPair(
        peerDeviceId: String = UUID.randomUUID().toString(),
        platform: PeerPlatform = PeerPlatform.MACOS,
    ) = NewPair(
        pairId = UUID.randomUUID().toString(),
        peer = PeerKeys(peerDeviceId, ikSigPub = ByteArray(32) { 3 }, ikDhPub = ByteArray(32) { 4 }),
        peerName = "MacBook của Lan",
        peerPlatform = platform,
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
