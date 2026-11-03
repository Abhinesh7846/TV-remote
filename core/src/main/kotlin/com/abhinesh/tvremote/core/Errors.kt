package com.abhinesh.tvremote.core

import java.io.IOException

/** TV not reachable over TCP (off, wrong IP, different network, or Remote Service not running). */
class CannotConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The TV refused this phone's certificate: pairing is needed (again). [definite] is false when
 * the secure handshake was merely cut off (which some stacks report instead of a TLS alert).
 */
class PairingRequiredException(message: String, cause: Throwable? = null, val definite: Boolean = true) : IOException(message, cause)

/** The device at this address presents a different key than the TV we paired with. */
class TvIdentityChangedException(cause: Throwable? = null) :
    IOException("This TV's identity changed since pairing (factory reset, or a different device at this address). Pair again.", cause)

/** An established connection ended (TV closed it, Wi-Fi dropped, or the TV stopped answering). */
class ConnectionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

class PairingException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** Typed code fails the local check byte: definitely a typo, connection still open. */
        CODE_MISTYPED,
        /** TV answered with a non-OK status (e.g. 402 wrong code); TV ends the session. */
        REJECTED_BY_TV,
        /** TV closed the pairing session (cancelled on TV, pairing dialog timed out). */
        CLOSED_BY_TV,
        /** TV did not answer a pairing step within the timeout. */
        TIMEOUT,
        CANNOT_CONNECT,
    }
}

/** The TV did not start a voice session after KEYCODE_SEARCH (no voice support, or Assistant unavailable). */
class VoiceUnavailableException(message: String) : IOException(message)
