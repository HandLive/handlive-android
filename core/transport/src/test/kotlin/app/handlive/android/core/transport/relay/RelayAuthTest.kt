package app.handlive.android.core.transport.relay

import app.handlive.android.core.crypto.derivation.RelaySignatureMessages
import app.handlive.android.core.crypto.identity.DeviceIdentity
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registration, challenge, token and the JWT endpoints against an HTTP relay (0.6.4, CONN-03 API 1–3, E2, E3). */
class RelayAuthTest {
    private val server = MockWebServer().apply { start() }
    private val vector =
        SharedTestVectors.vectors("relay-auth.json").first {
            it.str("kind") == "register" &&
                it.str("platform") == "android"
        }
    private val identity = DeviceIdentity(vector.hex("ik_sig_seed"), ByteArray(32) { 7 })
    private var now = vector.str("ts").toLong()
    private val config = RelayConfig.unpinned(server.url("/").toString().trimEnd('/'), "ws://unused")
    private val http = OkHttpRelayTransport.http(OkHttpRelayTransport.client(config), config)
    private val auth =
        RelayAuth(
            http,
            RelayIdentity(identity.deviceId, identity.signingPublicKey, "1.0.0 (100)", identity::sign),
        ) { now }
    private val api = RelayApi(http, auth)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun registrationIsTheSharedVectorByteForByte() =
        runBlocking {
            server.enqueue(json(201, """{"device_id":"${identity.deviceId}","created_at":$now}"""))
            auth.register()
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/devices", request.path)
            assertEquals(vector.str("request"), request.body.readUtf8())
            assertTrue(auth.registered)
        }

    @Test
    fun aTokenIsSignedWithHlauth1AndReusedUntilAMinuteBeforeItExpires() =
        runBlocking {
            enqueueRegistrationAndToken("jwt-1")
            server.enqueue(json(202, """{"accepted":true}"""))
            server.enqueue(json(202, """{"accepted":true}"""))
            api.push(PUSH)
            now += 839_000
            api.push(PUSH)

            server.takeRequest()
            server.takeRequest()
            val token = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val challenge = Base64Codecs.decodeB64u(token.getValue("challenge").jsonPrimitive.content)
            val signature = Base64Codecs.decodeB64u(token.getValue("sig").jsonPrimitive.content)
            assertTrue(
                Ed25519Keys.verify(
                    identity.signingPublicKey,
                    RelaySignatureMessages.token(challenge, identity.deviceId),
                    signature,
                ),
            )
            assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer jwt-1", server.takeRequest().getHeader("Authorization"))
            assertEquals(5, server.requestCount)
        }

    @Test
    fun anExpiredTokenIsRenewedAndTheCallRetriedOnce() =
        runBlocking {
            enqueueRegistrationAndToken("jwt-1")
            server.enqueue(json(401, """{"error":{"code":"TOKEN_EXPIRED","message":"expired"}}"""))
            enqueueChallengeAndToken("jwt-2")
            server.enqueue(json(202, """{"accepted":true}"""))
            val response = api.push(PUSH)
            assertEquals(202, response.status)
            val paths = List(server.requestCount) { server.takeRequest() }
            assertEquals("Bearer jwt-2", paths.last().getHeader("Authorization"))
            assertEquals(
                listOf(
                    "/v1/devices",
                    "/v1/auth/challenge",
                    "/v1/auth/token",
                    "/v1/push",
                    "/v1/auth/challenge",
                    "/v1/auth/token",
                    "/v1/push",
                ),
                paths.map { it.path },
            )
        }

    @Test
    fun anUnknownDeviceRegistersAgainThenRetriesOnce() =
        runBlocking {
            enqueueRegistrationAndToken("jwt-1")
            server.enqueue(json(202, """{"accepted":true}"""))
            api.push(PUSH)
            auth.forget()
            server.enqueue(json(404, """{"error":{"code":"DEVICE_NOT_FOUND","message":"gone"}}"""))
            server.enqueue(json(200, """{"device_id":"${identity.deviceId}","created_at":$now}"""))
            enqueueChallengeAndToken("jwt-2")
            server.enqueue(json(202, """{"accepted":true}"""))
            assertEquals(202, api.push(PUSH).status)
            val paths = List(server.requestCount) { server.takeRequest().path }.drop(4)
            assertEquals(
                listOf("/v1/auth/challenge", "/v1/devices", "/v1/auth/challenge", "/v1/auth/token", "/v1/push"),
                paths,
            )
        }

    @Test
    fun aRevokedDeviceStopsWithItsCode() =
        runBlocking {
            server.enqueue(json(410, """{"error":{"code":"DEVICE_REVOKED","message":"revoked"}}"""))
            val error = runCatching { api.push(PUSH) }.exceptionOrNull() as RelayRequestException
            assertEquals(410, error.status)
            assertEquals("DEVICE_REVOKED", error.code)
        }

    @Test
    fun removingTheDeviceDoesNotRegisterItAgain() =
        runBlocking {
            server.enqueue(json(404, """{"error":{"code":"DEVICE_NOT_FOUND","message":"gone"}}"""))
            val error =
                runCatching {
                    api.deleteThisDevice(
                        revokePairs = false,
                    )
                }.exceptionOrNull() as RelayRequestException
            assertEquals("DEVICE_NOT_FOUND", error.code)
            assertEquals(1, server.requestCount)
            assertEquals("/v1/auth/challenge", server.takeRequest().path)
        }

    @Test
    fun noConnectionIsUnreachable() =
        runBlocking {
            server.shutdown()
            val error = runCatching { auth.register() }.exceptionOrNull()
            assertTrue(error is RelayUnreachableException)
        }

    private fun enqueueRegistrationAndToken(token: String) {
        server.enqueue(json(201, """{"device_id":"${identity.deviceId}","created_at":$now}"""))
        enqueueChallengeAndToken(token)
    }

    private fun enqueueChallengeAndToken(token: String) {
        val challenge = Base64Codecs.encodeB64u(ByteArray(32) { it.toByte() })
        server.enqueue(json(200, """{"challenge":"$challenge","expires_at":${now + 60_000}}"""))
        server.enqueue(json(200, """{"access_token":"$token","expires_in":900}"""))
    }

    private fun json(
        status: Int,
        body: String,
    ) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        val PUSH =
            PushRequest(
                pairId = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f2a3b4c5d",
                to = "dac073e0-123b-8ea5-9dd9-b3bda9cf6037",
                kind = "alert",
                reason = "sms_new",
                envB64 = "e30=",
                collapseKey = "sms:sms:1",
                ttlS = 86_400,
            )
    }
}
