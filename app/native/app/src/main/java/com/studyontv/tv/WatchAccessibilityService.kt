package com.studyontv.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView

class WatchAccessibilityService : AccessibilityService() {
    private var overlay: WebView? = null
    private var upCount = 0
    private var lastUp = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && (pkg.contains("inputmethod") || pkg.contains("keyboard"))) return
            if (overlay == null || pkg != packageName) Foreground.update(pkg)
            GateService.onForegroundChanged(this, pkg)
        }
    }
    override fun onInterrupt() = Unit
    override fun onServiceConnected() {
        instance = this
        connected = true
        if (Prefs.onboarded(this)) GateService.start(this)
    }
    override fun onDestroy() {
        removeOverlay()
        instance = null
        connected = false
        super.onDestroy()
    }

    private fun addOverlay(reason: String) {
        if (overlay != null) return
        DeviceRuntime.ensure(this)
        val page = if (reason == "timeup") "quiz" else "lock?reason=$reason"
        val web = WebShell(this).create(Prefs.localBase(this) + "#/$page")
        web.addJavascriptInterface(OverlayBridge(), "SOTVNative")
        web.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener key == KeyEvent.KEYCODE_BACK
            if (key == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) {
                val now = android.os.SystemClock.elapsedRealtime()
                upCount = if (now - lastUp < 900) upCount + 1 else 1
                lastUp = now
                if (upCount >= 5) { upCount = 0; OverlayBridge().openParent(); return@setOnKeyListener true }
            } else if (event.repeatCount == 0) upCount = 0
            if (key == KeyEvent.KEYCODE_BACK) return@setOnKeyListener true
            WebShell(this).dispatchKey(web, key, event)
        }
        overlay = web
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        params.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(web, params)
            web.requestFocus()
        } catch (e: Exception) { overlay = null; web.destroy() }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (e: Exception) { }
        view.destroy()
    }

    inner class OverlayBridge {
        @JavascriptInterface fun answerInput(voice: Boolean) {
            Handler(Looper.getMainLooper()).post { overlay?.let { InputSupport.open(this@WatchAccessibilityService, it, voice) } }
        }
        @JavascriptInterface fun apiToken(): String = AdminAuth.deviceToken
        @JavascriptInterface fun deviceInfo(): String = org.json.JSONObject().put("platform", if (DeviceBridge.isTv(this@WatchAccessibilityService)) "android-tv" else "android").toString()
        @JavascriptInterface fun openParent() {
            Handler(Looper.getMainLooper()).post {
                removeOverlay()
                startActivity(Intent(this@WatchAccessibilityService, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        @JavascriptInterface fun exitToLauncher() {
            val status = DeviceRuntime.learning.status()
            if (status.getString("reason").isNotEmpty() || status.optLong("remainingSeconds") <= 0) return
            Handler(Looper.getMainLooper()).post { performGlobalAction(GLOBAL_ACTION_HOME); removeOverlay() }
        }
    }

    companion object {
        private var instance: WatchAccessibilityService? = null
        var connected = false
            private set
        fun hasGate(): Boolean = instance?.overlay != null
        fun showGate(reason: String): Boolean {
            val service = instance ?: return false
            service.addOverlay(reason)
            return service.overlay != null
        }
        fun hideGate() { Handler(Looper.getMainLooper()).post { instance?.removeOverlay() } }
    }
}
