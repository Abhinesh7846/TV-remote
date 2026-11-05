package com.abhinesh.tvremote.cli

import com.abhinesh.tvremote.core.AppLauncher
import com.abhinesh.tvremote.core.AppShortcut
import com.abhinesh.tvremote.core.KeyCodes
import com.abhinesh.tvremote.core.PairingException
import com.abhinesh.tvremote.core.PairingSession
import com.abhinesh.tvremote.core.RemoteSession
import com.abhinesh.tvremote.core.proto.RemoteMessages.Direction
import com.abhinesh.tvremote.core.tls.ClientIdentity
import com.abhinesh.tvremote.core.tls.TvCertificateInfo
import com.abhinesh.tvremote.core.tls.publicKeySha256
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalTime
import kotlin.system.exitProcess

/**
 * Laptop test harness for the shared protocol core. Not shipped in the app.
 *
 *   pair <host>                    pair; reads the TV code from stdin or cli-data/code.txt
 *   run <host> <action> [...]      connect and run actions, e.g. home up down ok wait:1500
 *                                  key:<code> long:<code>:<ms> launch:<id> text:<s> voice:<wav> status
 */
fun main(args: Array<String>) {
    if (args.size < 2 || args[0] !in setOf("pair", "run")) {
        println("usage: pair <host> | run <host> <actions...>")
        exitProcess(2)
    }
    val dir = File("cli-data").apply { mkdirs() }
    val identity = loadOrCreateIdentity(dir)
    val host = args[1]
    runBlocking {
        if (args[0] == "pair") pair(host, identity, dir) else run(host, identity, dir, args.drop(2))
    }
    exitProcess(0)
}

private fun log(s: String) = println("[${LocalTime.now().withNano(0)}] $s")

private fun loadOrCreateIdentity(dir: File): ClientIdentity {
    val keyFile = File(dir, "client.key")
    val certFile = File(dir, "client.crt")
    if (keyFile.exists() && certFile.exists()) {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream()) as X509Certificate
        return ClientIdentity(key, cert)
    }
    log("generating a new client identity")
    val id = ClientIdentity.generate("TVRemote laptop test")
    keyFile.writeBytes(id.privateKey.encoded)
    certFile.writeBytes(id.certificate.encoded)
    return id
}

private suspend fun pair(host: String, identity: ClientIdentity, dir: File) {
    val codeFile = File(dir, "code.txt").apply { delete() }
    val session = PairingSession(host, identity, "TVRemote (laptop test)", log = ::log)
    try {
        session.start()
        val cert = session.serverCertificate!!
        val info = TvCertificateInfo.from(cert)
        log("TV certificate subject: ${cert.subjectX500Principal}")
        log("parsed: model=${info.model} mac=${info.macAddress} serverName=${session.serverName}")
        log("pairing-port key sha256: ${cert.publicKeySha256().toHex()}")
        while (true) {
            log("ENTER CODE: type it here, or write it to ${codeFile.absolutePath}")
            val code = waitForCode(codeFile)
            log("local check of '$code': ${if (session.isCodeConsistent(code)) "consistent" else "MISMATCH"}")
            try {
                session.submitCode(code)
                log("PAIRED OK")
                File(dir, "paired-host.txt").writeText(host)
                return
            } catch (e: PairingException) {
                log("pairing failed: ${e.reason} ${e.message}")
                if (e.reason != PairingException.Reason.CODE_MISTYPED) return
                codeFile.delete()
            }
        }
    } catch (e: PairingException) {
        log("pairing failed: ${e.reason} ${e.message}")
    } finally {
        session.close()
    }
}

private suspend fun waitForCode(codeFile: File): String {
    val deadline = System.currentTimeMillis() + 300_000
    while (System.currentTimeMillis() < deadline) {
        // available() throws on Windows when stdin isn't a console; fall back to the file then.
        if (runCatching { System.`in`.available() > 0 }.getOrDefault(false)) {
            readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (codeFile.exists()) codeFile.readText().trim().takeIf { it.isNotEmpty() }?.let { codeFile.delete(); return it }
        delay(300)
    }
    error("timed out waiting for the pairing code")
}

private suspend fun run(host: String, identity: ClientIdentity, dir: File, actions: List<String>) {
    val pinFile = File(dir, "remote-pin.txt")
    val pin = pinFile.takeIf { it.exists() }?.readText()?.trim()?.hexToBytes()
    val s = RemoteSession(host, identity, pin, log = ::log)
    try {
        s.connect()
    } catch (e: Exception) {
        log("connect failed: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    val cert = s.serverCertificate!!
    log("connected. remote-port key sha256: ${cert.publicKeySha256().toHex()}")
    if (pin == null) pinFile.writeText(cert.publicKeySha256().toHex())
    delay(800)
    log("status: ${s.status.value}")
    for (a in actions) {
        val parts = a.split(':')
        when (parts[0]) {
            "home" -> s.sendKey(KeyCodes.HOME)
            "back" -> s.sendKey(KeyCodes.BACK)
            "up" -> s.sendKey(KeyCodes.DPAD_UP)
            "down" -> s.sendKey(KeyCodes.DPAD_DOWN)
            "left" -> s.sendKey(KeyCodes.DPAD_LEFT)
            "right" -> s.sendKey(KeyCodes.DPAD_RIGHT)
            "ok" -> s.sendKey(KeyCodes.DPAD_CENTER)
            "volup" -> s.sendKey(KeyCodes.VOLUME_UP)
            "voldown" -> s.sendKey(KeyCodes.VOLUME_DOWN)
            "key" -> s.sendKey(parts[1].toInt())
            "long" -> {
                s.sendKey(parts[1].toInt(), Direction.START_LONG)
                delay(parts.getOrNull(2)?.toLong() ?: 1000)
                s.sendKey(parts[1].toInt(), Direction.END_LONG)
            }
            "wait" -> delay(parts[1].toLong())
            "status" -> log("status: ${s.status.value}")
            "text" -> log("text sent: ${s.sendText(a.removePrefix("text:"))}")
            "launch" -> {
                val sc = AppShortcut.DEFAULTS.firstOrNull { it.id == parts[1] }
                    ?: AppShortcut(parts[1], parts[1], parts[1], listOf(a.removePrefix("launch:")), 0)
                log("launch ${sc.label}: ${AppLauncher.launch(sc) { s }}")
            }
            "link" -> log("app link sent: ${s.launchAppLink(a.removePrefix("link:"))}")
            "share" -> {
                val parsed = com.abhinesh.tvremote.core.share.LinkParser.parse(a.removePrefix("share:"))
                log("parsed: $parsed")
                if (parsed is com.abhinesh.tvremote.core.share.ParseResult.Ok) {
                    log("send: ${com.abhinesh.tvremote.core.share.SendToTv.send(parsed.link) { s }}")
                }
            }
            "voice" -> voice(s, File(parts[1]), parts.getOrNull(2)?.toInt() ?: 0, parts.getOrNull(3)?.toInt() ?: 0)
            else -> log("unknown action $a")
        }
        log("action '$a' done")
        delay(400)
    }
    delay(800)
    log("final status: ${s.status.value}")
    s.close()
}

/** Streams a 16-bit mono 8 kHz WAV file to the TV in real time, like push-to-talk would. */
private suspend fun voice(s: RemoteSession, wav: File, leadSilenceMs: Int, tailSilenceMs: Int) {
    val bytes = wav.readBytes()
    val dataAt = (12 until bytes.size - 8).firstOrNull { String(bytes, it, 4, Charsets.US_ASCII) == "data" }
        ?: error("not a WAV file")
    val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16)
    val bytesPerMs = rate * 2 / 1000
    val pcm = ByteArray(leadSilenceMs * bytesPerMs) + bytes.copyOfRange(dataAt + 8, bytes.size) + ByteArray(tailSilenceMs * bytesPerMs)
    val id = try {
        s.startVoice()
    } catch (e: Exception) {
        log("voice unavailable: ${e.message}")
        return
    }
    log("voice session $id: streaming ${pcm.size} bytes at $rate Hz (${pcm.size / (rate * 2.0)} s)")
    var i = 0
    while (i < pcm.size) {
        val end = minOf(pcm.size, i + RemoteSession.VOICE_CHUNK_MIN)
        s.sendVoiceAudio(id, pcm.copyOfRange(i, end))
        delay(((end - i) / bytesPerMs).toLong()) // real-time pacing
        i = end
    }
    s.endVoice(id)
    log("voice session $id ended")
    delay(4000)
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
