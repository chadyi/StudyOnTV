package com.studyontv.parent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 家长端独立 App 自动检查更新（零新增权限，保持 INTERNET + ACCESS_NETWORK_STATE 红线）：
 * 发现新版本后在首页弹出轻提示卡片，点击跳转到蒲公英（或 GitHub Release）由手机浏览器手动下载覆盖安装。
 */
object ParentUpdater {
    private const val PREFS = "sotv_parent_update"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_CACHED_JSON = "cached_info"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"
    private const val KEY_PGYER_KEY = "pgyer_key"
    private const val KEY_PGYER_URL = "pgyer_url"

    private const val CACHE_MS = 6L * 60 * 60 * 1000L
    private const val PGYER_CHECK_URL = "https://www.pgyer.com/apiv1/update/check"
    private const val PGYER_SDK_API_KEY = "305092bc73c180b55c26012a94809131"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; StudyOnTV-Parent) AppleWebKit/537.36"
    internal const val RELEASES_FALLBACK_URL = "https://github.com/chadyi/StudyOnTV/releases/latest"

    private val MANIFEST_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/chadyi/StudyOnTV@main/update.json",
        "https://raw.githubusercontent.com/chadyi/StudyOnTV/main/update.json"
    )

    data class UpdateInfo(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val pageUrl: String,
        val fallbackUrl: String,
        val pgyerKey: String = "",
        val source: String = ""
    ) {
        val isPgyerPage: Boolean get() = pageUrl.startsWith("https://www.pgyer.com/")
    }

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDismissed(context: Context, version: String): Boolean =
        version.isNotBlank() && sp(context).getString(KEY_DISMISSED_VERSION, "") == version.trim()

    fun dismiss(context: Context, version: String) {
        sp(context).edit().putString(KEY_DISMISSED_VERSION, version.trim()).apply()
    }

    fun check(context: Context, forceRefresh: Boolean = false, onResult: (UpdateInfo?, String?) -> Unit) {
        if (BuildConfig.LOCAL_ONLY) {
            onResult(null, "仅本地版本未启用在线检查更新")
            return
        }
        val app = context.applicationContext
        val prefs = sp(app)
        if (!forceRefresh && System.currentTimeMillis() - prefs.getLong(KEY_CHECKED_AT, 0L) < CACHE_MS) {
            val cached = readCached(app)
            val newer = cached?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            onResult(newer, null)
            return
        }
        Thread {
            val (info, err) = checkNow(app)
            onResult(info, err)
        }.apply { name = "parent-update-check"; isDaemon = true }.start()
    }

    private fun checkNow(context: Context): Pair<UpdateInfo?, String?> {
        val prefs = sp(context)
        val savedKey = prefs.getString(KEY_PGYER_KEY, "").orEmpty().trim()
        val savedPgyerUrl = prefs.getString(KEY_PGYER_URL, "").orEmpty().trim()
        val errors = ArrayList<String>()

        // 已有蒲公英 appKey 时优先直查蒲公英（国内直连快、无 CDN 延迟）
        if (savedKey.isNotEmpty()) {
            try {
                val pgyerRes = fetchFromPgyer(savedKey, savedPgyerUrl, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                if (pgyerRes != null) {
                    saveCheckResult(context, pgyerRes)
                    ParentDiag.info("Update", "pgyer found v${pgyerRes.version}")
                    return pgyerRes to null
                }
            } catch (e: Exception) {
                errors.add("蒲公英：${e.message}")
                ParentDiag.warn("Update", "pgyer check failed: ${e.message}")
            }
        }

        // 回退读取 update.json（jsDelivr CDN -> raw.githubusercontent）
        for (url in MANIFEST_URLS) {
            try {
                val text = httpGet(url)
                val parsed = parseManifestUpdate(text, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                cacheManifestPgyerMeta(context, text)
                saveCheckResult(context, parsed)
                ParentDiag.info("Update", "manifest ok host=${URL(url).host} newer=${parsed?.version ?: "none"}")
                return parsed to null
            } catch (e: Exception) {
                errors.add("${URL(url).host}：${e.message}")
                ParentDiag.warn("Update", "manifest failed url=$url err=${e.message}")
            }
        }

        // 若蒲公英接口已明确返回「无新版」且未抛错，则无需报失败
        if (savedKey.isNotEmpty() && errors.none { it.startsWith("蒲公英：") }) {
            saveCheckResult(context, null)
            return null to null
        }
        return null to errors.firstOrNull().orEmpty().ifBlank { "检查更新失败，请检查网络" }
    }

    private fun fetchFromPgyer(
        appKey: String,
        savedPageUrl: String,
        currentVersion: String,
        currentVersionCode: Int
    ): UpdateInfo? {
        val form = listOf(
            "_api_key" to PGYER_SDK_API_KEY,
            "agKey" to appKey,
            "version" to currentVersion,
            "build" to currentVersionCode.toString(),
            "sdkapi" to android.os.Build.VERSION.SDK_INT.toString()
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val conn = (URL(PGYER_CHECK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            require(code in 200..299) { "HTTP $code" }
            val root = JSONObject(body)
            require(root.optInt("code", -1) == 0) { root.optString("message").ifEmpty { "接口返回失败" } }
            val data = root.optJSONObject("data") ?: return null
            if (!data.optBoolean("haveNewVersion", false)) return null
            val ver = data.optString("version").trim().removePrefix("v")
            if (ver.isEmpty() || !isNewer(ver, currentVersion)) return null
            val dl = data.optString("downloadURL").trim().replaceFirst("http://", "https://")
            val page = resolveDownloadUrl(savedPageUrl, dl)
            UpdateInfo(
                version = ver,
                versionCode = data.optString("build").toIntOrNull() ?: 0,
                notes = data.optString("releaseNote").trim(),
                pageUrl = page,
                fallbackUrl = RELEASES_FALLBACK_URL,
                pgyerKey = appKey,
                source = "蒲公英"
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun cacheManifestPgyerMeta(context: Context, jsonText: String) {
        try {
            val parent = JSONObject(jsonText).optJSONObject("assets")?.optJSONObject("parent") ?: return
            val key = parent.optString("pgyerKey").trim()
            val url = parent.optString("pgyerUrl").trim()
            val edit = sp(context).edit()
            if (key.isNotEmpty()) edit.putString(KEY_PGYER_KEY, key)
            if (url.startsWith("https://www.pgyer.com/")) edit.putString(KEY_PGYER_URL, url)
            edit.apply()
        } catch (_: Exception) { }
    }

    private fun saveCheckResult(context: Context, info: UpdateInfo?) {
        val edit = sp(context).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis())
        if (info == null) {
            edit.remove(KEY_CACHED_JSON)
        } else {
            val obj = JSONObject()
                .put("version", info.version)
                .put("versionCode", info.versionCode)
                .put("notes", info.notes)
                .put("pageUrl", info.pageUrl)
                .put("fallbackUrl", info.fallbackUrl)
                .put("pgyerKey", info.pgyerKey)
                .put("source", info.source)
            edit.putString(KEY_CACHED_JSON, obj.toString())
        }
        edit.apply()
    }

    private fun readCached(context: Context): UpdateInfo? {
        val raw = sp(context).getString(KEY_CACHED_JSON, "").orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            UpdateInfo(
                version = o.optString("version"),
                versionCode = o.optInt("versionCode", 0),
                notes = o.optString("notes"),
                pageUrl = o.optString("pageUrl"),
                fallbackUrl = o.optString("fallbackUrl", RELEASES_FALLBACK_URL),
                pgyerKey = o.optString("pgyerKey"),
                source = o.optString("source")
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun resolveDownloadUrl(pgyerUrl: String, assetUrl: String): String {
        val pgyer = pgyerUrl.trim()
        if (pgyer.startsWith("https://")) return pgyer
        val asset = assetUrl.trim()
        if (asset.startsWith("https://")) return asset
        return RELEASES_FALLBACK_URL
    }

    internal fun parseManifestUpdate(
        jsonText: String,
        currentVersion: String,
        currentVersionCode: Int
    ): UpdateInfo? {
        val root = JSONObject(jsonText)
        val ver = root.optString("version").trim().removePrefix("v")
        val code = root.optInt("versionCode", 0)
        if (ver.isEmpty()) return null
        if (!isNewer(ver, currentVersion) && !(ver == currentVersion && code > currentVersionCode)) return null
        val parent = root.optJSONObject("assets")?.optJSONObject("parent")
        val pgyerUrl = parent?.optString("pgyerUrl").orEmpty().trim()
        val assetUrl = parent?.optString("url").orEmpty().trim()
        val pgyerKey = parent?.optString("pgyerKey").orEmpty().trim()
        val primary = resolveDownloadUrl(pgyerUrl, assetUrl)
        val fallback = if (assetUrl.startsWith("https://") && assetUrl != primary) assetUrl else RELEASES_FALLBACK_URL
        return UpdateInfo(
            version = ver,
            versionCode = code,
            notes = root.optString("notes").trim(),
            pageUrl = primary,
            fallbackUrl = fallback,
            pgyerKey = pgyerKey,
            source = if (primary.startsWith("https://www.pgyer.com/")) "蒲公英" else "GitHub Release"
        )
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        val remoteClean = latest.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val localClean = current.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (remoteClean.isEmpty()) return false
        val remoteCore = remoteClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val localCore = localClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteCore.size, localCore.size)) {
            val x = remoteCore.getOrElse(i) { 0 }
            val y = localCore.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val remotePre = remoteClean.substringAfter('-', "")
        val localPre = localClean.substringAfter('-', "")
        if (remotePre.isEmpty() && localPre.isEmpty()) return false
        if (remotePre.isEmpty() && localPre.isNotEmpty()) return true
        if (remotePre.isNotEmpty() && localPre.isEmpty()) return false
        val rParts = remotePre.split('.', '-')
        val lParts = localPre.split('.', '-')
        for (i in 0 until minOf(rParts.size, lParts.size)) {
            val rp = rParts[i]
            val lp = lParts[i]
            if (rp == lp) continue
            val rn = rp.toIntOrNull()
            val ln = lp.toIntOrNull()
            return when {
                rn != null && ln != null -> rn > ln
                rn != null -> false
                ln != null -> true
                else -> rp > lp
            }
        }
        return rParts.size > lParts.size
    }

    fun openDownloadPage(activity: Activity, info: UpdateInfo) {
        val targets = listOf(info.pageUrl, info.fallbackUrl, RELEASES_FALLBACK_URL)
            .map { it.trim() }
            .filter { it.startsWith("https://") }
            .distinct()
        for (target in targets) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                ParentDiag.info("Update", "opened download page host=${URL(target).host}")
                return
            } catch (e: Exception) {
                ParentDiag.warn("Update", "open browser failed for $target: ${e.javaClass.simpleName}")
            }
        }
        val copyUrl = targets.firstOrNull() ?: RELEASES_FALLBACK_URL
        ParentUi.copyToClipboard(activity, "StudyOnTV Parent Update", copyUrl, "未找到浏览器，已复制下载链接")
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val cleanNotes = info.notes.lineSequence()
            .filter { !it.contains("[force]", true) && it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { "包含体验优化与问题修复。" }
        val actionLabel = if (info.isPgyerPage) "去蒲公英下载" else "去浏览器下载"
        val hint = if (info.isPgyerPage) {
            "点击将打开蒲公英下载页，下载后直接覆盖安装即可（已保存设备与登录状态不会丢失）。"
        } else {
            "点击将打开浏览器下载最新版本，下载后直接覆盖安装即可（已保存设备与登录状态不会丢失）。"
        }
        AlertDialog.Builder(activity)
            .setTitle("发现家长端新版本 v${info.version}")
            .setMessage("当前 v${BuildConfig.VERSION_NAME}\n\n$cleanNotes\n\n$hint")
            .setPositiveButton(actionLabel) { _, _ -> openDownloadPage(activity, info) }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    fun buildUpdateCard(activity: Activity, p: ParentUi.Palette, info: UpdateInfo, onDismiss: () -> Unit): View {
        val card = ParentUi.card(activity, p, paddingDp = 14)
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(activity).apply {
            text = "新版本 v${info.version} 可用"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(ParentUi.badge(activity, info.source.ifBlank { "可更新" }, p.accentSoft, p.accentText))
        card.addView(top)

        val firstLine = info.notes.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.equals("v${info.version}", true) }
            ?: "点击跳转到下载页，覆盖安装不会丢失已保存的设备。"
        card.addView(TextView(activity).apply {
            text = firstLine
            textSize = 12.5f
            maxLines = 2
            setTextColor(p.textMuted)
            setPadding(0, ParentUi.dp(activity, 5), 0, ParentUi.dp(activity, 10))
        })

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnText = if (info.isPgyerPage) "去蒲公英下载" else "去下载更新"
        actions.addView(ParentUi.primaryButton(activity, p, btnText) {
            openDownloadPage(activity, info)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
        })
        actions.addView(ParentUi.spacer(activity, 1, 8))
        actions.addView(ParentUi.secondaryButton(activity, p, "更新内容") {
            showUpdateDialog(activity, info)
        })
        actions.addView(ParentUi.spacer(activity, 1, 8))
        actions.addView(ParentUi.secondaryButton(activity, p, "忽略") {
            dismiss(activity, info.version)
            onDismiss()
        })
        card.addView(actions)
        return card
    }
}
