package app.handlive.android.core.transport.relay

import app.handlive.android.core.transport.tls.SelfSignedCertificateGenerator
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A relay certificate for TLS tests: self-signed P-256, served through MockWebServer, trusted by a client only
 * through [trusting] — the platform trust store of the JVM never has it, like a relay behind a foreign CA.
 */
class TestRelayTls {
    private val identity = SelfSignedCertificateGenerator.generate()
    private val certificate = identity.second

    /** `sha256/…` of the certificate's public key, as `handlive.relayExtraPins` carries a pin. */
    val pin: String = CertificatePinner.pin(certificate)

    val serverSockets: SSLSocketFactory =
        SSLContext
            .getInstance(TLS)
            .apply {
                val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                keys.setKeyEntry(ALIAS, identity.first.private, PASSWORD, arrayOf(certificate))
                val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                managers.init(keys, PASSWORD)
                init(managers.keyManagers, null, null)
            }.socketFactory

    /** [client] trusting this certificate alone; it names no host, so the host name is not checked either. */
    fun trusting(client: OkHttpClient): OkHttpClient {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anchors.setCertificateEntry(ALIAS, certificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val trust = factory.trustManagers.single() as X509TrustManager
        val context = SSLContext.getInstance(TLS).apply { init(null, arrayOf(trust), null) }
        return client
            .newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private companion object {
        const val TLS = "TLS"
        const val ALIAS = "relay"
        val PASSWORD = "test".toCharArray()
    }
}
