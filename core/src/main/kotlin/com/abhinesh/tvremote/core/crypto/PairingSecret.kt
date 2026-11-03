package com.abhinesh.tvremote.core.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * The polo pairing secret: SHA-256 over both RSA public keys plus the last 4 hex digits of
 * the code the TV shows. The first 2 hex digits of that code are a check byte that must
 * equal the first byte of the hash, which lets us reject a mistyped code before sending it.
 */
object PairingSecret {
    private val HEX = Regex("^[0-9A-Fa-f]{6}$")

    fun isWellFormed(code: String): Boolean = HEX.matches(code)

    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
        require(isWellFormed(code)) { "Pairing code must be 6 hexadecimal characters" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsignedBytes(client.modulus))
        md.update(unsignedBytes(client.publicExponent))
        md.update(unsignedBytes(server.modulus))
        md.update(unsignedBytes(server.publicExponent))
        md.update(hexToBytes(code.substring(2)))
        return md.digest()
    }

    /** True when the code's check byte matches the hash, i.e. the code was typed correctly. */
    fun checkByteMatches(secret: ByteArray, code: String): Boolean =
        (secret[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    /** Big-endian magnitude without leading zero bytes (what polo's removeLeadingNullBytes does). */
    fun unsignedBytes(value: BigInteger): ByteArray {
        val raw = value.abs().toByteArray()
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        return raw.copyOfRange(start, raw.size)
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
