package com.studyontv.parent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 家长端设备清单持久化：
 * 按 `deviceId` 自动跟踪路由器重启后变化的局域网 IP，支持收藏置顶与离线保留。
 */
object DeviceStore {
    private const val PREF_NAME = "sotv_parent_devices"
    private const val KEY_DEVICES = "devices_v1"
    private const val PREF_SESSIONS = "sotv_parent_sessions"
    private val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")

    internal fun isValidSessionToken(token: String): Boolean = TOKEN_REGEX.matches(token.trim())

    data class SavedDevice(
        val id: String,
        val name: String,
        val ip: String,
        val port: Int = LanNet.DEFAULT_HTTP_PORT,
        val version: String = "",
        val isTv: Boolean = true,
        val questions: Int = 0,
        val pinned: Boolean = false,
        val lastSeenAt: Long = 0L,
        val scheme: String = "http"
    ) {
        val host: String get() = LanNet.formatHostPort(ip, port, scheme)
        val baseUrl: String get() = "$scheme://$host"
        val adminUrl: String get() = "$baseUrl/admin/"

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("ip", ip)
            .put("port", port)
            .put("scheme", scheme)
            .put("version", version)
            .put("isTv", isTv)
            .put("questions", questions)
            .put("pinned", pinned)
            .put("lastSeenAt", lastSeenAt)

        companion object {
            fun fromJson(obj: JSONObject): SavedDevice? {
                val ip = obj.optString("ip").trim()
                val scheme = obj.optString("scheme", "http").trim().lowercase(Locale.US).let {
                    if (it == "https") "https" else "http"
                }
                val port = obj.optInt("port", if (scheme == "https") 443 else LanNet.DEFAULT_HTTP_PORT)
                val parsed = LanNet.parseManualHost("$scheme://${LanNet.formatHostPort(ip, port, scheme)}", port)
                    ?: return null
                val id = obj.optString("id").trim().ifEmpty { parsed.host }
                val name = obj.optString("name").trim().ifEmpty { parsed.ip }
                return SavedDevice(
                    id = id,
                    name = name,
                    ip = parsed.ip,
                    port = parsed.port,
                    version = obj.optString("version").trim(),
                    isTv = obj.optBoolean("isTv", true),
                    questions = obj.optInt("questions", 0).coerceAtLeast(0),
                    pinned = obj.optBoolean("pinned", false),
                    lastSeenAt = obj.optLong("lastSeenAt", 0L),
                    scheme = parsed.scheme
                )
            }
        }
    }

    /**
     * 纯函数合并逻辑：
     * - 同 `id` 的设备发现新 IP 时自动更新地址，保留用户的 `pinned` 状态；
     * - 不同 `id` 但占用了同一地址时清理旧条目（例如设备重装）；
     * - 排序规则：置顶优先 -> 最近活跃优先 -> 名称字母序。
     */
    internal fun mergeDiscovered(
        existing: List<SavedDevice>,
        discovered: List<LanNet.DiscoveredDevice>
    ): List<SavedDevice> {
        val byId = LinkedHashMap<String, SavedDevice>()
        for (item in existing) byId[item.id] = item
        for (d in discovered) {
            val parsed = LanNet.parseManualHost("${d.scheme}://${LanNet.formatHostPort(d.ip, d.port, d.scheme)}", d.port)
                ?: continue
            val prevById = byId[d.id]
            val prevByHost = byId.values.firstOrNull {
                it.ip == parsed.ip && it.port == parsed.port && it.scheme == parsed.scheme && it.id != d.id
            }
            if (prevByHost != null) byId.remove(prevByHost.id)
            val pinned = prevById?.pinned ?: prevByHost?.pinned ?: false
            byId[d.id] = SavedDevice(
                id = d.id,
                name = d.name.ifBlank { prevById?.name ?: parsed.ip },
                ip = parsed.ip,
                port = parsed.port,
                version = d.version.ifBlank { prevById?.version.orEmpty() },
                isTv = d.isTv,
                questions = d.questions,
                pinned = pinned,
                lastSeenAt = maxOf(d.lastSeenAt, prevById?.lastSeenAt ?: 0L),
                scheme = parsed.scheme
            )
        }
        return sortDevices(byId.values.toList())
    }

    internal fun sortDevices(list: List<SavedDevice>): List<SavedDevice> =
        list.sortedWith(
            compareByDescending<SavedDevice> { it.pinned }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )

    fun load(context: Context): List<SavedDevice> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<SavedDevice>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { SavedDevice.fromJson(it) }?.let { list.add(it) }
            }
            sortDevices(list)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(context: Context, devices: List<SavedDevice>) {
        val arr = JSONArray()
        for (d in sortDevices(devices)) arr.put(d.toJson())
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    fun recordDiscovered(context: Context, discovered: List<LanNet.DiscoveredDevice>): List<SavedDevice> {
        val merged = mergeDiscovered(load(context), discovered)
        saveAll(context, merged)
        return merged
    }

    fun togglePin(context: Context, id: String): List<SavedDevice> {
        val updated = load(context).map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        saveAll(context, updated)
        return sortDevices(updated)
    }

    fun remove(context: Context, id: String): List<SavedDevice> {
        val updated = load(context).filterNot { it.id == id }
        saveAll(context, updated)
        saveSessionToken(context, id, "")
        return updated
    }

    /** 按 deviceId 持久化 30 天免密会话令牌（不存明文密码），路由器换 IP 后依然免重登。 */
    fun saveSessionToken(context: Context, deviceId: String, token: String) {
        val key = deviceId.trim()
        if (key.isEmpty()) return
        val clean = token.trim()
        val editor = context.getSharedPreferences(PREF_SESSIONS, Context.MODE_PRIVATE).edit()
        if (isValidSessionToken(clean)) editor.putString(key, clean) else editor.remove(key)
        editor.apply()
    }

    fun loadSessionToken(context: Context, deviceId: String): String {
        val key = deviceId.trim()
        if (key.isEmpty()) return ""
        val saved = context.getSharedPreferences(PREF_SESSIONS, Context.MODE_PRIVATE)
            .getString(key, "").orEmpty().trim()
        return if (isValidSessionToken(saved)) saved else ""
    }

    fun hasSessionToken(context: Context, deviceId: String): Boolean =
        loadSessionToken(context, deviceId).isNotEmpty()
}
