package com.studyontv.parent

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 家长端网络连接与发现工具：
 * 1. 自动扫描（UDP 18099、无线 ADB 5555 子网扫描）严格限定 RFC1918 私有局域网 IPv4；
 * 2. 手动添加设备支持任意网段（含 Tailscale/VPN 100.64.x、公网 IP、IPv6）与任意域名（支持 http/https）；
 * 3. EU 仅本地隐私版（BuildConfig.LOCAL_ONLY）仍严格限定私有局域网。
 */
object LanNet {
    const val DISCOVERY_PORT = 18099
    const val DEFAULT_HTTP_PORT = 8099
    const val DEFAULT_ADB_PORT = 5555
    const val MAGIC_DISCOVER = "SOTV_DISCOVER_V1"
    const val MAGIC_PEER = "SOTV_PEER_V1"
    private const val MAX_PACKET_BYTES = 512

    internal fun formatHostPort(host: String, port: Int, scheme: String = "http"): String =
        if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"

    data class ParsedHost(val ip: String, val port: Int, val scheme: String = "http") {
        val host: String get() = formatHostPort(ip, port, scheme)
        val baseUrl: String get() = "$scheme://$host"
        val adminUrl: String get() = "$baseUrl/admin/"
    }

    data class DiscoveredDevice(
        val id: String,
        val name: String,
        val ip: String,
        val port: Int,
        val version: String,
        val isTv: Boolean,
        val questions: Int,
        val lastSeenAt: Long = System.currentTimeMillis(),
        val scheme: String = "http"
    ) {
        val host: String get() = formatHostPort(ip, port, scheme)
        val baseUrl: String get() = "$scheme://$host"
    }

    data class AdbCandidate(
        val ip: String,
        val port: Int = DEFAULT_ADB_PORT,
        val studyOnTvName: String = "",
        val studyOnTvVersion: String = ""
    ) {
        val host: String get() = "$ip:$port"
    }

    /** 仅放行 RFC1918 私有局域网 IPv4 字面量（10/8、172.16/12、192.168/16）。 */
    fun isPrivateLanIp(ip: String): Boolean {
        if (ip.isEmpty() || ip.length > 15) return false
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return false
            if (p.length > 1 && p[0] == '0') return false
            if (p.any { it !in '0'..'9' }) return false
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        val a = octets[0]
        val b = octets[1]
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** 解析并校验 `ip` 或 `ip:port`（去掉可选 http:// 与路径，端口限定 1024..65535）。 */
    fun parsePrivateLanHost(rawInput: String, defaultPort: Int = DEFAULT_HTTP_PORT): ParsedHost? {
        if (defaultPort !in 1024..65535) return null
        val trimmed = rawInput.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()
        if (trimmed.isEmpty() || trimmed.length > 21) return null
        for (ch in trimmed) {
            if (ch !in '0'..'9' && ch != '.' && ch != ':') return null
        }
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx < 0) {
            return if (isPrivateLanIp(trimmed)) ParsedHost(trimmed, defaultPort) else null
        }
        if (trimmed.indexOf(':', colonIdx + 1) >= 0) return null
        val ipPart = trimmed.substring(0, colonIdx)
        val portPart = trimmed.substring(colonIdx + 1)
        if (!isPrivateLanIp(ipPart)) return null
        if (portPart.isEmpty() || portPart.length > 5 || portPart[0] == '0') return null
        val port = portPart.toIntOrNull() ?: return null
        if (port !in 1024..65535) return null
        return ParsedHost(ipPart, port)
    }

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
        // 全数字且含点但未通过 IPv4 校验（如 999.999.999.999 或 192.168.01.1）一律拒绝；单段纯数字也拒绝
        if (labels.all { label -> label.all { it in '0'..'9' } }) return false
        return true
    }

    /**
     * 解析手动添加的设备地址：允许任意非回环/非链路本地 IPv4（含 Tailscale 100.64.x、VPN、公网 IP）、
     * 方括号 IPv6 以及任意域名，支持可选 http:// 或 https:// 协议头与 1..65535 端口。
     * EU 仅本地隐私版（localOnly = true）仍严格退回私有局域网校验。
     */
    fun parseManualHost(
        rawInput: String,
        defaultPort: Int = DEFAULT_HTTP_PORT,
        localOnly: Boolean = BuildConfig.LOCAL_ONLY
    ): ParsedHost? {
        if (localOnly) return parsePrivateLanHost(rawInput, defaultPort)
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
        return ParsedHost(hostPart, port, scheme)
    }

    internal fun parsePeerPacket(bytes: ByteArray, length: Int, senderIp: String, nowMs: Long = System.currentTimeMillis()): DiscoveredDevice? {
        if (length !in 1..MAX_PACKET_BYTES || length > bytes.size) return null
        if (!isPrivateLanIp(senderIp)) return null
        return try {
            val obj = JSONObject(String(bytes, 0, length, Charsets.UTF_8))
            if (obj.optString("magic") != MAGIC_PEER) return null
            val port = obj.optInt("port", -1)
            if (port !in 1024..65535) return null
            val id = obj.optString("id").trim().take(32).ifEmpty { "$senderIp:$port" }
            val name = obj.optString("name").trim().take(32).ifEmpty { senderIp }
            val version = obj.optString("version").trim().take(24)
            val isTv = obj.optBoolean("isTv", true)
            val questions = obj.optInt("questions", 0).coerceAtLeast(0)
            DiscoveredDevice(id, name, senderIp, port, version, isTv, questions, nowMs)
        } catch (_: Exception) {
            null
        }
    }

    /** 列出本机活跃的私有局域网 IPv4 地址（排除回环与断开接口）。 */
    fun localPrivateIpv4Addresses(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress.orEmpty()
                        if (isPrivateLanIp(ip)) out.add(ip)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    internal fun buildSubnetScanIps(localIps: List<String>): List<String> {
        val selfSet = localIps.toSet()
        val prefixes = LinkedHashSet<String>()
        for (ip in localIps) {
            if (!isPrivateLanIp(ip)) continue
            val lastDot = ip.lastIndexOf('.')
            if (lastDot > 0) prefixes.add(ip.substring(0, lastDot))
        }
        val candidates = ArrayList<String>(prefixes.size * 254)
        for (prefix in prefixes) {
            for (hostOctet in 1..254) {
                val ip = "$prefix.$hostOctet"
                if (ip !in selfSet) candidates.add(ip)
            }
        }
        return candidates
    }

    /** 主动发送 UDP 18099 广播探测家里已装 StudyOnTV 的电视与平板。 */
    fun discoverStudyOnTvDevices(timeoutMs: Long = 1200L): List<DiscoveredDevice> {
        val waitMs = timeoutMs.coerceIn(300L, 4000L)
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val payload = JSONObject().put("magic", MAGIC_DISCOVER).toString().toByteArray(Charsets.UTF_8)
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 180
            }
            val targets = broadcastTargets()
            // 发两轮减少 Wi-Fi UDP 丢包
            for (attempt in 0..1) {
                for (target in targets) {
                    try {
                        sock.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                    } catch (_: Exception) {
                    }
                }
                val sliceEnd = System.currentTimeMillis() + (waitMs / 2)
                val buf = ByteArray(MAX_PACKET_BYTES)
                while (System.currentTimeMillis() < sliceEnd) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                        val senderIp = pkt.address?.hostAddress.orEmpty()
                        val dev = parsePeerPacket(pkt.data, pkt.length, senderIp)
                        if (dev != null) found[dev.id] = dev
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) { }
        }
        return found.values.sortedWith(compareBy<DiscoveredDevice> { it.name.lowercase(Locale.ROOT) }.thenBy { it.host })
    }

    /** 探测指定主机是否运行 StudyOnTV（`GET /api/hello`，内网直连不走代理、统一禁重定向）。 */
    fun probeHello(
        ip: String,
        port: Int = DEFAULT_HTTP_PORT,
        timeoutMs: Int = 2500,
        scheme: String = "http"
    ): DiscoveredDevice? {
        val target = parseManualHost("$scheme://${formatHostPort(ip, port, scheme)}", port) ?: return null
        return probeHelloTarget(target, timeoutMs)
    }

    fun probeHelloTarget(target: ParsedHost, timeoutMs: Int = 2500): DiscoveredDevice? {
        if (BuildConfig.LOCAL_ONLY && !isPrivateLanIp(target.ip)) return null
        val url = URL("${target.baseUrl}/api/hello")
        val rawConn = if (isPrivateLanIp(target.ip)) url.openConnection(Proxy.NO_PROXY) else url.openConnection()
        val conn = (rawConn as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            if (json.optString("app") != "StudyOnTV") return null
            DiscoveredDevice(
                id = json.optString("id").trim().take(32).ifEmpty { target.host },
                name = json.optString("name").trim().take(32).ifEmpty { target.ip },
                ip = target.ip,
                port = target.port,
                version = json.optString("version").trim().take(24),
                isTv = json.optBoolean("isTv", true),
                questions = json.optInt("questions", 0).coerceAtLeast(0),
                lastSeenAt = System.currentTimeMillis(),
                scheme = target.scheme
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 并发扫描本机所在 `/24` 局域网中开放无线 ADB（默认 TCP 5555）的设备，
     * 并附带探测是否已安装 StudyOnTV（8099）以便前端标注「可升级/修权限」。
     */
    fun scanAdbDevices(port: Int = DEFAULT_ADB_PORT, connectTimeoutMs: Int = 260): List<AdbCandidate> {
        val candidates = buildSubnetScanIps(localPrivateIpv4Addresses())
        if (candidates.isEmpty()) return emptyList()
        val found = CopyOnWriteArrayList<AdbCandidate>()
        val pool = Executors.newFixedThreadPool(32)
        try {
            for (ip in candidates) {
                pool.execute {
                    val open = try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (open) {
                        val hello = probeHello(ip, DEFAULT_HTTP_PORT, timeoutMs = 600)
                        found.add(
                            AdbCandidate(
                                ip = ip,
                                port = port,
                                studyOnTvName = hello?.name.orEmpty(),
                                studyOnTvVersion = hello?.version.orEmpty()
                            )
                        )
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(8, TimeUnit.SECONDS)
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        return found.sortedBy { ipSortKey(it.ip) }
    }

    private fun ipSortKey(ip: String): Long {
        val p = ip.split('.')
        if (p.size != 4) return 0L
        return p.fold(0L) { acc, s -> (acc shl 8) or (s.toLongOrNull() ?: 0L) }
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && isPrivateLanIp(addr.hostAddress.orEmpty())) {
                        ia.broadcast?.let { targets.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return targets
    }
}
