package app.handlive.android.core.transport.relay

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The OkHttp client of the relay (0.4.3): TLS through the platform (Let's Encrypt chain), the SPKI pins of
 * [RelayConfig.pins] checked on every connection, WebSocket pings every 15 s (`WS_PING_INTERVAL`, 0.10).
 */
object OkHttpRelayTransport {
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val PING_INTERVAL_SECONDS = 15L

    fun client(config: RelayConfig): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (config.pins.isNotEmpty()) {
                    // A pin pattern is a host name, while RELAY_HOST may carry a port ("relay.example.com:8443").
                    val host = config.baseUrl.toHttpUrl().host
                    val pinner = CertificatePinner.Builder()
                    config.pins.forEach { pinner.add(host, it) }
                    certificatePinner(pinner.build())
                }
            }.build()

    /** [RelayHttp] on [client]; JSON bodies, UTF-8. */
    fun http(
        client: OkHttpClient,
        config: RelayConfig,
    ): RelayHttp =
        RelayHttp { method, path, body, bearer ->
            val request =
                Request
                    .Builder()
                    .url(config.baseUrl + path)
                    .method(
                        method,
                        body?.toRequestBody(JSON) ?: if (method == "GET") null else EMPTY.toRequestBody(JSON),
                    ).apply { bearer?.let { header("Authorization", "Bearer $it") } }
                    .build()
            client.newCall(request).await()
        }

    private suspend fun Call.await(): RelayResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            response.use {
                                RelayResponse(it.code, it.body?.string().orEmpty(), retryAfterMillis(it))
                            }
                        continuation.resume(result)
                    }

                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        val failure =
                            if (e.refusesCertificate()) {
                                RelayPinMismatchException(e)
                            } else {
                                RelayUnreachableException("relay unreachable", e)
                            }
                        continuation.resumeWithException(failure)
                    }
                },
            )
        }

    private fun retryAfterMillis(response: Response): Long? =
        response
            .header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.let { TimeUnit.SECONDS.toMillis(it) }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val EMPTY = ""
}

/**
 * CONN-03 E7: TLS refused the relay's certificate — its chain matches none of the pins or names another host, or the
 * platform does not trust the chain (a handshake that failed on the certificate path) — rather than a network or
 * protocol failure (E1).
 */
internal fun Throwable.refusesCertificate(): Boolean =
    this is SSLPeerUnverifiedException ||
        (
            this is SSLHandshakeException &&
                generateSequence(cause) { it.cause }.take(MAX_CAUSES).any { cause ->
                    cause is CertificateException || cause is CertPathValidatorException ||
                        cause is CertPathBuilderException
                }
        )

/** How deep [refusesCertificate] looks through the causes of a handshake failure. */
private const val MAX_CAUSES = 8

/** The relay REST client and the `/v1/relay` socket factory on one pinned client (CONN-03, 0.4.3). */
class RelayTransport(
    val http: RelayHttp,
    val links: RelayLinkFactory,
) {
    companion object {
        /**
         * The pinned transport of [config]. Relay settings the client cannot take — a malformed pin or host among the
         * build settings — give a transport on which every call fails as unreachable (CONN-03 E1): the relay does not
         * work, and nothing that touches it crashes.
         */
        fun create(config: RelayConfig): RelayTransport =
            try {
                val client = OkHttpRelayTransport.client(config)
                // Both URLs are parsed now, so a malformed host fails here rather than at the first call or link.
                Request.Builder().url(config.baseUrl).url(config.webSocketUrl)
                RelayTransport(OkHttpRelayTransport.http(client, config), OkHttpRelayLinkFactory(client, config))
            } catch (e: IllegalArgumentException) {
                unusable(e)
            }

        private fun unusable(cause: IllegalArgumentException): RelayTransport {
            fun failure() = RelayUnreachableException("relay settings unusable", cause)
            return RelayTransport({ _, _, _, _ -> throw failure() }, { throw failure() })
        }
    }
}
