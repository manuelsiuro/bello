package com.bello.assistant.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The address a phone on the same Wi-Fi can reach the tablet at (FR-PAGE-04). No permission is
 * needed to list the interfaces; the choice itself is pure and unit tested. On the Galaxy Tab 4
 * `wlan0` carries the address, `p2p0` is up without one and `rmnet*` are down.
 */
object LocalAddress {

    class Candidate(val name: String, val isUp: Boolean, val addresses: List<InetAddress>)

    fun wifiIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.map { nic ->
            Candidate(nic.name, runCatching { nic.isUp }.getOrDefault(false), nic.inetAddresses.toList())
        }
    }.getOrNull()?.let { pick(it) }

    /** The wireless interface's site-local IPv4 first, any other site-local IPv4 next, never loopback. */
    fun pick(candidates: List<Candidate>): String? {
        val usable = candidates.filter { it.isUp && EXCLUDED.none { prefix -> it.name.startsWith(prefix) } }
        return usable.filter { it.name.startsWith("wlan") }.firstNotNullOfOrNull(::siteLocal)
            ?: usable.firstNotNullOfOrNull(::siteLocal)
    }

    private fun siteLocal(candidate: Candidate): String? = candidate.addresses
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress

    private val EXCLUDED = listOf("lo", "p2p", "rmnet", "dummy")
}
