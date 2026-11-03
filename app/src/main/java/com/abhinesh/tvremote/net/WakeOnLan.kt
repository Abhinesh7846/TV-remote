package com.abhinesh.tvremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Standard Wake-on-LAN magic packet. Only helps if the TV keeps its network interface
 * listening in standby; many TVs don't, and a TV that's fully powered off never will.
 */
object WakeOnLan {
    private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun isValidMac(mac: String) = MAC.matches(mac.trim())

    suspend fun send(mac: String, tvHost: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidMac(mac)) { "Enter the MAC address as 6 pairs, for example AA:BB:CC:DD:EE:FF" }
            val macBytes = mac.trim().split(':', '-').map { it.toInt(16).toByte() }
            val payload = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { macBytes }.toByteArray()
            val targets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                tvHost?.split('.')?.takeIf { it.size == 4 }?.let { add(InetAddress.getByName("${it[0]}.${it[1]}.${it[2]}.255")) }
            }
            DatagramSocket().use { s ->
                s.broadcast = true
                for (t in targets) for (port in intArrayOf(9, 7)) s.send(DatagramPacket(payload, payload.size, t, port))
            }
        }
    }
}
