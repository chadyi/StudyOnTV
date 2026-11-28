package com.studyontv.tv

import java.util.Locale

/**
 * 手动添加多设备地址解析器：
 * - 自动扫描（UDP 18099）仍严格限定 RFC1918 私有局域网；
 * - 手动添加允许任意非回环/非链路本地 IPv4（含 Tailscale 100.64.x、VPN、公网 IP）、方括号 IPv6 及任意域名（支持 http/https）；
 * - EU 仅本地隐私版（localOnly = true）仍强制退回 RFC1918 私有局域网校验。
 */
internal object PeerHostParser {
    fun formatHostPort(host: String, port: Int, scheme: String = "http"): String =
        if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"

    private fun parseIpv4Octets(ip: String): IntArray? {
        if (ip.isEmpty() || ip.length > 15) return null
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || (p.length > 1 && p[0] == '0') || p.any { it !in '0'..'9' }) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            octets[i] = v
        }
        return octets
    }

    private fun isAllowedManualIpv4(ip: String): Boolean {
        val o = parseIpv4Octets(ip) ?: return false
        val a = o[0]
        val b = o[1]
        if (a == 0 || a == 127 || (a == 169 && b == 254) || a >= 224) return false
        return true
    }

    private fun isAllowedManualIpv6(bracketed: String): Boolean {
        if (!bracketed.startsWith("[") || !bracketed.endsWith("]")) return false
        val inner = bracketed.substring(1, bracketed.length - 1).lowercase(Locale.US)
        if (inner.isEmpty() || inner.length > 45 || !inner.contains(':')) return false
        if (inner.any { it !in '0'..'9' && it !in 'a'..'f' && it != ':' && it != '.' }) return false
        if (inner == "::" || inner == "::1" || inner.startsWith("fe8") || inner.startsWith("fe9") ||
            inner.startsWith("fea") || inner.startsWith("feb") || inner.startsWith("ff")
        ) return false
        val groups = inner.split(':').filter { it.isNotEmpty() }
        if (groups.isEmpty()) return false
        if (groups.all { it.all { ch -> ch == '0' } } ||
            (groups.dropLast(1).all { it.all { ch -> ch == '0' } } && groups.last().trimStart('0') == "1")
        ) return false
        return true
    }

    private fun isAllowedManualDomain(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        val lower = host.lowercase(Locale.US)
        if (lower == "localhost" || lower.endsWith(".localhost") || lower == "ip6-localhost") return false
        val labels = lower.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        if (labels.any { label -> label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' } }) return false
        if (labels.all { label -> label.all { it in '0'..'9' } }) return false
        return true
    }

    fun parseManualPeerHost(
        rawInput: String,
        defaultPort: Int = PeerDiscovery.DEFAULT_HTTP_PORT,
        localOnly: Boolean = LocalOnlyGuard.enabled
    ): PeerDiscovery.ParsedLanHost? {
        if (localOnly) return PeerDiscovery.parsePrivateLanHost(rawInput, defaultPort)
        if (defaultPort !in 1..65535) return null
        var s = rawInput.trim()
        if (s.isEmpty() || s.length > 300) return null
        if (s.any { it.isWhitespace() || it.code < 0x20 }) return null
        var scheme = "http"
        var explicitHttps = false
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) {
            val rawScheme = s.substring(0, schemeIdx).lowercase(Locale.US)
            if (rawScheme != "http" && rawScheme != "https") return null
            scheme = rawScheme
            explicitHttps = rawScheme == "https"
            s = s.substring(schemeIdx + 3)
        }
        val authority = s.substringBefore('/')
        if (authority.isEmpty() || authority.contains('@') || authority.contains('?') || authority.contains('#')) return null

        val hostPart: String
        val portPart: String?
        if (authority.startsWith("[")) {
            val closeIdx = authority.indexOf(']')
            if (closeIdx <= 1) return null
            hostPart = authority.substring(0, closeIdx + 1).lowercase(Locale.US)
            val rest = authority.substring(closeIdx + 1)
            portPart = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
            if (!isAllowedManualIpv6(hostPart)) return null
        } else {
            val colonIdx = authority.indexOf(':')
            if (colonIdx < 0) {
                hostPart = authority.lowercase(Locale.US)
                portPart = null
            } else {
                if (authority.indexOf(':', colonIdx + 1) >= 0) return null
                hostPart = authority.substring(0, colonIdx).lowercase(Locale.US)
                portPart = authority.substring(colonIdx + 1)
            }
            val validHost = if (hostPart.all { it in '0'..'9' || it == '.' }) {
                isAllowedManualIpv4(hostPart)
            } else {
                isAllowedManualDomain(hostPart)
            }
            if (!validHost) return null
        }

        val port = if (portPart == null) {
            if (explicitHttps) 443 else defaultPort
        } else {
            if (portPart.isEmpty() || portPart.length > 5 || portPart[0] == '0' || portPart.any { it !in '0'..'9' }) return null
            val p = portPart.toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            p
        }
        return PeerDiscovery.ParsedLanHost(hostPart, port, scheme)
    }
}
