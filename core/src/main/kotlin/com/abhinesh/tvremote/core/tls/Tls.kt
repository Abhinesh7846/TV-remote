package com.abhinesh.tvremote.core.tls

import com.abhinesh.tvremote.core.CannotConnectException
import com.abhinesh.tvremote.core.PairingRequiredException
import com.abhinesh.tvremote.core.TvIdentityChangedException
import com.abhinesh.tvremote.core.crypto.SelfSignedCertificate
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** The phone's long-lived identity. The TV remembers this certificate after pairing. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    companion object {
        fun generate(commonName: String): ClientIdentity {
            val kp = SelfSignedCertificate.generateKeyPair()
            return ClientIdentity(kp.private, SelfSignedCertificate.create(commonName, kp))
        }
    }
}

/** SHA-256 of the certificate's SubjectPublicKeyInfo; used to recognise the same TV later. */
fun X509Certificate.publicKeySha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)

/**
 * TV certificates are self-signed, so normal CA validation cannot apply. Instead this
 * records the TV's certificate and, once we know it, pins its public key (trust on first use).
 */
class TvTrustManager(private val pinnedKeySha256: ByteArray?) : X509ExtendedTrustManager() {
    @Volatile var serverCertificate: X509Certificate? = null
        private set
    @Volatile var pinMismatch: Boolean = false
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
        serverCertificate = leaf
        if (pinnedKeySha256 != null && !MessageDigest.isEqual(pinnedKeySha256, leaf.publicKeySha256())) {
            pinMismatch = true
            throw CertificateException("TV certificate changed since pairing")
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client mode only")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client mode only")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client mode only")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Always offers the single client certificate; the TV accepts self-signed clients. */
class SingleKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    private val alias = "tvremote-client"
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
}

class TlsConnection(val socket: SSLSocket, val serverCertificate: X509Certificate) : Closeable {
    val input: InputStream = socket.inputStream
    val output: OutputStream = socket.outputStream
    /**
     * Closing TLS writes a close_notify alert, which Android forbids on the main thread, so
     * the close runs on a short-lived background thread. Pending reads fail right after.
     */
    override fun close() {
        Thread({ runCatching { socket.close() } }, "tls-close").apply { isDaemon = true }.start()
    }
}

object Tls {
    const val CONNECT_TIMEOUT_MS = 5_000

    /**
     * Opens TCP + TLS with client authentication. Classifies failures:
     * TCP problems -> [CannotConnectException]; TLS rejection of our certificate ->
     * [PairingRequiredException]; pinned TV key mismatch -> [TvIdentityChangedException].
     */
    fun connect(
        host: String,
        port: Int,
        identity: ClientIdentity,
        pinnedKeySha256: ByteArray? = null,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): TlsConnection {
        val trust = TvTrustManager(pinnedKeySha256)
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(SingleKeyManager(identity)), arrayOf(trust), null) }
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw CannotConnectException("Couldn't reach $host:$port (${e.javaClass.simpleName}: ${e.message})", e)
        }
        val ssl = try {
            (ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket).also {
                it.soTimeout = 10_000
                it.useClientMode = true
                it.startHandshake()
            }
        } catch (e: SocketTimeoutException) {
            runCatching { plain.close() }
            throw CannotConnectException("TV didn't finish the secure handshake on port $port", e)
        } catch (e: SSLException) {
            runCatching { plain.close() }
            if (trust.pinMismatch) throw TvIdentityChangedException(e)
            throw PairingRequiredException("TV rejected this phone's certificate on port $port", e)
        } catch (e: java.net.SocketException) {
            runCatching { plain.close() }
            throw PairingRequiredException("The TV cut off the secure handshake on port $port", e, definite = false)
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw CannotConnectException("Connection to $host:$port failed (${e.javaClass.simpleName}: ${e.message})", e)
        }
        val cert = trust.serverCertificate ?: (ssl.session.peerCertificates.first() as X509Certificate)
        return TlsConnection(ssl, cert)
    }
}
