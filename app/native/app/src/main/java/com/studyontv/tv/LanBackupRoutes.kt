package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale

/** 备份导出恢复与局域网多设备发现、探测、一键同步路由。 */
internal fun LanServer.registerBackupRoutes() {
    router.get("/api/backup") { ctx ->
        DeviceRuntime.learning.flushUsage()
        val includeQuestions = ctx.query["includeQuestions"] != "0"
        json(ctx, 200, store.exportBackup(includeQuestions))
    }
    router.post("/api/backup/restore") { ctx ->
        DeviceRuntime.learning.flushUsage()
        val input = JSONObject(ctx.body)
        val backup = input.optJSONObject("backup") ?: input
        val mode = input.optString("mode", "replace")
        val scopesArr = input.optJSONArray("scopes")
        val scopes: Set<String>? = if (scopesArr != null) {
            val set = LinkedHashSet<String>()
            for (i in 0 until scopesArr.length()) {
                val s = scopesArr.optString(i).trim()
                if (s.isNotEmpty()) set.add(s)
            }
            set
        } else null
        val restored = store.restoreBackup(backup, mode, scopes)
        DeviceRuntime.learning.status()
        GateService.refreshNow()
        json(ctx, 200, restored)
    }
    router.get("/api/peers") { ctx ->
        val doScan = ctx.query["scan"] == "1" || ctx.query["scan"].equals("true", ignoreCase = true)
        val peerList = if (doScan) PeerDiscovery.scan(700L) else PeerDiscovery.listPeers()
        val selfIp = localIp() ?: ""
        val selfHost = if (selfIp.isNotEmpty()) "$selfIp:$boundPort" else ""
        val selfJson = JSONObject()
            .put("id", Prefs.deviceId(context))
            .put("name", Prefs.deviceName(context))
            .put("ip", selfIp)
            .put("port", boundPort)
            .put("host", selfHost)
            .put("version", BuildConfig.VERSION_NAME)
            .put("isTv", BuildConfig.FLAVOR == "tv")
            .put("questions", store.countQuestions())
        val peersArr = JSONArray()
        val nowMs = System.currentTimeMillis()
        for (p in peerList) peersArr.put(p.toJson(nowMs))
        json(ctx, 200, JSONObject().put("self", selfJson).put("peers", peersArr))
    }
    router.post("/api/peers/probe") { ctx ->
        val input = JSONObject(ctx.body)
        val rawHost = input.optString("host").trim()
        val parsed = PeerDiscovery.parseManualPeerHost(rawHost)
            ?: return@post json(
                ctx,
                400,
                err("invalid_host").put(
                    "message",
                    if (LocalOnlyGuard.enabled) "仅本地隐私版只支持局域网私有 IPv4 地址（10.x / 172.16-31.x / 192.168.x）及合法端口（1024–65535）"
                    else "请输入有效的设备 IP 或域名（如 192.168.1.20:8099、100.64.x.x 或 https://tv.example.com）"
                )
            )
        val probed = probePeerHello(parsed)
        if (probed == null) {
            return@post json(ctx, 200, JSONObject()
                .put("ok", false)
                .put("online", false)
                .put("host", parsed.host)
                .put("message", "无法连接该设备，请确认设备已开机且网络可达"))
        }
        PeerDiscovery.recordProbedPeer(probed)
        val peerJson = probed.toJson()
        json(ctx, 200, JSONObject()
            .put("ok", true)
            .put("online", true)
            .put("host", probed.host)
            .put("id", probed.id)
            .put("name", probed.name)
            .put("ip", probed.ip)
            .put("port", probed.port)
            .put("version", probed.version)
            .put("isTv", probed.isTv)
            .put("questions", probed.questions)
            .put("peer", peerJson))
    }
    router.post("/api/peers/sync") { ctx ->
        val input = JSONObject(ctx.body)
        val targets = input.optJSONArray("targets")
        require(targets != null && targets.length() in 1..20) { "请选择 1–20 台目标设备" }
        val scopesRaw = mutableListOf<String>()
        input.optJSONArray("scopes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) scopesRaw.add(s)
            }
        }
        val scopes = LocalStore.normalizeSyncScopes(scopesRaw)
        require(scopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }
        val mode = input.optString("mode", "merge").trim().lowercase(Locale.US)
        require(mode in listOf("merge", "replace")) { "同步模式仅支持 merge 或 replace" }
        val defaultPassword = input.optString("defaultPassword", "")

        DeviceRuntime.learning.flushUsage()
        val scopedPayload = store.exportScopedSync(scopes)
        val selfIp = localIp()
        val results = JSONArray()
        var allOk = true

        for (i in 0 until targets.length()) {
            val t = targets.optJSONObject(i) ?: continue
            val rawHost = t.optString("host").trim()
            val hintedName = t.optString("name").trim()
            val parsed = PeerDiscovery.parseManualPeerHost(rawHost)
            if (parsed == null) {
                allOk = false
                results.put(JSONObject()
                    .put("host", rawHost)
                    .put("name", hintedName)
                    .put("ok", false)
                    .put(
                        "message",
                        if (LocalOnlyGuard.enabled) "仅本地隐私版只支持局域网私有 IPv4 地址及合法端口（1024–65535）"
                        else "目标设备地址格式无效"
                    ))
                continue
            }
            if (selfIp != null && parsed.ip == selfIp && parsed.port == boundPort) {
                allOk = false
                results.put(JSONObject()
                    .put("host", parsed.host)
                    .put("name", Prefs.deviceName(context))
                    .put("ok", false)
                    .put("message", "无需同步到当前本机设备"))
                continue
            }
            val knownPeer = PeerDiscovery.listPeers().firstOrNull { it.ip == parsed.ip && it.port == parsed.port }
            val targetName = hintedName.ifEmpty { knownPeer?.name ?: parsed.host }
            val password = t.optString("password", "").ifEmpty { defaultPassword }
            if (password.isEmpty()) {
                allOk = false
                results.put(JSONObject()
                    .put("host", parsed.host)
                    .put("name", targetName)
                    .put("ok", false)
                    .put("message", "需要输入该台设备的管理密码"))
                continue
            }
            val syncResult = syncToPeer(parsed, targetName, password, scopedPayload, scopes, mode)
            if (!syncResult.optBoolean("ok")) allOk = false
            results.put(syncResult)
        }
        json(ctx, 200, JSONObject().put("ok", allOk).put("results", results))
    }
}

private fun probePeerHello(target: PeerDiscovery.ParsedLanHost): PeerDiscovery.PeerInfo? {
    val (code, body) = lanHttpRequest(
        target = target,
        method = "GET",
        path = "/api/hello",
        connectTimeoutMs = 2500,
        readTimeoutMs = 3000
    ) ?: return null
    if (code != 200) return null
    val obj = try { JSONObject(body) } catch (_: Exception) { return null }
    if (obj.optString("app") != "StudyOnTV") return null
    val id = obj.optString("id").trim().take(32).ifEmpty { target.host }
    val name = obj.optString("name").trim().take(32).ifEmpty { target.host }
    val version = obj.optString("version").trim().take(24)
    val isTv = obj.optBoolean("isTv", true)
    val questions = obj.optInt("questions", 0).coerceAtLeast(0)
    return PeerDiscovery.PeerInfo(
        id = id,
        name = name,
        ip = target.ip,
        port = target.port,
        version = version,
        isTv = isTv,
        questions = questions,
        lastSeenAt = System.currentTimeMillis(),
        scheme = target.scheme
    )
}

private fun syncToPeer(
    target: PeerDiscovery.ParsedLanHost,
    initialName: String,
    password: String,
    scopedPayload: JSONObject,
    scopes: Set<String>,
    mode: String
): JSONObject {
    val resolvedName = try {
        probePeerHello(target)?.name?.takeIf { it.isNotBlank() } ?: initialName
    } catch (_: Exception) {
        initialName
    }

    val authBody = JSONObject()
        .put("passphrase", password)
        .put("remember", false)
        .toString()
    val authResp = lanHttpRequest(
        target = target,
        method = "POST",
        path = "/api/auth",
        body = authBody,
        connectTimeoutMs = 3000,
        readTimeoutMs = 5000
    ) ?: return JSONObject()
        .put("host", target.host)
        .put("name", resolvedName)
        .put("ok", false)
        .put("message", "无法连接目标设备，请确认设备已开机且在同一局域网")

    if (authResp.first == 401) {
        return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", false)
            .put("message", "管理密码错误，需要输入该台设备的管理密码")
    }
    if (authResp.first !in 200..299) {
        val remoteMsg = try { JSONObject(authResp.second).optString("message") } catch (_: Exception) { "" }
        return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", false)
            .put("message", remoteMsg.ifBlank { "目标设备鉴权失败（HTTP ${authResp.first}）" })
    }
    val remoteToken = try { JSONObject(authResp.second).optString("token") } catch (_: Exception) { "" }
    if (remoteToken.isBlank()) {
        return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", false)
            .put("message", "目标设备未返回有效会话令牌")
    }

    val restoreBody = JSONObject()
        .put("backup", scopedPayload)
        .put("mode", mode)
        .put("scopes", JSONArray(scopes.sorted()))
        .toString()
    val restoreResp = lanHttpRequest(
        target = target,
        method = "POST",
        path = "/api/backup/restore",
        body = restoreBody,
        bearerToken = remoteToken,
        connectTimeoutMs = 4000,
        readTimeoutMs = 20000
    ) ?: return JSONObject()
        .put("host", target.host)
        .put("name", resolvedName)
        .put("ok", false)
        .put("message", "同步传输中断，请稍后重试")

    if (restoreResp.first !in 200..299) {
        val remoteMsg = try { JSONObject(restoreResp.second).optString("message") } catch (_: Exception) { "" }
        return JSONObject()
            .put("host", target.host)
            .put("name", resolvedName)
            .put("ok", false)
            .put("message", remoteMsg.ifBlank { "目标设备同步失败（HTTP ${restoreResp.first}）" })
    }
    val restoredJson = try { JSONObject(restoreResp.second) } catch (_: Exception) { JSONObject() }
    val syncedQ = restoredJson.optInt("syncedQuestions", restoredJson.optInt("questions", 0))
    val totalQ = restoredJson.optInt("questions", syncedQ)
    val summary = LanServer.formatSyncSuccessMessage(scopes, mode, syncedQ, totalQ)
    return JSONObject()
        .put("host", target.host)
        .put("name", resolvedName)
        .put("ok", true)
        .put("message", summary)
}

private fun lanHttpRequest(
    target: PeerDiscovery.ParsedLanHost,
    method: String,
    path: String,
    body: String? = null,
    bearerToken: String? = null,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 5000
): Pair<Int, String>? {
    if (LocalOnlyGuard.enabled && !PeerDiscovery.isPrivateLanIp(target.ip)) return null
    if (target.scheme != "http" && target.scheme != "https") return null
    if (target.port !in 1..65535) return null
    var conn: HttpURLConnection? = null
    return try {
        val url = URL("${target.baseUrl}$path")
        val rawConn = if (PeerDiscovery.isPrivateLanIp(target.ip)) url.openConnection(Proxy.NO_PROXY) else url.openConnection()
        conn = (rawConn as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrEmpty()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        val text = if (stream != null) {
            stream.use { ins ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                var total = 0
                while (total < 65_536) {
                    val n = ins.read(chunk)
                    if (n <= 0) break
                    val take = minOf(n, 65_536 - total)
                    buf.write(chunk, 0, take)
                    total += take
                }
                buf.toString("UTF-8")
            }
        } else ""
        code to text
    } catch (_: Exception) {
        null
    } finally {
        try { conn?.disconnect() } catch (_: Exception) { }
    }
}
