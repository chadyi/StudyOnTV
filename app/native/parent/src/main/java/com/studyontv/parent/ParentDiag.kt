package com.studyontv.parent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 家长端独立 App 的运行日志、崩溃捕获与自动上报（零三方依赖，仅使用已有网络权限）：
 * 与设备端共用同一套 OSS / Worker 协议与 `logs/<YYYYMMDD>/` 目录结构，
 * 文件名带 `parent-runtime` / `parent-crash` 标识；严禁记录密码、会话令牌或私钥。
 */
object ParentDiag {
    private const val PREFS = "sotv_parent_diag"
    private const val KEY_CONSENT = "telemetry_consent"
    private const val KEY_LAST_UPLOAD = "last_telemetry_upload_at"
    private const val LOG_FILE = "parent.log"
    private const val CRASH_PENDING_FILE = "parent_crash_pending"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP = 2
    private const val RUNTIME_UPLOAD_INTERVAL_MS = 6L * 60 * 60 * 1000L

    private val initialized = AtomicBoolean(false)
    private val runtimeUploading = AtomicBoolean(false)
    private val crashUploading = AtomicBoolean(false)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (initialized.compareAndSet(false, true)) {
            installCrashHandler(app)
            val savedCount = try { DeviceStore.load(app).size } catch (_: Exception) { 0 }
            info(
                "App",
                "parent start version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                    "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} savedDevices=$savedCount"
            )
            uploadPendingCrash(app)
            uploadRuntimeLogsIfDue(app)
        }
    }

    fun telemetryEnabled(context: Context): Boolean {
        if (BuildConfig.LOCAL_ONLY) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, true)
    }

    fun setTelemetryEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSENT, !BuildConfig.LOCAL_ONLY && enabled).apply()
    }

    private fun lastUploadAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPLOAD, 0L)

    private fun setLastUploadAt(context: Context, ts: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_UPLOAD, ts).apply()
    }

    private fun logDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    @Synchronized
    fun write(level: String, tag: String, message: String) {
        try {
            val ctx = appContext ?: return
            rotateInDir(logDir(ctx))
            val line = "${timeFmt.format(Date())} $level/$tag ${Thread.currentThread().name}: $message\n"
            File(logDir(ctx), LOG_FILE).appendText(line)
            val prio = when (level) {
                "E" -> android.util.Log.ERROR
                "W" -> android.util.Log.WARN
                else -> android.util.Log.INFO
            }
            android.util.Log.println(prio, "StudyOnTVParent-$tag", message)
        } catch (_: Throwable) { }
    }

    fun info(tag: String, message: String) = write("I", tag, message)
    fun warn(tag: String, message: String) = write("W", tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        write("E", tag, message + suffix)
        if (throwable != null) write("E", tag, throwable.stackTraceToString().take(8000))
    }

    internal fun rotateInDir(dir: File, maxBytes: Long = MAX_BYTES, keep: Int = KEEP) {
        val current = File(dir, LOG_FILE)
        if (!current.exists() || current.length() < maxBytes) return
        if (keep <= 0) {
            current.delete()
            return
        }
        val oldest = File(dir, "$LOG_FILE.$keep")
        if (oldest.exists()) oldest.delete()
        for (i in keep - 1 downTo 1) {
            val from = File(dir, "$LOG_FILE.$i")
            val to = File(dir, "$LOG_FILE.${i + 1}")
            if (from.exists()) {
                if (to.exists()) to.delete()
                from.renameTo(to)
            }
        }
        val firstArchive = File(dir, "$LOG_FILE.1")
        if (firstArchive.exists()) firstArchive.delete()
        current.renameTo(firstArchive)
    }

    private fun installCrashHandler(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val summary = buildString {
                    append(throwable.javaClass.name)
                    throwable.message?.let { append(": ").append(it.take(240)) }
                    append("\n")
                    throwable.stackTrace.take(10).forEach { append("  at $it\n") }
                }
                File(app.filesDir, CRASH_PENDING_FILE).writeText(summary)
                write("E", "Crash", "thread=${thread.name} ${throwable.javaClass.name}: ${throwable.message}")
                write("E", "Crash", throwable.stackTraceToString().take(12000))
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, throwable)
        }
    }

    internal fun isAliyunOssEndpoint(endpoint: String): Boolean {
        val host = try { URL(endpoint).host.lowercase(Locale.US) } catch (_: Exception) { return false }
        return host.endsWith(".aliyuncs.com") && host.contains(".oss-")
    }

    private fun sanitizeTokenPart(raw: String, fallback: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(24).ifEmpty { fallback }

    internal fun ossObjectKey(kind: String, nowMillis: Long = System.currentTimeMillis()): String {
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
        val day = dayFmt.format(Date(nowMillis))
        val brand = sanitizeTokenPart(Build.MANUFACTURER ?: "", "unknown")
        val model = sanitizeTokenPart(Build.MODEL ?: "", "device")
        val ver = sanitizeTokenPart(BuildConfig.VERSION_NAME, "0")
        val rand = UUID.randomUUID().toString().replace("-", "").take(6)
        return "logs/$day/${nowMillis / 1000L}_${brand}_${model}_v${ver}_${kind}_$rand.zip"
    }

    private fun ossSignV1(
        ak: String,
        sk: String,
        method: String,
        contentType: String,
        dateHeader: String,
        canonicalResource: String
    ): String {
        val stringToSign = "$method\n\n$contentType\n$dateHeader\n$canonicalResource"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(sk.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = Base64.encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "OSS $ak:$sig"
    }

    private fun uploadZip(endpoint: String, token: String, zipBytes: ByteArray, kind: String): Int {
        val trimmed = endpoint.trim().trimEnd('/')
        if (isAliyunOssEndpoint(trimmed)) {
            val key = ossObjectKey(kind)
            val targetUrl = URL("$trimmed/$key")
            val conn = targetUrl.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "PUT"
                val contentType = "application/zip"
                val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val dateStr = dateFmt.format(Date())
                conn.setRequestProperty("Content-Type", contentType)
                conn.setRequestProperty("Date", dateStr)
                conn.setRequestProperty("User-Agent", "StudyOnTV-Parent-Telemetry/1")
                val sep = token.indexOf(':')
                if (sep > 0 && sep < token.length - 1) {
                    val ak = token.substring(0, sep).trim()
                    val sk = token.substring(sep + 1).trim()
                    val bucket = targetUrl.host.substringBefore(".oss-")
                    conn.setRequestProperty("Authorization", ossSignV1(ak, sk, "PUT", contentType, dateStr, "/$bucket/$key"))
                } else {
                    conn.setRequestProperty("x-oss-forbid-overwrite", "true")
                }
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.outputStream.use { it.write(zipBytes) }
                return conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
        val conn = URL(trimmed).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.setRequestProperty("X-Report-Kind", kind)
            conn.setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
            conn.setRequestProperty("X-App-VersionCode", BuildConfig.VERSION_CODE.toString())
            conn.setRequestProperty("X-Device-Model", Build.MODEL)
            conn.setRequestProperty("X-Device-Manufacturer", Build.MANUFACTURER)
            conn.setRequestProperty("X-Android-SDK", Build.VERSION.SDK_INT.toString())
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.outputStream.use { it.write(zipBytes) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    internal fun runPendingCrashUpload(
        readAndClear: () -> String,
        writePending: (String) -> Unit,
        buildZip: (String) -> ByteArray,
        upload: (ByteArray) -> Int,
        onSuccess: (Int, Int) -> Unit = { _, _ -> },
        onFailure: (String) -> Unit = {}
    ): Boolean {
        var summary: String? = null
        return try {
            summary = readAndClear()
            val zipBytes = buildZip(summary)
            val code = upload(zipBytes)
            if (code in 200..299) {
                onSuccess(zipBytes.size, code)
                true
            } else {
                writePending(summary)
                onFailure("parent crash upload failed: HTTP $code")
                false
            }
        } catch (e: Exception) {
            if (summary != null) try { writePending(summary) } catch (_: Exception) { }
            onFailure("parent crash upload error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun buildDeviceJson(context: Context): JSONObject =
        JSONObject()
            .put("app", "StudyOnTV-Parent")
            .put("flavor", "parent")
            .put("package", context.packageName)
            .put("version", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("savedDevicesCount", try { DeviceStore.load(context).size } catch (_: Exception) { 0 })

    private fun buildZip(context: Context, crashSummary: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (!crashSummary.isNullOrBlank()) {
                zip.putNextEntry(ZipEntry("crash_summary.txt"))
                zip.write(crashSummary.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            logDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("device.json"))
            zip.write(buildDeviceJson(context).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun uploadPendingCrash(context: Context) {
        if (BuildConfig.LOCAL_ONLY || !telemetryEnabled(context)) return
        val endpoint = BuildConfig.CRASH_ENDPOINT.trim()
        if (endpoint.isBlank()) return
        val pendingFile = File(context.filesDir, CRASH_PENDING_FILE)
        if (!pendingFile.exists()) return
        if (!crashUploading.compareAndSet(false, true)) return
        val app = context.applicationContext
        val token = BuildConfig.CRASH_TOKEN.trim()
        Thread {
            try {
                runPendingCrashUpload(
                    readAndClear = {
                        val f = File(app.filesDir, CRASH_PENDING_FILE)
                        val text = try { f.readText() } catch (_: Exception) { "" }
                        f.delete()
                        text
                    },
                    writePending = { try { File(app.filesDir, CRASH_PENDING_FILE).writeText(it) } catch (_: Exception) { } },
                    buildZip = { buildZip(app, it) },
                    upload = { uploadZip(endpoint, token, it, "parent-crash") },
                    onSuccess = { size, code -> info("Crash", "parent crash upload ok ($size bytes, HTTP $code)") },
                    onFailure = { msg -> warn("Crash", msg) }
                )
            } finally {
                crashUploading.set(false)
            }
        }.apply { name = "parent-crash-upload"; isDaemon = true }.start()
    }

    fun uploadRuntimeLogsIfDue(context: Context, force: Boolean = false, onDone: ((Boolean, String) -> Unit)? = null) {
        if (BuildConfig.LOCAL_ONLY) {
            onDone?.invoke(false, "仅本地版本未启用日志上报")
            return
        }
        if (!force && !telemetryEnabled(context)) return
        val endpoint = BuildConfig.CRASH_ENDPOINT.trim()
        if (endpoint.isBlank()) {
            onDone?.invoke(false, "当前构建未配置日志上报地址")
            return
        }
        if (!force && System.currentTimeMillis() - lastUploadAt(context) < RUNTIME_UPLOAD_INTERVAL_MS) return
        if (!runtimeUploading.compareAndSet(false, true)) {
            onDone?.invoke(false, "日志正在上报中，请稍候")
            return
        }
        val app = context.applicationContext
        val token = BuildConfig.CRASH_TOKEN.trim()
        Thread {
            var ok = false
            var msg = ""
            try {
                val zipBytes = buildZip(app)
                val code = uploadZip(endpoint, token, zipBytes, "parent-runtime")
                if (code in 200..299) {
                    setLastUploadAt(app, System.currentTimeMillis())
                    info("Telemetry", "parent runtime log upload ok (${zipBytes.size} bytes, HTTP $code)")
                    ok = true
                    msg = "诊断日志已上报，感谢反馈"
                } else {
                    warn("Telemetry", "parent runtime log upload failed: HTTP $code")
                    msg = "上报失败（HTTP $code），请检查网络后重试"
                }
            } catch (e: Exception) {
                warn("Telemetry", "parent runtime log upload error: ${e.javaClass.simpleName}: ${e.message}")
                msg = "上报失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                runtimeUploading.set(false)
            }
            onDone?.invoke(ok, msg)
        }.apply { name = "parent-log-upload"; isDaemon = true }.start()
    }

    fun showMenuDialog(activity: Activity) {
        val enabled = telemetryEnabled(activity)
        val items = ArrayList<String>()
        if (!BuildConfig.LOCAL_ONLY) {
            items.add("检查家长端更新")
            items.add("立即上报诊断日志")
            items.add(if (enabled) "暂停自动上报日志（当前已开启）" else "开启自动上报日志（当前已暂停）")
        } else {
            items.add("当前为仅本地隐私版（已关闭联网与上报）")
        }
        AlertDialog.Builder(activity)
            .setTitle("家长端 v${BuildConfig.VERSION_NAME}")
            .setItems(items.toTypedArray()) { _, which ->
                if (BuildConfig.LOCAL_ONLY) return@setItems
                when (which) {
                    0 -> {
                        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
                        ParentUpdater.check(activity, forceRefresh = true) { info, err ->
                            activity.runOnUiThread {
                                if (activity.isFinishing) return@runOnUiThread
                                when {
                                    info != null -> ParentUpdater.showUpdateDialog(activity, info)
                                    !err.isNullOrBlank() -> Toast.makeText(activity, "检查失败：$err", Toast.LENGTH_LONG).show()
                                    else -> Toast.makeText(activity, "当前已是最新版本 v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    1 -> {
                        Toast.makeText(activity, "正在上报家长端诊断日志…", Toast.LENGTH_SHORT).show()
                        uploadRuntimeLogsIfDue(activity, force = true) { _, message ->
                            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
                        }
                    }
                    2 -> {
                        setTelemetryEnabled(activity, !enabled)
                        Toast.makeText(
                            activity,
                            if (!enabled) "已开启家长端诊断日志上报" else "已关闭家长端自动日志上报",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
