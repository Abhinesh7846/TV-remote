package com.abhinesh.tvremote.core

import com.abhinesh.tvremote.core.crypto.PairingSecret
import com.abhinesh.tvremote.core.proto.Framing
import com.abhinesh.tvremote.core.proto.ProtoReader
import com.abhinesh.tvremote.core.proto.ProtoWriter
import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.publicKeySha256
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/**
 * In-process stand-in for an Android TV Remote Service, speaking the same wire protocol on
 * two local TLS ports. Only paired client certificates are accepted on the remote port.
 */
class FakeTv(
    @Volatile var features: Int = 623,
    @Volatile var pingIntervalMs: Long = 1_000,
) : Closeable {
    sealed interface LinkOutcome {
        data class Open(val pkg: String) : LinkOutcome
        data object Reject : LinkOutcome
        data object Ignore : LinkOutcome
    }

    val identity = ClientIdentity.generate("atvremote/fake/fake/FakeTV/AA:BB:CC:DD:EE:FF")
    private val paired = Collections.synchronizedSet(HashSet<String>())
    private val random = SecureRandom()

    @Volatile var currentCode: String? = null
    @Volatile var silent = false
    @Volatile var answerVoice = true
    @Volatile var currentApp = HOME_APP
    @Volatile var isOn = true
    @Volatile var volume = 17
    @Volatile var muted = false
    /** Like the MiTV-AXFR2: standby turns Wi-Fi off, so the remote port disappears. */
    @Volatile var networkOffInStandby = false
    @Volatile var ignoreSleepKey = false
    @Volatile var reportsVolume = true
    @Volatile var linkOutcome: (String) -> LinkOutcome = { LinkOutcome.Open("com.example.$it") }

    val keys = CopyOnWriteArrayList<Pair<Int, Int>>()
    val links = CopyOnWriteArrayList<String>()
    val texts = CopyOnWriteArrayList<String>()
    val voicePayloadSizes = CopyOnWriteArrayList<Int>()
    val voiceEnds = CopyOnWriteArrayList<Int>()
    val pingResponses = CopyOnWriteArrayList<Int>()
    @Volatile var remoteConnections = 0

    private val openSockets = CopyOnWriteArrayList<Socket>()
    private var pairingServer: SSLServerSocket? = null
    private var remoteServer: SSLServerSocket? = null
    var pairingPort = 0; private set
    var remotePort = 0; private set

    init {
        startPairing()
        startRemote()
    }

    fun isPaired(client: ClientIdentity) = paired.contains(hex(client.certificate.publicKeySha256()))

    // ------------------------------------------------------------------ servers

    private fun context(trust: X509ExtendedTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(ServerKeyManager(identity)), arrayOf(trust), null) }

    private fun listen(ctx: SSLContext, port: Int): SSLServerSocket =
        (ctx.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", port))
            needClientAuth = true
        }

    fun startPairing(port: Int = pairingPort) {
        val server = listen(context(AnyClientTrust { true }), port)
        pairingServer = server
        pairingPort = server.localPort
        thread(isDaemon = true, name = "fake-pairing") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                openSockets += s
                thread(isDaemon = true) { runCatching { handlePairing(s) }; runCatching { s.close() } }
            }
        }
    }

    fun startRemote(port: Int = remotePort) {
        val trust = AnyClientTrust { cert -> paired.contains(hex(cert.publicKeySha256())) }
        val server = listen(context(trust), port)
        remoteServer = server
        remotePort = server.localPort
        thread(isDaemon = true, name = "fake-remote") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                openSockets += s
                thread(isDaemon = true) { runCatching { handleRemote(s) }; runCatching { s.close() } }
            }
        }
    }

    /** Simulates the TV disappearing from the network (power off, Wi-Fi loss). */
    fun stopRemote() {
        remoteServer?.close()
        dropConnections()
    }

    /** Simulates a dropped link: existing connections die, the TV stays reachable. */
    fun dropConnections() {
        openSockets.forEach { runCatching { it.close() } }
        openSockets.clear()
    }

    override fun close() {
        pairingServer?.close()
        remoteServer?.close()
        dropConnections()
    }

    // ------------------------------------------------------------------ pairing (polo)

    private fun outer(status: Int = 200, build: ProtoWriter.() -> Unit = {}): ByteArray =
        ProtoWriter().varint(1, 2).varint(2, status.toLong()).apply(build).toByteArray()

    private fun handlePairing(s: SSLSocket) {
        s.startHandshake()
        val client = s.session.peerCertificates.first() as X509Certificate
        val input = s.inputStream
        val out = s.outputStream
        fun next(n: Int) = ProtoReader(Framing.read(input)).fields().firstOrNull { it.number == n }
        next(10) ?: return
        Framing.write(out, outer { message(11) { string(1, "FakeTV") } })
        next(20) ?: return
        Framing.write(out, outer { message(20) { message(2) { varint(1, 3); varint(2, 6) }; varint(3, 1) } })
        next(30) ?: return
        val nonceHex = hex(ByteArray(2).also { random.nextBytes(it) }).uppercase()
        val probe = PairingSecret.compute(client.publicKey as RSAPublicKey, identity.publicKey, "00$nonceHex")
        val code = "%02X".format(probe[0].toInt() and 0xFF) + nonceHex
        currentCode = code // "displayed" before the ack, like the real TV
        Framing.write(out, outer { message(31) {} })
        val secret = next(40)?.reader()?.fields()?.firstOrNull { it.number == 1 }?.bytes
        val expected = PairingSecret.compute(client.publicKey as RSAPublicKey, identity.publicKey, code)
        if (secret != null && MessageDigest.isEqual(secret, expected)) {
            paired += hex(client.publicKeySha256())
            Framing.write(out, outer { message(41) { bytes(1, secret) } })
        } else {
            Framing.write(out, outer(status = 402))
        }
    }

    /** Returns a well-formed code that passes the client's check byte but is still wrong. */
    fun wrongCodeWithValidCheckByte(client: ClientIdentity): String {
        val real = currentCode!!
        for (n in 0 until 0x10000) {
            val nonceHex = "%04X".format(n)
            if (nonceHex == real.substring(2)) continue
            val h = PairingSecret.compute(client.publicKey, identity.publicKey, "00$nonceHex")
            val code = "%02X".format(h[0].toInt() and 0xFF) + nonceHex
            if (code.substring(0, 2) == real.substring(0, 2)) return code
        }
        error("no candidate")
    }

    // ------------------------------------------------------------------ remote

    private fun msg(build: ProtoWriter.() -> Unit) = ProtoWriter().apply(build).toByteArray()

    private fun handleRemote(s: SSLSocket) {
        s.startHandshake()
        remoteConnections++
        val input = s.inputStream
        val out = s.outputStream
        val lock = Any()
        fun send(b: ByteArray) = synchronized(lock) { Framing.write(out, b) }
        send(msg {
            message(1) {
                int32(1, features)
                message(2) { string(1, "FakeTV"); string(2, "Test"); int32(3, 1); string(4, "1"); string(5, "fake"); string(6, "9.9") }
            }
        })
        thread(isDaemon = true) {
            var n = 1
            while (!s.isClosed) {
                Thread.sleep(pingIntervalMs)
                if (!silent) runCatching { send(msg { message(8) { int32(1, n++) } }) }.onFailure { return@thread }
            }
        }
        while (true) {
            val raw = Framing.read(input)
            if (silent) continue
            for (f in ProtoReader(raw).fields()) {
                val sub = f.reader().fields()
                when (f.number) {
                    1 -> send(msg { message(2) {} })
                    2 -> {
                        send(msg { message(40) { bool(1, isOn) } })
                        if (reportsVolume) send(volumeMsg())
                        send(imeApp(currentApp))
                    }
                    9 -> pingResponses += sub.firstOrNull { it.number == 1 }?.int ?: 0
                    10 -> {
                        val code = sub.firstOrNull { it.number == 1 }?.int ?: 0
                        val dir = sub.firstOrNull { it.number == 2 }?.int ?: 0
                        keys += code to dir
                        if (code == KeyCodes.SEARCH && answerVoice && features and 8 != 0) {
                            send(msg { message(30) { int32(1, 7); string(2, "com.google.android.katniss") } })
                        }
                        if (dir == 3) when (code) {
                            KeyCodes.SLEEP -> if (!ignoreSleepKey && isOn && goStandby(s, ::send)) return
                            KeyCodes.POWER -> if (isOn) { if (goStandby(s, ::send)) return } else { isOn = true; send(msg { message(40) { bool(1, true) } }) }
                            KeyCodes.WAKEUP -> if (!isOn) { isOn = true; send(msg { message(40) { bool(1, true) } }) }
                            KeyCodes.VOLUME_UP -> { volume = minOf(100, volume + 1); if (reportsVolume) send(volumeMsg()) }
                            KeyCodes.VOLUME_DOWN -> { volume = maxOf(0, volume - 1); if (reportsVolume) send(volumeMsg()) }
                            KeyCodes.VOLUME_MUTE -> { muted = !muted; if (reportsVolume) send(volumeMsg()) }
                            KeyCodes.HOME -> { currentApp = HOME_APP; send(imeApp(HOME_APP)) }
                        }
                    }
                    21 -> {
                        val edit = sub.firstOrNull { it.number == 3 }?.reader()?.fields()
                        val obj = edit?.firstOrNull { it.number == 2 }?.reader()?.fields()
                        texts += obj?.firstOrNull { it.number == 3 }?.string ?: ""
                    }
                    90 -> {
                        val link = sub.firstOrNull { it.number == 1 }?.string ?: ""
                        links += link
                        when (val o = linkOutcome(link)) {
                            is LinkOutcome.Open -> { Thread.sleep(150); currentApp = o.pkg; send(imeApp(o.pkg)) }
                            LinkOutcome.Reject -> {
                                send(msg { message(3) { bool(1, true); message(2) { message(90) { string(1, link) } } } })
                                s.close()
                                return
                            }
                            LinkOutcome.Ignore -> {}
                        }
                    }
                    31 -> voicePayloadSizes += sub.firstOrNull { it.number == 2 }?.bytes?.size ?: 0
                    32 -> voiceEnds += sub.firstOrNull { it.number == 1 }?.int ?: 0
                }
            }
        }
    }

    private fun imeApp(pkg: String) = msg { message(20) { message(1) { string(12, pkg) } } }

    private fun volumeMsg() = msg { message(50) { varint(6, 100); varint(7, volume.toLong()); bool(8, muted) } }

    /** Returns true when the connection ended because the network went away. */
    private fun goStandby(s: SSLSocket, send: (ByteArray) -> Unit): Boolean {
        isOn = false
        send(msg { message(40) { bool(1, false) } })
        if (!networkOffInStandby) return false
        Thread.sleep(100)
        stopRemote()
        runCatching { s.close() }
        return true
    }

    /** What a working network wake (Wake-on-LAN) would do: the TV comes back on the network, screen on. */
    fun wakeFromNetwork() {
        isOn = true
        startRemote(remotePort)
    }

    companion object {
        const val HOME_APP = "com.google.android.apps.tv.launcherx"

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    private class ServerKeyManager(private val id: ClientIdentity) : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = "tv"
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = "tv"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("tv")
        override fun getCertificateChain(alias: String?) = arrayOf(id.certificate)
        override fun getPrivateKey(alias: String?) = id.privateKey
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
    }

    private class AnyClientTrust(private val accept: (X509Certificate) -> Boolean) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val c = chain?.firstOrNull() ?: throw CertificateException("no client cert")
            if (!accept(c)) throw CertificateException("client not paired")
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
