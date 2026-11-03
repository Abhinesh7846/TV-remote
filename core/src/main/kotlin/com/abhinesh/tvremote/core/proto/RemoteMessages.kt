package com.abhinesh.tvremote.core.proto

/**
 * Remote-control messages sent on port 6466 (proto3 RemoteMessage from remotemessage.proto,
 * as published in tronikos/androidtvremote2, Apache-2.0). proto3 omits default (zero/empty)
 * scalar values, so the writers below do the same to stay byte-compatible.
 */
object RemoteMessages {

    /** Feature bits negotiated in remote_configure / remote_set_active. */
    object Feature {
        const val PING = 1 shl 0
        const val KEY = 1 shl 1
        const val IME = 1 shl 2
        /** Lets the TV answer KEYCODE_SEARCH with remote_voice_begin so the phone can stream audio. */
        const val VOICE = 1 shl 3
        const val POWER = 1 shl 5
        const val VOLUME = 1 shl 6
        const val APP_LINK = 1 shl 9

        fun describe(mask: Int): String = listOfNotNull(
            "ping".takeIf { mask and PING != 0 },
            "keys".takeIf { mask and KEY != 0 },
            "keyboard".takeIf { mask and IME != 0 },
            "voice".takeIf { mask and VOICE != 0 },
            "power".takeIf { mask and POWER != 0 },
            "volume".takeIf { mask and VOLUME != 0 },
            "app links".takeIf { mask and APP_LINK != 0 },
        ).joinToString(", ").ifEmpty { "none" }
    }

    /** RemoteDirection enum. START_LONG = key down, END_LONG = key up, SHORT = full press. */
    enum class Direction(val wire: Int) { START_LONG(1), END_LONG(2), SHORT(3) }

    private fun ProtoWriter.int32NonZero(field: Int, value: Int) = apply { if (value != 0) int32(field, value) }
    private fun ProtoWriter.stringNonEmpty(field: Int, value: String) = apply { if (value.isNotEmpty()) string(field, value) }

    private fun msg(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).toByteArray()

    fun configureResponse(features: Int): ByteArray = msg {
        message(1) {
            int32NonZero(1, features)
            message(2) {
                int32(3, 1)
                string(4, "1")
                string(5, "atvremote")
                string(6, "1.0.0")
            }
        }
    }

    fun setActive(features: Int): ByteArray = msg { message(2) { int32NonZero(1, features) } }

    fun pingResponse(val1: Int): ByteArray = msg { message(9) { int32NonZero(1, val1) } }

    fun keyInject(keyCode: Int, direction: Direction): ByteArray = msg {
        message(10) {
            int32NonZero(1, keyCode)
            int32NonZero(2, direction.wire)
        }
    }

    /** Mirrors androidtvremote2 send_text: start = end = len-1, insert = 1, value = full text. */
    fun imeBatchEdit(imeCounter: Int, fieldCounter: Int, text: String): ByteArray = msg {
        message(21) {
            int32NonZero(1, imeCounter)
            int32NonZero(2, fieldCounter)
            message(3) {
                int32(1, 1)
                message(2) {
                    int32NonZero(1, text.length - 1)
                    int32NonZero(2, text.length - 1)
                    stringNonEmpty(3, text)
                }
            }
        }
    }

    fun appLinkLaunch(appLink: String): ByteArray = msg { message(90) { stringNonEmpty(1, appLink) } }

    fun voiceBegin(sessionId: Int): ByteArray = msg { message(30) { int32NonZero(1, sessionId) } }

    fun voicePayload(sessionId: Int, samples: ByteArray): ByteArray = msg {
        message(31) {
            int32NonZero(1, sessionId)
            if (samples.isNotEmpty()) bytes(2, samples)
        }
    }

    fun voiceEnd(sessionId: Int): ByteArray = msg { message(32) { int32NonZero(1, sessionId) } }

    // ---------------------------------------------------------------- inbound

    sealed interface Inbound {
        data class Configure(val features: Int, val model: String, val vendor: String, val appVersion: String) : Inbound
        data class SetActive(val active: Int) : Inbound
        data class PingRequest(val val1: Int) : Inbound
        data class ImeKeyInject(val appPackage: String, val appLabel: String, val fieldValue: String?) : Inbound
        data class ImeBatchEdit(val imeCounter: Int, val fieldCounter: Int, val value: String?) : Inbound
        data class ImeShowRequest(val fieldCounter: Int, val value: String, val label: String) : Inbound
        data class VoiceBegin(val sessionId: Int, val packageName: String) : Inbound
        data class Start(val started: Boolean) : Inbound
        data class Volume(val level: Int, val max: Int, val muted: Boolean, val playerModel: String) : Inbound
        /** remote_error echoes the message the TV refused; [rejectedAppLink] is set when it was an app link. */
        data class Error(val value: Boolean, val detail: String, val rejectedAppLink: String? = null) : Inbound
        data class Unknown(val fieldNumbers: List<Int>) : Inbound
    }

    fun parse(bytes: ByteArray): Inbound {
        val fields = ProtoReader(bytes).fields()
        for (f in fields) {
            val sub = { f.reader().fields() }
            when (f.number) {
                1 -> {
                    val s = sub()
                    val info = s.firstOrNull { it.number == 2 }?.reader()?.fields().orEmpty()
                    return Inbound.Configure(
                        features = s.int(1),
                        model = info.str(1),
                        vendor = info.str(2),
                        appVersion = info.str(6),
                    )
                }
                2 -> return Inbound.SetActive(sub().int(1))
                3 -> {
                    val s = sub()
                    val inner = s.firstOrNull { it.number == 2 }?.bytes
                    val innerFields = inner?.let { runCatching { ProtoReader(it).fields() }.getOrNull() }.orEmpty()
                    val link = innerFields.firstOrNull { it.number == 90 }?.let { lf -> lf.reader().fields().str(1) }
                    val detail = when {
                        link != null -> "app link rejected: $link"
                        innerFields.isNotEmpty() -> "rejected message fields ${innerFields.map { it.number }}"
                        else -> ""
                    }
                    return Inbound.Error(s.int(1) != 0, detail, link)
                }
                8 -> return Inbound.PingRequest(sub().int(1))
                20 -> {
                    val s = sub()
                    val app = s.firstOrNull { it.number == 1 }?.reader()?.fields().orEmpty()
                    val field = s.firstOrNull { it.number == 2 }?.reader()?.fields()
                    return Inbound.ImeKeyInject(app.str(12), app.str(10), field?.str(2))
                }
                21 -> {
                    val s = sub()
                    val edit = s.firstOrNull { it.number == 3 }?.reader()?.fields()
                    val obj = edit?.firstOrNull { it.number == 2 }?.reader()?.fields()
                    return Inbound.ImeBatchEdit(s.int(1), s.int(2), obj?.str(3))
                }
                22 -> {
                    val status = sub().firstOrNull { it.number == 2 }?.reader()?.fields().orEmpty()
                    return Inbound.ImeShowRequest(status.int(1), status.str(2), status.str(6))
                }
                30 -> {
                    val s = sub()
                    return Inbound.VoiceBegin(s.int(1), s.str(2))
                }
                40 -> return Inbound.Start(sub().int(1) != 0)
                50 -> {
                    val s = sub()
                    return Inbound.Volume(level = s.int(7), max = s.int(6), muted = s.int(8) != 0, playerModel = s.str(3))
                }
            }
        }
        return Inbound.Unknown(fields.map { it.number })
    }

    private fun List<ProtoField>.int(n: Int): Int = lastOrNull { it.number == n && it.wireType == ProtoWriter.WIRE_VARINT }?.int ?: 0
    private fun List<ProtoField>.str(n: Int): String = lastOrNull { it.number == n && it.wireType == ProtoWriter.WIRE_LEN }?.string ?: ""
}
