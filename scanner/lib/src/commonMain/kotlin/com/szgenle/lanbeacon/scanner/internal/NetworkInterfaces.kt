package com.szgenle.lanbeacon.scanner.internal

/**
 * 枚举本机所有 RFC1918 IPv4 /24 子网前缀。
 *
 * 返回形如 ["192.168.31.", "10.0.1."] 的列表。
 */
internal expect fun getLocalSubnetPrefixes(): List<String>
