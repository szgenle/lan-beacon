package com.szgenle.lanbeacon.scanner.internal

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * JVM 实现：通过 [NetworkInterface] 枚举本机所有 RFC1918 私有 IPv4 /24 子网前缀。
 */
internal actual fun getLocalSubnetPrefixes(): List<String> {
    val prefixes = mutableSetOf<String>()
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    for (iface in interfaces) {
        if (!iface.isUp || iface.isLoopback) continue
        for (addr in iface.inetAddresses) {
            if (addr !is Inet4Address) continue
            val ip = addr.hostAddress ?: continue
            val parts = ip.split(".")
            if (parts.size != 4) continue

            val first = parts[0].toIntOrNull() ?: continue
            val second = parts[1].toIntOrNull() ?: continue

            // RFC1918: 10.x.x.x / 172.16-31.x.x / 192.168.x.x
            val isPrivate = (first == 10) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)

            if (isPrivate) {
                prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}.")
            }
        }
    }
    return prefixes.toList()
}
