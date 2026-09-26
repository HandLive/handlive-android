package app.handlive.android.feature.relay

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.relay.PairRegistrationRequest
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.relay.testing.PHONE_DEVICE_ID
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
    fun theListOfPairsCleansUpRevokedPairsAndForgottenRegistrations() =
        runTest {
            val revoked = fixture.addPair()
            val forgotten = fixture.addPair()
            val kept = fixture.addPair()
            val entries =
                listOf(
                    entry(revoked, revokedAt = fixture.now - 1_000),
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

    private suspend fun entry(
        pairId: String,
        revokedAt: Long?,
    ): String {
        val pair =
            fixture.pairs
                .observeActive()
                .first()
                .single { it.pairId == pairId }
        val revoked = revokedAt?.let { ""","revoked_at":$it""" }.orEmpty()
        return """{"pair_id":"$pairId","peer_device_id":"${pair.peerDeviceId}","peer_platform":"ios",""" +
            """"created_at":${fixture.now}$revoked,"peer_online":false}"""
    }

    private companion object {
        const val PEER = "dac073e0-123b-8ea5-9dd9-b3bda9cf6037"
    }
}
