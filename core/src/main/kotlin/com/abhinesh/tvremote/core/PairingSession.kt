package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.PairingException.Reason
import com.abhinesh.tvremote.core.crypto.PairingSecret
import com.abhinesh.tvremote.core.proto.Framing
import com.abhinesh.tvremote.core.proto.PoloMessages
import com.abhinesh.tvremote.core.proto.PoloMessages.Kind
import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.Tls
import com.abhinesh.tvremote.core.tls.TlsConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLException

/**
 * One pairing attempt on port 6467:
 *  1. [start] connects and negotiates; when it returns the TV is showing a 6-character code.
 *  2. [submitCode] checks the code locally, sends the secret and waits for the TV's ack.
 * A mistyped code ([Reason.CODE_MISTYPED]) leaves the session open for another try; any
 * other failure means the TV ended this session and [start] must be called on a new one.
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String,
    private val port: Int = DEFAULT_PORT,
    private val stepTimeoutMs: Int = 10_000,
    private val log: (String) -> Unit = {},
) : Closeable {
    private var conn: TlsConnection? = null

    val serverCertificate: X509Certificate? get() = conn?.serverCertificate
    var serverName: String? = null
        private set

    suspend fun start() = withContext(Dispatchers.IO) {
        val c = try {
            Tls.connect(host, port, identity)
        } catch (e: CannotConnectException) {
            throw PairingException(Reason.CANNOT_CONNECT, "Couldn't reach the TV's pairing service at $host:$port. Is the TV on and on the same Wi-Fi?", e)
        } catch (e: Exception) {
            throw PairingException(Reason.CANNOT_CONNECT, "Secure connection to the TV's pairing service failed: ${e.message}", e)
        }
        conn = c
        c.socket.soTimeout = stepTimeoutMs
        log("pairing: connected, sending request as \"$clientName\"")
        send(PoloMessages.pairingRequest(clientName))
        serverName = expect(Kind.PAIRING_REQUEST_ACK).serverName
        send(PoloMessages.options())
        expect(Kind.OPTIONS)
        send(PoloMessages.configuration())
        expect(Kind.CONFIGURATION_ACK)
        log("pairing: TV is showing the code")
    }

    /** Local check only (no network): true if [code] is what the TV displays. */
    fun isCodeConsistent(code: String): Boolean {
        val server = serverCertificate?.publicKey as? RSAPublicKey ?: return false
        if (!PairingSecret.isWellFormed(code)) return false
        val secret = PairingSecret.compute(identity.publicKey, server, code.uppercase())
        return PairingSecret.checkByteMatches(secret, code)
    }

    suspend fun submitCode(rawCode: String) = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase()
        if (!PairingSecret.isWellFormed(code)) {
            throw PairingException(Reason.CODE_MISTYPED, "Enter the 6 characters shown on the TV (digits 0-9 and letters A-F).")
        }
        val server = serverCertificate?.publicKey as? RSAPublicKey
            ?: throw PairingException(Reason.CLOSED_BY_TV, "Pairing session is not open. Start pairing again.")
        val secret = PairingSecret.compute(identity.publicKey, server, code)
        if (!PairingSecret.checkByteMatches(secret, code)) {
            throw PairingException(Reason.CODE_MISTYPED, "That code doesn't match what the TV is showing. Check each character and try again.")
        }
        send(PoloMessages.secret(secret))
        expect(Kind.SECRET_ACK)
        log("pairing: TV accepted the code")
    }

    private fun send(bytes: ByteArray) {
        val c = conn ?: throw PairingException(Reason.CLOSED_BY_TV, "Pairing session is closed.")
        try {
            Framing.write(c.output, bytes)
        } catch (e: Exception) {
            throw PairingException(Reason.CLOSED_BY_TV, "The TV closed the pairing session (cancelled on the TV or timed out). Start pairing again.", e)
        }
    }

    private fun expect(kind: Kind): PoloMessages.Inbound {
        val c = conn ?: throw PairingException(Reason.CLOSED_BY_TV, "Pairing session is closed.")
        val raw = try {
            Framing.read(c.input)
        } catch (e: SocketTimeoutException) {
            throw PairingException(Reason.TIMEOUT, "The TV didn't respond within ${stepTimeoutMs / 1000} s.", e)
        } catch (e: EOFException) {
            throw PairingException(Reason.CLOSED_BY_TV, "The TV closed the pairing session (cancelled on the TV, wrong code, or timed out). Start pairing again.", e)
        } catch (e: SocketException) {
            throw PairingException(Reason.CLOSED_BY_TV, "The pairing connection dropped: ${e.message}. Start pairing again.", e)
        } catch (e: SSLException) {
            throw PairingException(Reason.CLOSED_BY_TV, "The pairing connection dropped: ${e.message}. Start pairing again.", e)
        }
        val msg = PoloMessages.parse(raw)
        log("pairing: received ${msg.kind} status=${msg.status}")
        if (msg.status != PoloMessages.STATUS_OK) {
            close()
            val hint = if (msg.status == PoloMessages.STATUS_BAD_SECRET) "The TV rejected the code." else "The TV reported ${PoloMessages.statusText(msg.status)}."
            throw PairingException(Reason.REJECTED_BY_TV, "$hint Start pairing again to get a new code.")
        }
        if (msg.kind != kind) {
            close()
            throw PairingException(Reason.REJECTED_BY_TV, "Unexpected pairing reply from the TV (${msg.kind}, wanted $kind).")
        }
        return msg
    }

    override fun close() {
        conn?.close()
        conn = null
    }

    companion object {
        const val DEFAULT_PORT = 6467
    }
}
