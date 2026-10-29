package com.coyotevision.app

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddresses {
    /** Avoid mobile-data and VPN interfaces, which cannot be used by the other phone on Wi-Fi. */
    fun discover(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && isLanInterface(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress }.distinct().sorted()
    }.getOrDefault(emptyList())

    internal fun isLanInterface(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("wlan", "wifi", "swlan", "ap", "eth", "en", "br").any { lower.startsWith(it) }
    }
}
