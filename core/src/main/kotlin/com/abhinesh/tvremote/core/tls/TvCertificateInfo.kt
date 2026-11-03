package com.abhinesh.tvremote.core.tls

import java.security.cert.X509Certificate

/**
 * Android TV Remote Service certificates encode device details in the subject, e.g.
 *   CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX
 *   dnQualifier=fugu/fugu/Nexus Player/CN=atvremote/XX:XX:XX:XX:XX:XX
 * (examples from androidtvremote2). Parsing is best effort: unknown formats yield nulls.
 */
data class TvCertificateInfo(val commonName: String, val model: String?, val macAddress: String?) {
    companion object {
        private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

        fun from(cert: X509Certificate): TvCertificateInfo {
            val attrs = subjectAttributes(cert.subjectX500Principal.encoded)
            val cn = attrs["2.5.4.3"].orEmpty()
            val dnq = attrs["2.5.4.46"].orEmpty()
            val cnParts = cn.split('/')
            val model = when {
                dnq.isNotEmpty() -> dnq.split('/').last()
                cnParts.size > 1 -> cnParts[cnParts.size - 2]
                else -> null
            }?.takeIf { it.isNotBlank() }
            val mac = cnParts.last().takeIf { MAC.matches(it) }?.uppercase()?.replace('-', ':')
            return TvCertificateInfo(cn, model, mac)
        }

        /** Tiny DER walk over Name ::= SEQUENCE OF SET OF SEQUENCE { OID, value }. */
        private fun subjectAttributes(der: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            runCatching {
                val name = Der(der).readTlv()
                val sets = Der(name.second)
                while (sets.hasMore()) {
                    val set = Der(sets.readTlv().second)
                    while (set.hasMore()) {
                        val atv = Der(set.readTlv().second)
                        val oid = decodeOid(atv.readTlv().second)
                        val value = atv.readTlv()
                        val text = when (value.first) {
                            0x1E -> String(value.second, Charsets.UTF_16BE) // BMPString
                            else -> String(value.second, Charsets.UTF_8)
                        }
                        out.putIfAbsent(oid, text)
                    }
                }
            }
            return out
        }

        private fun decodeOid(b: ByteArray): String {
            if (b.isEmpty()) return ""
            val parts = ArrayList<Long>()
            val first = b[0].toInt() and 0xFF
            parts += (first / 40).toLong(); parts += (first % 40).toLong()
            var v = 0L
            for (i in 1 until b.size) {
                val x = b[i].toInt() and 0xFF
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) { parts += v; v = 0 }
            }
            return parts.joinToString(".")
        }
    }

    private class Der(private val d: ByteArray) {
        private var p = 0
        fun hasMore() = p < d.size
        fun readTlv(): Pair<Int, ByteArray> {
            val tag = d[p++].toInt() and 0xFF
            var len = d[p++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { len = (len shl 8) or (d[p++].toInt() and 0xFF) }
            }
            val v = d.copyOfRange(p, p + len)
            p += len
            return tag to v
        }
    }
}
