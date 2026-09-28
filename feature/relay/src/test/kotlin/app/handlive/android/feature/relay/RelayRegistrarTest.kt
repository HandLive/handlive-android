package app.handlive.android.feature.relay

import app.handlive.android.core.crypto.derivation.RevocationStatement
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.DevicesDeleteRequest
import app.handlive.android.core.protocol.relay.PairRegistrationRequest
import app.handlive.android.core.protocol.relay.PairRevokeRequest
import app.handlive.android.core.protocol.relay.RelayPairRevoked
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.relay.testing.PHONE_DEVICE_ID
import app.handlive.android.feature.relay.testing.PHONE_SEED
import app.handlive.android.feature.relay.testing.PHONE_SIGNING_KEY
import app.handlive.android.feature.relay.testing.RelayFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** What the relay learns about the phone (CONN-03 step 3, PAIR-01 API 8, PAIR-02 API 1, PAIR-03 E3, CONN-04 API 1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayRegistrarTest {
    private val fixture = RelayFixture()
    private val revokedElsewhere = mutableListOf<String>()
    private val registrar =
        RelayRegistrar(fixture.auth, fixture.api, fixture.pairs, fixture.relayPairs, fixture.clock) {
            revokedElsewhere += it
        }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun theDeviceThenEveryUnregisteredPairIsRegistered() =
        runTest {
            val pairId = fixture.addPair(PeerPlatform.MACOS, registered = false, peerDeviceId = PEER)
            fixture.http.enqueue("POST", "/v1/pairs", 201, """{"pair_id":"$pairId"}""")

            registrar.registerAll()

            assertEquals(
                "/v1/devices",
                fixture.http.calls
                    .first()
                    .path,
            )
            val body =
                fixture.http
                    .calls("POST", "/v1/pairs")
                    .single()
                    .body!!
            JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/pairs-request", body)
            val request = ProtocolJson.decodeFromString(PairRegistrationRequest.serializer(), body)
            assertEquals(pairId, request.pairId)
            assertEquals(PHONE_DEVICE_ID, request.deviceA)
            assertEquals(PEER, request.deviceB)
            assertTrue(fixture.pairs.find(pairId)!!.relayRegistered)

            // Registered once per process; a registered pair is not sent again.
            registrar.registerAll()
            assertEquals(1, fixture.http.calls("POST", "/v1/devices").size)
            assertEquals(1, fixture.http.calls("POST", "/v1/pairs").size)
        }

    @Test
    fun aPairThePeerHasNotRegisteredYetIsRetriedADayLater() =
        runTest {
            val pairId = fixture.addPair(registered = false)
            // 404 DEVICE_NOT_FOUND also names an unknown caller (CONN-03 E2): the phone registers again, retries once.
            repeat(2) { fixture.http.enqueue("POST", "/v1/pairs", 404, fixture.http.error("DEVICE_NOT_FOUND")) }
            registrar.registerAll()
            registrar.registerAll()
            assertEquals(2, fixture.http.calls("POST", "/v1/pairs").size)
            assertEquals(2, fixture.http.calls("POST", "/v1/devices").size)
            assertFalse(fixture.pairs.find(pairId)!!.relayRegistered)

            fixture.now += 24 * 60 * 60 * 1000L
            fixture.http.enqueue("POST", "/v1/pairs", 200, """{"pair_id":"$pairId"}""")
            registrar.registerAll()
            assertEquals(3, fixture.http.calls("POST", "/v1/pairs").size)
            assertTrue(fixture.pairs.find(pairId)!!.relayRegistered)
        }

    @Test
    fun aPairRefusedFor401IsTriedAgainAtTheNextOccasion() =
        runTest {
            val pairId = fixture.addPair(registered = false)
            // Refused with the renewed token as well: nothing about the pair itself, so no 24-hour wait.
            repeat(2) { fixture.http.enqueue("POST", "/v1/pairs", 401, fixture.http.error("SIGNATURE_INVALID")) }
            registrar.registerAll()
            assertEquals(2, fixture.http.calls("POST", "/v1/pairs").size)
            assertFalse(fixture.pairs.find(pairId)!!.relayRegistered)

            fixture.http.enqueue("POST", "/v1/pairs", 201, """{"pair_id":"$pairId"}""")
            registrar.registerAll()
            assertEquals(3, fixture.http.calls("POST", "/v1/pairs").size)
            assertTrue(fixture.pairs.find(pairId)!!.relayRegistered)
        }

    @Test
    fun tombstonesAreRevokedOnTheRelayUntilItConfirms() =
        runTest {
            val pairId = fixture.addPair()
            fixture.pairs.revoke(pairId)
            fixture.http.enqueue("POST", "/v1/pairs/$pairId/revoke", 503, fixture.http.error("INTERNAL"))

            registrar.revokeTombstones()
            assertEquals(listOf(pairId), fixture.relayPairs.tombstonesToRevoke())
            val body =
                fixture.http
                    .calls("POST", "/v1/pairs/$pairId/revoke")
                    .single()
                    .body!!
            JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/pair-revoke-request", body)

            // 404 NOT_PAIRED: the relay never knew it, which also means done.
            fixture.http.enqueue("POST", "/v1/pairs/$pairId/revoke", 404, fixture.http.error("NOT_PAIRED"))
            registrar.revokeTombstones()
            assertTrue(fixture.relayPairs.tombstonesToRevoke().isEmpty())
        }

    @Test
    fun anUnknownPairIsRevokedWithoutRegisteringTheDeviceAgain() =
        runTest {
            val pairId = fixture.addPair()
            fixture.pairs.revoke(pairId)
            // PAIR-03 API 3: an unknown pair_id answers 404 DEVICE_NOT_FOUND, which means revoked.
            fixture.http.enqueue("POST", "/v1/pairs/$pairId/revoke", 404, fixture.http.error("DEVICE_NOT_FOUND"))
            registrar.revokeTombstones()
            assertTrue(fixture.relayPairs.tombstonesToRevoke().isEmpty())
            assertEquals(1, fixture.http.calls("POST", "/v1/pairs/$pairId/revoke").size)

            // A relay that no longer knows this device holds none of its pairs either.
            val other = fixture.addPair()
            fixture.pairs.revoke(other)
            fixture.auth.forget()
            fixture.http.enqueue("POST", "/v1/auth/challenge", 404, fixture.http.error("DEVICE_NOT_FOUND"))
            registrar.revokeTombstones()
            assertTrue(fixture.relayPairs.tombstonesToRevoke().isEmpty())
            assertTrue(fixture.http.calls("POST", "/v1/pairs/$other/revoke").isEmpty())
            assertTrue("never registered for a revocation", fixture.http.calls("POST", "/v1/devices").isEmpty())
        }

    @Test
    fun theListOfPairsCleansUpRevokedPairsAndForgottenRegistrations() =
        runTest {
            val revoked = fixture.addPair(peerDeviceId = PEER, peerSigningKey = PEER_KEY)
            val forgotten = fixture.addPair()
            val kept = fixture.addPair()
            val entries =
                listOf(
                    entry(revoked, revokedAt = fixture.now - 1_000, signedBy = PEER_SEED),
                    entry(kept, revokedAt = null),
                ).joinToString(",")
            fixture.http.enqueue("GET", "/v1/pairs", 200, """{"pairs":[$entries]}""")

            registrar.checkPairs()

            assertEquals(listOf(revoked), revokedElsewhere)
            assertFalse(fixture.pairs.find(forgotten)!!.relayRegistered)
            assertTrue(fixture.pairs.find(kept)!!.relayRegistered)
            // At most once a minute, unless the relay just said NOT_PAIRED.
            registrar.checkPairs()
            assertEquals(1, fixture.http.calls("GET", "/v1/pairs").size)
            fixture.http.enqueue("GET", "/v1/pairs", 200, """{"pairs":[]}""")
            registrar.checkPairs(force = true)
            assertEquals(2, fixture.http.calls("GET", "/v1/pairs").size)
        }

    @Test
    fun aRevokedRowCountsOnlyWithThePeersSignedStatement() =
        runTest {
            // Four clients (one pair per client): each row below is revoked without a valid statement of its peer.
            val legacy = fixture.addPair(peerSigningKey = PEER_KEY)
            val forged = fixture.addPair(peerSigningKey = PEER_KEY)
            val byOther = fixture.addPair(peerSigningKey = PEER_KEY)
            val byPhone = fixture.addPair(peerSigningKey = PEER_KEY)
            val entries =
                listOf(
                    // Revoked before signed revocation: no statement.
                    entry(legacy, revokedAt = fixture.now),
                    // Signed with a key that is not the stored peer key.
                    entry(forged, revokedAt = fixture.now, signedBy = OTHER_SEED),
                    // A third device signs for itself.
                    entry(byOther, revokedAt = fixture.now, signedBy = OTHER_SEED, by = OTHER),
                    // The relay replays the phone's own statement as if the peer had revoked.
                    entry(byPhone, revokedAt = fixture.now, signedBy = PHONE_SEED, by = PHONE_DEVICE_ID),
                ).joinToString(",")
            fixture.http.enqueue("GET", "/v1/pairs", 200, """{"pairs":[$entries]}""")
            registrar.checkPairs()
            assertTrue(revokedElsewhere.isEmpty())
            assertEquals(4, fixture.pairs.activeCount())
        }

    @Test
    fun aRelayPairRevokedFrameIsActedOnOnlyWhenThePeerSignedIt() =
        runTest {
            val pairId = fixture.addPair(peerDeviceId = PEER, peerSigningKey = PEER_KEY)
            val at = fixture.now
            val good =
                Base64Codecs.encodeB64u(
                    Ed25519Keys.sign(PEER_SEED, RevocationStatement.message(pairId, PEER, at)),
                )
            val bad =
                Base64Codecs.encodeB64u(
                    Ed25519Keys.sign(OTHER_SEED, RevocationStatement.message(pairId, PEER, at)),
                )
            assertTrue(
                registrar.isSignedByPeer(RelayPairRevoked(pairId = pairId, by = PEER, revokedAt = at, sig = good)),
            )
            assertFalse(
                registrar.isSignedByPeer(RelayPairRevoked(pairId = pairId, by = PEER, revokedAt = at, sig = bad)),
            )
            assertFalse(registrar.isSignedByPeer(RelayPairRevoked(pairId = pairId, by = PEER)))
            assertFalse(
                registrar.isSignedByPeer(RelayPairRevoked(pairId = pairId, by = OTHER, revokedAt = at, sig = good)),
            )
            assertFalse(
                registrar.isSignedByPeer(RelayPairRevoked(pairId = UUID.randomUUID().toString(), by = PEER)),
            )
        }

    @Test
    fun aRevocationCarriesAFreshStatementSignedByThePhone() =
        runTest {
            val pairId = fixture.addPair()
            fixture.pairs.revoke(pairId)
            fixture.http.enqueue("POST", "/v1/pairs/$pairId/revoke", 503, fixture.http.error("INTERNAL"))
            registrar.revokeTombstones()
            // A retry after E3 signs again with the current time.
            fixture.now += 60 * 60 * 1000L
            fixture.http.enqueue("POST", "/v1/pairs/$pairId/revoke", 204)
            registrar.revokeTombstones()
            val bodies =
                fixture.http.calls("POST", "/v1/pairs/$pairId/revoke").map {
                    JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/pair-revoke-request", it.body!!)
                    ProtocolJson.decodeFromString(PairRevokeRequest.serializer(), it.body!!)
                }
            assertEquals(listOf(fixture.now - 60 * 60 * 1000L, fixture.now), bodies.map { it.revokedAt })
            bodies.forEach { assertSignedByThePhone(pairId, it.revokedAt, it.sig) }
        }

    @Test
    fun deletingEverythingSignsEveryLocalPairAndEveryPairTheRelayStillHolds() =
        runTest {
            val active = fixture.addPair()
            val tombstone = fixture.addPair()
            fixture.pairs.revoke(tombstone)
            val remoteOnly = UUID.randomUUID().toString()
            val listed =
                """{"pair_id":"$remoteOnly","peer_device_id":"$PEER","peer_platform":"ios",""" +
                    """"created_at":${fixture.now},"revoked_at":null,"peer_online":false}"""
            fixture.http.enqueue("GET", "/v1/pairs", 200, """{"pairs":[${entry(active, null)},$listed]}""")
            fixture.http.enqueue("DELETE", "/v1/devices/me?revoke_pairs=true", 204)

            assertEquals(ServerDeletion.DONE, registrar.deleteDevice(revokePairs = true))

            val body =
                fixture.http
                    .calls("DELETE", "/v1/devices/me?revoke_pairs=true")
                    .single()
                    .body!!
            JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/devices-delete-request", body)
            val revocations = ProtocolJson.decodeFromString(DevicesDeleteRequest.serializer(), body).revocations
            assertEquals(setOf(active, tombstone, remoteOnly), revocations.map { it.pairId }.toSet())
            assertEquals(3, revocations.size)
            revocations.forEach { assertSignedByThePhone(it.pairId, it.revokedAt, it.sig) }
        }

    @Test
    fun aPairThePeerCompletedIsMarkedRegisteredAndStopsWaiting() =
        runTest {
            val pairId = fixture.addPair(registered = false)
            // PAIR-01 API 8 logic 6: 404 twice (the peer is not registered yet) → the next call waits 24 h.
            repeat(2) { fixture.http.enqueue("POST", "/v1/pairs", 404, fixture.http.error("DEVICE_NOT_FOUND")) }
            registrar.registerAll()
            assertFalse(fixture.pairs.find(pairId)!!.relayRegistered)

            // The peer registered the pair meanwhile: the relay lists it without revoked_at (PAIR-02 API 1 logic 3).
            fixture.http.enqueue("GET", "/v1/pairs", 200, """{"pairs":[${entry(pairId, revokedAt = null)}]}""")
            registrar.checkPairs()

            assertTrue(fixture.pairs.find(pairId)!!.relayRegistered)
            registrar.registerAll()
            assertEquals(2, fixture.http.calls("POST", "/v1/pairs").size)
            // Were it forgotten again, the wait is over: the next registration goes out at once.
            fixture.relayPairs.markRegistered(pairId, registered = false)
            fixture.http.enqueue("POST", "/v1/pairs", 200, """{"pair_id":"$pairId"}""")
            registrar.registerAll()
            assertEquals(3, fixture.http.calls("POST", "/v1/pairs").size)
        }

    @Test
    fun thePushTokenGoesWhenNewAndOnceAWeek() =
        runTest {
            repeat(3) { fixture.http.enqueue("PUT", "/v1/devices/me/push-token", 204) }

            registrar.registerPushToken("token-1")
            registrar.registerPushToken("token-1")
            assertEquals(1, fixture.http.calls("PUT", "/v1/devices/me/push-token").size)
            JsonSchemaValidation.assertValid(
                "relay-rest.schema.json#/\$defs/push-token-request",
                fixture.http
                    .calls("PUT", "/v1/devices/me/push-token")
                    .single()
                    .body!!,
            )

            registrar.registerPushToken("token-2")
            fixture.now += 7 * 24 * 60 * 60 * 1000L
            registrar.registerPushToken("token-2")
            assertEquals(3, fixture.http.calls("PUT", "/v1/devices/me/push-token").size)
        }

    @Test
    fun deletingTheDeviceMakesTheNextUseRegisterAgain() =
        runTest {
            registrar.registerAll()
            fixture.http.enqueue("DELETE", "/v1/devices/me?revoke_pairs=false", 204)

            assertEquals(ServerDeletion.DONE, registrar.deleteDevice(revokePairs = false))
            val call = fixture.http.calls("DELETE", "/v1/devices/me?revoke_pairs=false").single()
            assertTrue(call.bearer!!.startsWith("jwt-"))

            registrar.registerAll()
            assertEquals(2, fixture.http.calls("POST", "/v1/devices").size)
        }

    @Test
    fun aDeviceTheRelayNoLongerKnowsCountsAsDeletedAndErrorsChangeNothing() =
        runTest {
            // E6: the challenge answers 404 DEVICE_NOT_FOUND; the phone must not register just to delete itself.
            fixture.http.enqueue("POST", "/v1/auth/challenge", 404, fixture.http.error("DEVICE_NOT_FOUND"))
            assertEquals(ServerDeletion.DONE, registrar.deleteDevice(revokePairs = true))
            assertTrue(fixture.http.calls("POST", "/v1/devices").isEmpty())

            fixture.http.enqueue("DELETE", "/v1/devices/me?revoke_pairs=true", 503, fixture.http.error("INTERNAL"))
            assertEquals(ServerDeletion.UNREACHABLE, registrar.deleteDevice(revokePairs = true))
            fixture.http.unreachable = true
            assertEquals(ServerDeletion.UNREACHABLE, registrar.deleteDevice(revokePairs = true))
        }

    /** A `GET /v1/pairs` row; a revoked one carries the statement [signedBy] signs as [by] (none without a seed). */
    private suspend fun entry(
        pairId: String,
        revokedAt: Long?,
        signedBy: ByteArray? = null,
        by: String? = null,
    ): String {
        val pair =
            fixture.pairs
                .observeActive()
                .first()
                .single { it.pairId == pairId }
        val signer = by ?: pair.peerDeviceId
        val statement =
            if (revokedAt != null && signedBy != null) {
                val sig = Ed25519Keys.sign(signedBy, RevocationStatement.message(pairId, signer, revokedAt))
                ""","revoked_by":"$signer","revoke_sig":"${Base64Codecs.encodeB64u(sig)}""""
            } else {
                ""
            }
        val revoked = revokedAt?.let { ""","revoked_at":$it""" }.orEmpty()
        return """{"pair_id":"$pairId","peer_device_id":"${pair.peerDeviceId}","peer_platform":"ios",""" +
            """"created_at":${fixture.now}$revoked$statement,"peer_online":false}"""
    }

    private fun assertSignedByThePhone(
        pairId: String,
        revokedAt: Long,
        sig: String,
    ) = assertTrue(
        RevocationStatement.isFromPeer(
            RevocationStatement.Received(pairId, PHONE_DEVICE_ID, revokedAt, sig),
            PHONE_DEVICE_ID,
            PHONE_SIGNING_KEY,
        ),
    )

    private companion object {
        /** RFC 8032 TEST 3, the iOS device of `revoke.json`. */
        const val PEER = "dac073e0-123b-8ea5-9dd9-b3bda9cf6037"
        const val OTHER = "39f713d0-a644-853f-8452-9421b9f51b9b"
        val PEER_SEED: ByteArray = ByteArray(32) { 7 }
        val PEER_KEY: ByteArray = Ed25519Keys.publicFromSeed(PEER_SEED)
        val OTHER_SEED: ByteArray = ByteArray(32) { 8 }
    }
}
