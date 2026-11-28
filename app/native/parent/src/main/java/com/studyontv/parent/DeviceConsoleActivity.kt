package com.studyontv.parent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

/**
 * 家长管理后台容器：
 * 直接加载目标电视/平板本地 HTTP 服务的 `http://<ip>:<port>/admin/`，100% 复用 `parent-ui` 全部功能，
 * 补齐原生返回键导航、文件选择器（Word/PDF/图片/题库导入）、备份导出下载、断线友好提示与一键复制网址。
 */
class DeviceConsoleActivity : Activity() {
    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_SCHEME = "scheme"

        private const val REQ_FILE_CHOOSER = 4101
        private const val REQ_SAVE_DOWNLOAD = 4102

        /**
         * 解析 WebView 文件选择器的 Intent MIME 类型：
         * 仅当全部 accept 条目均属于同一媒体大类（image/audio/video）时才收窄类型；
         * 文档与 JSON（.json/.docx/.pdf/.txt/application/json）在国产 ROM 上常被标成
         * application/octet-stream 或 text/plain，若强塞 EXTRA_MIME_TYPES 会导致文件变灰无法选中。
         */
        internal fun resolveFileChooserMimeType(acceptTypes: Array<String>?): String {
            val tokens = acceptTypes.orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().lowercase(java.util.Locale.US) }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return "*/*"
            val imageExts = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".svg")
            val audioExts = setOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".opus")
            val videoExts = setOf(".mp4", ".webm", ".mkv", ".mov", ".m4v")
            return when {
                tokens.all { it.startsWith("image/") || it in imageExts } -> "image/*"
                tokens.all { it.startsWith("audio/") || it in audioExts } -> "audio/*"
                tokens.all { it.startsWith("video/") || it in videoExts } -> "video/*"
                else -> "*/*"
            }
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var views: DeviceConsoleViews
    private lateinit var targetHost: LanNet.ParsedHost
    private var deviceId: String = ""
    private var deviceName: String = ""
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadUserAgent: String? = null

    private inner class ParentSessionBridge {
        @JavascriptInterface
        fun getSavedToken(): String = DeviceStore.loadSessionToken(this@DeviceConsoleActivity, deviceId)

        @JavascriptInterface
        fun saveToken(token: String?) {
            DeviceStore.saveSessionToken(this@DeviceConsoleActivity, deviceId, token.orEmpty())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ParentDiag.init(this)
        val palette = ParentUi.palette(this)
        ParentUi.applySystemBars(this, palette)

        val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
        val scheme = intent.getStringExtra(EXTRA_SCHEME).orEmpty().lowercase().let { if (it == "https") "https" else "http" }
        val port = intent.getIntExtra(EXTRA_PORT, if (scheme == "https") 443 else LanNet.DEFAULT_HTTP_PORT)
        val parsed = LanNet.parseManualHost("$scheme://${LanNet.formatHostPort(ip, port, scheme)}", port)
        if (parsed == null) {
            Toast.makeText(this, "无效的设备地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        targetHost = parsed
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty().ifBlank { parsed.host }
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty().ifBlank { parsed.ip }

        views = DeviceConsoleViews(
            activity = this,
            p = palette,
            deviceName = deviceName,
            targetHost = targetHost,
            callbacks = object : DeviceConsoleViews.Callbacks {
                override fun onBack() = finish()
                override fun onReload() {
                    views.hideOffline()
                    views.webView.reload()
                }

                override fun onCopyUrl() {
                    val adminUrl = targetHost.adminUrl
                    ParentUi.copyToClipboard(this@DeviceConsoleActivity, "StudyOnTV Admin", adminUrl, "已复制 $adminUrl")
                }

                override fun onReconnect() {
                    views.hideOffline()
                    loadAdminHome()
                }

                override fun onOpenAdbFix() {
                    startActivity(
                        Intent(this@DeviceConsoleActivity, AdbWizardActivity::class.java)
                            .putExtra(AdbWizardActivity.EXTRA_PREFILL_IP, targetHost.ip)
                            .putExtra(AdbWizardActivity.EXTRA_FIX_ONLY, true)
                    )
                }
            }
        )
        setContentView(views.rootView)
        configureWebView()
        ParentDiag.info("Console", "open host=${targetHost.host} device=$deviceName")
        loadAdminHome()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = views.webView
        CookieManager.getInstance().setAcceptCookie(true)
        webView.addJavascriptInterface(ParentSessionBridge(), "StudyOnTVParent")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrlNavigation(request.url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlNavigation(Uri.parse(url))
            }

            private fun handleUrlNavigation(uri: Uri?): Boolean {
                if (uri == null) return true
                val scheme = uri.scheme?.lowercase().orEmpty()
                val uriPort = when {
                    uri.port > 0 -> uri.port
                    scheme == "https" -> 443
                    scheme == "http" -> 80
                    else -> -1
                }
                val expectedHost = targetHost.ip.trim('[', ']')
                if (scheme == targetHost.scheme && uri.host.equals(expectedHost, ignoreCase = true) && uriPort == targetHost.port) {
                    return false // 同一目标设备内导航，留在 WebView
                }
                if (scheme == "https" || scheme == "http") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description?.toString().orEmpty() else ""
                    showOffline(desc)
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                showOffline(description.orEmpty())
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                views.updateProgress(newProgress)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val src = consoleMessage.sourceId().orEmpty().substringBefore('?').substringAfterLast('/')
                    ParentDiag.warn("WebJS", "$src:${consoleMessage.lineNumber()} ${consoleMessage.message().orEmpty().take(300)}")
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val allowMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val acceptRaw = fileChooserParams.acceptTypes.orEmpty().joinToString(",")
                val mimeType = resolveFileChooserMimeType(fileChooserParams.acceptTypes)
                val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType
                    if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType
                    if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                val fallback = try { fileChooserParams.createIntent() } catch (_: Exception) { null }
                val candidates = listOfNotNull(openDoc, getContent, fallback)
                for (candidate in candidates) {
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(candidate, REQ_FILE_CHOOSER)
                        ParentDiag.info("FileChooser", "launched action=${candidate.action} mime=$mimeType accept=$acceptRaw multi=$allowMultiple")
                        return true
                    } catch (e: Exception) {
                        ParentDiag.warn("FileChooser", "action=${candidate.action} failed: ${e.javaClass.simpleName}")
                    }
                }
                pendingFileCallback = null
                ParentDiag.warn("FileChooser", "no file picker available accept=$acceptRaw mime=$mimeType")
                Toast.makeText(
                    this@DeviceConsoleActivity,
                    "当前设备没有可用的文件选择器，请复制网址到手机浏览器导入",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@DeviceConsoleActivity)
                    .setMessage(message)
                    .setPositiveButton("知道了") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@DeviceConsoleActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String?,
                result: JsPromptResult
            ): Boolean {
                val input = EditText(this@DeviceConsoleActivity).apply {
                    setText(defaultValue.orEmpty())
                }
                AlertDialog.Builder(this@DeviceConsoleActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result.confirm(input.text?.toString().orEmpty()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("${targetHost.baseUrl}/")) {
                Toast.makeText(this, "请使用电脑浏览器导出该文件", Toast.LENGTH_SHORT).show()
                return@DownloadListener
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            pendingDownloadUrl = url
            pendingDownloadUserAgent = userAgent
            val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(saveIntent, REQ_SAVE_DOWNLOAD)
            } catch (_: Exception) {
                Toast.makeText(this, "当前系统不支持保存文件对话框", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadAdminHome() {
        views.webView.loadUrl(targetHost.adminUrl)
    }

    private fun showOffline(reason: String) {
        ParentDiag.warn("Console", "offline host=${targetHost.host} reason=$reason")
        runOnUiThread { views.showOffline(reason) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = pendingFileCallback
            pendingFileCallback = null
            if (cb == null) return
            if (resultCode != RESULT_OK || data == null) {
                ParentDiag.info("FileChooser", "canceled resultCode=$resultCode")
                cb.onReceiveValue(null)
                return
            }
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            ParentDiag.info("FileChooser", "selected count=${uris.size}")
            cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        } else if (requestCode == REQ_SAVE_DOWNLOAD) {
            val downloadUrl = pendingDownloadUrl
            val ua = pendingDownloadUserAgent
            pendingDownloadUrl = null
            pendingDownloadUserAgent = null
            val destUri = if (resultCode == RESULT_OK) data?.data else null
            if (downloadUrl != null && destUri != null) {
                saveHttpDownload(downloadUrl, ua, destUri)
            }
        }
    }

    private fun saveHttpDownload(downloadUrl: String, userAgent: String?, destUri: Uri) {
        Toast.makeText(this, "正在保存导出文件…", Toast.LENGTH_SHORT).show()
        val cookie = CookieManager.getInstance().getCookie(downloadUrl)
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                val parsedUrl = URL(downloadUrl)
                val rawConn = if (LanNet.isPrivateLanIp(targetHost.ip)) parsedUrl.openConnection(Proxy.NO_PROXY) else parsedUrl.openConnection()
                conn = (rawConn as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
                if (conn.responseCode !in 200..299) {
                    throw Exception("HTTP ${conn.responseCode}")
                }
                val outStream = contentResolver.openOutputStream(destUri)
                    ?: throw Exception("无法写入目标位置")
                conn.inputStream.use { input ->
                    outStream.use { output -> input.copyTo(output) }
                }
                runOnUiThread { Toast.makeText(this, "文件已保存成功", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                conn?.disconnect()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::views.isInitialized && !views.isOfflineVisible() && views.webView.canGoBack()) {
            views.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        try { CookieManager.getInstance().flush() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        io.shutdownNow()
        if (this::views.isInitialized) {
            views.webView.destroy()
        }
        super.onDestroy()
    }
}
