package app.handlive.android.core.transport.relay

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.relay.RelayErrorResponse
import java.io.IOException

/** One response of the relay REST API (0.4.3): the HTTP status, the body, and `Retry-After` of a 429. */
class RelayResponse(
    val status: Int,
    val body: String,
    val retryAfterMillis: Long? = null,
) {
    val ok: Boolean get() = status in HTTP_OK until HTTP_MULTIPLE_CHOICES

    /** `error.code` of an error body (0.8.2), `null` when the body is not one. */
    val errorCode: String?
        get() =
            runCatching {
                ProtocolJson
                    .decodeFromString(
                        RelayErrorResponse.serializer(),
                        body,
                    ).error.code
            }.getOrNull()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_MULTIPLE_CHOICES = 300
    }
}

/** The relay REST API as the client uses it; `bearer` is the device JWT of 0.6.4 when the endpoint needs one. */
fun interface RelayHttp {
    /** Throws [RelayUnreachableException] when no response arrived (network, TLS, pin mismatch). */
    suspend fun send(
        method: String,
        path: String,
        body: String?,
        bearer: String?,
    ): RelayResponse
}

/**
 * This client, telling [onRefused] of every call that met a refused certificate (CONN-03 E7) before that call fails:
 * a REST call then reports E7 as the `/v1/relay` link does.
 */
fun RelayHttp.reportingRefusedCertificates(onRefused: () -> Unit): RelayHttp =
    RelayHttp { method, path, body, bearer ->
        try {
            send(method, path, body, bearer)
        } catch (e: RelayPinMismatchException) {
            onRefused()
            throw e
        }
    }

/** No HTTP response: no network, a timeout, a TLS failure (CONN-03 E1). */
open class RelayUnreachableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The relay's certificate was refused (CONN-03 E7): its chain matches none of the pins, or the platform does not trust
 * it. Never connect; report a security error.
 */
class RelayPinMismatchException(
    cause: Throwable? = null,
) : RelayUnreachableException("relay certificate pin mismatch", cause)

/** A relay error the caller cannot recover from by itself: the HTTP status and the code of 0.8.2. */
class RelayRequestException(
    val status: Int,
    val code: String?,
    val retryAfterMillis: Long? = null,
) : IOException("relay answered $status ${code.orEmpty()}")
