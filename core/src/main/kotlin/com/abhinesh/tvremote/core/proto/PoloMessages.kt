package com.abhinesh.tvremote.core.proto

/**
 * Pairing ("polo") messages sent on port 6467. Field numbers follow polo.proto from
 * Google's google-tv-pairing-protocol (Apache-2.0), as used by tronikos/androidtvremote2.
 *
 * OuterMessage { protocol_version=1, status=2, pairing_request=10, pairing_request_ack=11,
 *                options=20, configuration=30, configuration_ack=31, secret=40, secret_ack=41 }
 */
object PoloMessages {
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200
    const val STATUS_ERROR = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET = 402

    private const val ENCODING_TYPE_HEXADECIMAL = 3
    private const val ROLE_TYPE_INPUT = 1
    private const val SYMBOL_LENGTH = 6

    private fun outer(build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter()
            .varint(1, PROTOCOL_VERSION.toLong())
            .varint(2, STATUS_OK.toLong())
            .apply(build)
            .toByteArray()

    fun pairingRequest(clientName: String, serviceName: String = "atvremote"): ByteArray = outer {
        message(10) {
            string(1, serviceName)
            string(2, clientName)
        }
    }

    /** Sent after pairing_request_ack: we accept 6-symbol hexadecimal codes as the input device. */
    fun options(): ByteArray = outer {
        message(20) {
            message(1) {
                varint(1, ENCODING_TYPE_HEXADECIMAL.toLong())
                varint(2, SYMBOL_LENGTH.toLong())
            }
            varint(3, ROLE_TYPE_INPUT.toLong())
        }
    }

    /** Sent after the TV's options; the TV shows the code once it acks this. */
    fun configuration(): ByteArray = outer {
        message(30) {
            message(1) {
                varint(1, ENCODING_TYPE_HEXADECIMAL.toLong())
                varint(2, SYMBOL_LENGTH.toLong())
            }
            varint(2, ROLE_TYPE_INPUT.toLong())
        }
    }

    fun secret(secret: ByteArray): ByteArray = outer {
        message(40) { bytes(1, secret) }
    }

    enum class Kind { PAIRING_REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, OTHER }

    data class Inbound(val protocolVersion: Int, val status: Int, val kind: Kind, val serverName: String?)

    fun parse(bytes: ByteArray): Inbound {
        var version = 0
        var status = 0
        var kind = Kind.OTHER
        var serverName: String? = null
        for (f in ProtoReader(bytes).fields()) {
            when (f.number) {
                1 -> version = f.int
                2 -> status = f.int
                11 -> {
                    kind = Kind.PAIRING_REQUEST_ACK
                    serverName = f.reader().fields().firstOrNull { it.number == 1 }?.string
                }
                20 -> kind = Kind.OPTIONS
                31 -> kind = Kind.CONFIGURATION_ACK
                41 -> kind = Kind.SECRET_ACK
            }
        }
        return Inbound(version, status, kind, serverName)
    }

    fun statusText(status: Int): String = when (status) {
        STATUS_OK -> "OK"
        STATUS_ERROR -> "error (400)"
        STATUS_BAD_CONFIGURATION -> "bad configuration (401)"
        STATUS_BAD_SECRET -> "wrong code (402)"
        else -> "status $status"
    }
}
