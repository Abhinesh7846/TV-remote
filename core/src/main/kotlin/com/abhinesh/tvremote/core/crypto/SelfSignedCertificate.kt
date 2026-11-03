package com.abhinesh.tvremote.core.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the client's self-signed X.509 v3 certificate with plain JDK APIs (a small DER
 * encoder), so no BouncyCastle is needed on Android. Shape matches androidtvremote2's
 * certificate_generator: RSA-2048, SHA256withRSA, CN=<name>, basicConstraints CA, SAN dNSName.
 */
object SelfSignedCertificate {

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()

    fun create(commonName: String, keyPair: KeyPair, validYears: Long = 20, random: SecureRandom = SecureRandom()): X509Certificate {
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val serial = BigInteger(63, random).add(BigInteger.ONE)
        val sigAlg = seq(oid("1.2.840.113549.1.1.11"), DER_NULL) // sha256WithRSAEncryption
        val name = seq(set(seq(oid("2.5.4.3"), utf8(commonName))))
        val extensions = explicit(3, seq(
            seq(oid("2.5.29.19"), octets(seq(bool(true), integer(BigInteger.ZERO)))),       // basicConstraints
            seq(oid("2.5.29.17"), octets(seq(tlv(0x82, commonName.toByteArray(Charsets.US_ASCII))))), // SAN dNSName
        ))
        val tbs = seq(
            explicit(0, integer(BigInteger.valueOf(2))), // v3
            integer(serial),
            sigAlg,
            name,
            seq(time(now.minusDays(1)), time(now.plusYears(validYears))),
            name,
            keyPair.public.encoded, // already a DER SubjectPublicKeyInfo
            extensions,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    // ---- minimal DER helpers

    private val DER_NULL = byteArrayOf(0x05, 0x00)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    private fun explicit(n: Int, inner: ByteArray) = tlv(0xA0 or n, inner)
    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    private fun octets(v: ByteArray) = tlv(0x04, v)
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun time(t: ZonedDateTime): ByteArray =
        if (t.year < 2050) tlv(0x17, t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
        else tlv(0x18, t.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val stack = ArrayList<Int>()
            var v = arc
            stack += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }
}
