package com.abhinesh.tvremote.core.proto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf wire-format codec covering exactly what the Android TV Remote v2
 * messages use: varints (int32/uint32/enum/bool) and length-delimited fields
 * (string/bytes/embedded messages). Fields are written in the order they are called,
 * so callers write them in field-number order to match the reference encoder.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        writeRawVarint(out, value)
        return this
    }

    /** int32 semantics: negative values are sign-extended to 10 bytes, like protobuf does. */
    fun int32(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LEN)
        writeRawVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeRawVarint(out, ((field shl 3) or wireType).toLong())

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_I64 = 1
        const val WIRE_LEN = 2
        const val WIRE_I32 = 5

        fun writeRawVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun encodeVarint(value: Long): ByteArray =
            ByteArrayOutputStream().also { writeRawVarint(it, value) }.toByteArray()
    }
}

/** One decoded field. [varint] is set for varint fields, [bytes] for length-delimited ones. */
class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?) {
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L
    val string: String get() = String(bytes ?: ByteArray(0), Charsets.UTF_8)
    fun reader(): ProtoReader = ProtoReader(bytes ?: ByteArray(0))
}

class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun fields(): List<ProtoField> {
        val result = ArrayList<ProtoField>()
        while (pos < data.size) result += readField()
        return result
    }

    private fun readField(): ProtoField {
        val key = readVarint()
        val number = (key ushr 3).toInt()
        val wireType = (key and 7).toInt()
        if (number <= 0) throw ProtoException("Invalid field number $number")
        return when (wireType) {
            ProtoWriter.WIRE_VARINT -> ProtoField(number, wireType, readVarint(), null)
            ProtoWriter.WIRE_LEN -> {
                val len = readVarint()
                if (len < 0 || len > data.size - pos) throw ProtoException("Truncated field $number")
                val b = data.copyOfRange(pos, pos + len.toInt())
                pos += len.toInt()
                ProtoField(number, wireType, 0, b)
            }
            ProtoWriter.WIRE_I64 -> { skip(8); ProtoField(number, wireType, 0, null) }
            ProtoWriter.WIRE_I32 -> { skip(4); ProtoField(number, wireType, 0, null) }
            else -> throw ProtoException("Unsupported wire type $wireType")
        }
    }

    private fun skip(n: Int) {
        if (n > data.size - pos) throw ProtoException("Truncated fixed field")
        pos += n
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= data.size) throw ProtoException("Truncated varint")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoException("Varint too long")
    }
}

class ProtoException(message: String) : IOException(message)

/**
 * Length-delimited framing used on both TV ports: each message is preceded by its size
 * as a varint (protobuf "delimited" format).
 */
object Framing {
    const val MAX_MESSAGE = 1 shl 20

    fun write(out: OutputStream, payload: ByteArray) {
        val buf = ByteArrayOutputStream(payload.size + 5)
        ProtoWriter.writeRawVarint(buf, payload.size.toLong())
        buf.write(payload)
        out.write(buf.toByteArray())
        out.flush()
    }

    /** Blocks until one full message is read. Throws [EOFException] when the peer closes. */
    fun read(input: InputStream): ByteArray {
        var len = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed by TV")
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw ProtoException("Corrupt message length")
        }
        if (len < 0 || len > MAX_MESSAGE) throw ProtoException("Message too large: $len")
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val n = input.read(payload, off, payload.size - off)
            if (n < 0) throw EOFException("Connection closed by TV")
            off += n
        }
        return payload
    }
}
