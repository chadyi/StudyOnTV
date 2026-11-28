package com.studyontv.tv

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL

class WebShell(private val context: Context) {

    var onBackPressed: (() -> Unit)? = null
    var onShowFileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun create(url: String): WebView {
        ensureDataDirectory(context)
        val web = createWebViewSafely(context)
        try { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) } catch (_: Throwable) { }
        web.setBackgroundColor(TvUi.background(context))
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        
        
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        // #141：电视把系统字体调大后，WebView 会把学习页的字再放大一遍，题干被顶出屏幕；
        // 页面自己按 0.5208vw 和「学习页字号」缩放，这里固定 100%，只认家长设置的值
        s.textZoom = 100
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.userAgentString = s.userAgentString + " StudyOnTV/0.1 (AndroidTV)"

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(url)) return true
                return !isAllowed(url)
            }
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (LocalOnlyGuard.enabled) {
                    val reqUrl = request?.url?.toString()
                    if (!LocalOnlyGuard.isLocalUrl(reqUrl)) {
                        return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val src = consoleMessage.sourceId().orEmpty().substringBefore('?').substringAfterLast('/')
                    Diag.warn("WebJS", "$src:${consoleMessage.lineNumber()} ${consoleMessage.message().orEmpty().take(300)}")
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val handler = onShowFileChooser ?: return false
                if (filePathCallback == null || fileChooserParams == null) return false
                return handler(filePathCallback, fileChooserParams)
            }
        }
        web.loadUrl(url)
        return web
    }

    /**
     * 安全创建 WebView：部分精简 ROM 电视盒子（如 Android 6.0.1 运营商盒子）缺失系统 WebView 包，
     * 捕获 MissingWebViewPackageException / UnsatisfiedLinkError，避免 Activity/Service 无限闪退。
     */
    fun createOrNull(url: String): WebView? = try {
        create(url)
    } catch (t: Throwable) {
        if (!isWebViewUnavailable(t)) throw t
        Diag.error("WebView", "系统缺少 WebView 组件，已降级原生提示", t)
        null
    }

    
    fun warmUp(url: String) {
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.connect()
                c.inputStream?.close()
                c.disconnect()
            } catch (t: Throwable) {
                
            }
        }.apply { isDaemon = true }.start()
    }

    fun dispatchKey(web: WebView, keyCode: Int, event: KeyEvent?): Boolean {
        val longPress = event?.repeatCount ?: 0
        val js = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "window.TV&&TV.move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "window.TV&&TV.move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "window.TV&&TV.move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "window.TV&&TV.move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                "window.TV&&TV.select()"
            KeyEvent.KEYCODE_BACK -> {
                if (longPress > 2) return false
                web.evaluateJavascript("window.TV&&TV.back()", null)
                return true
            }
            else -> return false
        }
        web.evaluateJavascript(js, null)
        return true
    }

    companion object {
        @Volatile private var dataDirChecked = false

        private fun sanitizeSuffix(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifEmpty { "sub" }

        /**
         * ST-613：Android 9+（API 28+）下，在任何 WebView 初始化之前隔离非主进程数据目录，
         * 避免多进程或旧僵尸进程占锁导致 Using WebView from more than one process 崩溃。
         */
        private fun ensureDataDirectory(context: Context) {
            if (dataDirChecked || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            synchronized(this) {
                if (dataDirChecked) return
                dataDirChecked = true
                try {
                    val proc = Application.getProcessName().orEmpty()
                    if (proc.isNotEmpty() && proc != context.packageName) {
                        WebView.setDataDirectorySuffix(sanitizeSuffix(proc))
                    }
                } catch (_: Throwable) { }
            }
        }

        private fun createWebViewSafely(context: Context): WebView {
            ensureDataDirectory(context)
            return try {
                WebView(context)
            } catch (e: RuntimeException) {
                val msg = e.message.orEmpty()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    msg.contains("Using WebView from more than one process", ignoreCase = true)
                ) {
                    try {
                        WebView.setDataDirectorySuffix("p_${Process.myPid()}")
                        Diag.warn("WebView", "检测到旧进程占用默认 WebView 数据目录，已自动隔离至 p_${Process.myPid()}")
                        WebView(context)
                    } catch (_: Throwable) {
                        throw e
                    }
                } else {
                    throw e
                }
            }
        }

        /** 识别「系统缺少/损坏/正在更新 WebView」异常链，用于降级纯原生提示页而不是闪退循环。 */
        internal fun isWebViewUnavailable(t: Throwable): Boolean {
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth++ < 8) {
                val name = cur.javaClass.name
                val msg = cur.message.orEmpty()
                if (name.contains("MissingWebViewPackageException", ignoreCase = true) ||
                    cur is UnsatisfiedLinkError ||
                    msg.contains("MissingWebViewPackageException", ignoreCase = true) ||
                    msg.contains("No WebView installed", ignoreCase = true) ||
                    msg.contains("Failed to load WebView provider", ignoreCase = true) ||
                    msg.contains("com.google.android.webview", ignoreCase = true) ||
                    msg.contains("com.android.webview", ignoreCase = true)
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        /** 缺少系统 WebView 时的纯原生降级页：不依赖 WebView，告知原因、局域网管理地址并提供重试/退出入口。 */
        fun buildUnavailableView(
            context: Context,
            title: String,
            onRetry: () -> Unit,
            onExit: () -> Unit
        ): View {
            val dp = context.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()
            val tv = DeviceBridge.isTv(context)
            val lanUrl = try {
                val info = DeviceRuntime.connection(context)
                val ip = info.optString("ip").trim()
                val port = info.optInt("port", Prefs.localPort(context))
                if (ip.isNotEmpty()) "http://$ip:$port" else ""
            } catch (_: Throwable) { "" }
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = if (tv) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.NO_GRAVITY
                setPadding(px(28), px(if (tv) 36 else 48), px(28), px(36))
                setBackgroundColor(TvUi.background(context))
            }
            val col = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            container.addView(col, android.widget.LinearLayout.LayoutParams(if (tv) px(560) else -1, -2))
            fun label(text: String, size: Float, color: Int = TvUi.foreground(context), bold: Boolean = false) =
                android.widget.TextView(context).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    paint.isFakeBoldText = bold
                    setPadding(0, px(8), 0, px(8))
                }
            col.addView(label(title, if (tv) 28f else 24f, bold = true))
            col.addView(label(
                "当前设备的精简系统缺少 Android System WebView 组件，无法加载内置页面。\n\n" +
                    "解决方法：\n" +
                    "1. 在电视应用商店或通过 U 盘安装「Android System WebView」后点击重试；\n" +
                    "2. 家长管理后台不受影响，可直接用手机浏览器访问局域网地址。" +
                    if (lanUrl.isNotEmpty()) "\n\n家长后台地址：$lanUrl/admin" else "",
                15f,
                TvUi.muted(context)
            ))
            val retryBtn = TvUi.button(context, "重新尝试加载", true) { onRetry() }
            col.addView(retryBtn, android.widget.LinearLayout.LayoutParams(-1, px(54)).apply { topMargin = px(16) })
            col.addView(TvUi.button(context, "返回") { onExit() },
                android.widget.LinearLayout.LayoutParams(-1, px(50)).apply { topMargin = px(12) })
            retryBtn.requestFocus()
            return android.widget.ScrollView(context).apply {
                isFillViewport = true
                setBackgroundColor(TvUi.background(context))
                addView(container)
            }
        }

        /**
         * 解析 WebView 文件选择器的 Intent MIME 类型：
         * 仅当全部 accept 条目均属于同一媒体大类（image/audio/video）时才收窄类型；
         * 文档与 JSON（.json/.docx/.pdf/.txt/application/json）在国产 ROM 上常被标成
         * application/octet-stream 或 text/plain，一律使用通配类型避免文件变灰无法选中。
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

        fun isAllowed(url: String?): Boolean {
            if (url == null || url == "about:blank") return true
            val base = PrefsHolder.baseUrl ?: return false
            return try {
                val target = URL(url)
                val source = URL(base)
                target.protocol == source.protocol && target.host == source.host && target.port == source.port
            } catch (t: Throwable) {
                false
            }
        }
    }

    
    object PrefsHolder {
        @Volatile
        var baseUrl: String? = null
    }
}
