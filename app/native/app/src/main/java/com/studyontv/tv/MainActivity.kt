package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject







class MainActivity : Activity() {

    private lateinit var shell: WebShell
    private lateinit var web: WebView
    private lateinit var store: LocalStore
    private lateinit var lan: LanServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        if (!Prefs.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        Prefs.setUpdateInProgress(this, false)
        
        store = LocalStore(this)
        SeedAssets.ensureQuestions(this, store)
        lan = LanServer(this, store, assets)
        lan.start()
        Prefs.setLocalPort(this, lan.boundPort)

        val base = "http://127.0.0.1:" + lan.boundPort + "/"
        WebShell.PrefsHolder.baseUrl = base

        shell = WebShell(this)
        shell.warmUp(base)
        
        val startScreen = intent?.getStringExtra("screen") ?: "quiz"
        web = shell.create(base + "#/" + startScreen)
        web.addJavascriptInterface(Bridge(), "SOTVNative")
        shell.onBackPressed = { moveTaskToBack(true) }

        setContentView(web)
        web.requestFocus()
        GateService.start(this)
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        immersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (this::web.isInitialized && shell.dispatchKey(web, keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            
            startActivity(Intent(this, OnboardingActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    
    private fun checkUpdate() {
        Updater.check(this) { info ->
            val newer = Updater.newerThanCurrent(this, info) ?: return@check
            runOnUiThread {
                if (newer.force) {
                    startActivity(Intent(this, UpdateActivity::class.java).putExtra("payload", payload(newer)))
                } else if (this::web.isInitialized) {
                    web.evaluateJavascript("window.SOTV_UPDATE && SOTV_UPDATE(" + payload(newer) + ")", null)
                }
            }
        }
    }

    private fun payload(i: Updater.Info): String = JSONObject()
        .put("latest", i.latest).put("current", i.current)
        .put("force", i.force).put("notes", i.notes).put("url", i.url).toString()

    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN && this::web.isInitialized) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount > 2) return true
            web.evaluateJavascript(jsFor(event.keyCode), null)
        }
        return true
    }

    private fun jsFor(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> "window.TV&&TV.move('left')"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "window.TV&&TV.move('right')"
        KeyEvent.KEYCODE_DPAD_UP -> "window.TV&&TV.move('up')"
        KeyEvent.KEYCODE_DPAD_DOWN -> "window.TV&&TV.move('down')"
        KeyEvent.KEYCODE_BACK -> "window.TV&&TV.back()"
        else -> "window.TV&&TV.select()"
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    
    inner class Bridge {

        @JavascriptInterface
        fun version(): String = "0.1.0"

        @JavascriptInterface
        fun deviceInfo(): String {
            val o = JSONObject()
            o.put("brand", Build.BRAND)
            o.put("model", Build.MODEL)
            o.put("sdk", Build.VERSION.SDK_INT)
            o.put("webview", webViewVersion())
            o.put("platform", "android-tv")
            return o.toString()
        }

        @JavascriptInterface
        fun foregroundApp(): String {
            val o = JSONObject()
            o.put("pkg", Foreground.current(this@MainActivity) ?: "")
            return o.toString()
        }

        
        @JavascriptInterface
        fun lanInfo(): String {
            val o = JSONObject()
            o.put("ip", lan.localIp() ?: "")
            o.put("port", lan.boundPort)
            o.put("token", store.adminToken())
            o.put("url", "http://" + (lan.localIp() ?: "127.0.0.1") + ":" + lan.boundPort + "/admin/")
            return o.toString()
        }

        @JavascriptInterface
        fun questionCount(): Int = store.countQuestions()

        
        @JavascriptInterface
        fun updateInfo(): String {
            val info = Updater.newerThanCurrent(this@MainActivity, Updater.cachedInfo(this@MainActivity))
                ?: return ""
            return payload(info)
        }

        
        @JavascriptInterface
        fun installUpdate(url: String, latest: String, notes: String) {
            val payload = JSONObject()
                .put("latest", latest)
                .put("current", Updater.currentVersion(this@MainActivity))
                .put("notes", notes)
                .put("url", url)
            startActivity(Intent(this@MainActivity, UpdateActivity::class.java)
                .putExtra("payload", payload.toString()))
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun versionName(): String = Updater.currentVersion(this@MainActivity)

        @JavascriptInterface
        fun remainMinutes(): Int = Prefs.remainMinutes(this@MainActivity)

        @JavascriptInterface
        fun setRemainMinutes(min: Int) {
            Prefs.setRemainMinutes(this@MainActivity, min)
            if (min > 0) GateService.requestGateClear(this@MainActivity)
        }

        
        @JavascriptInterface
        fun lockNow(reason: String) {
            GateService.requestGate(this@MainActivity, reason)
        }

        @JavascriptInterface
        fun startWatch(minutes: Int) {
            Prefs.setRemainMinutes(this@MainActivity, minutes)
            GateService.requestGateClear(this@MainActivity)
        }

        
        @JavascriptInterface
        fun studyStarted() {
            Prefs.setRemainMinutes(this@MainActivity, 0)
        }

        @JavascriptInterface
        fun openOverlaySettings() {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun exitToLauncher() {
            moveTaskToBack(true)
        }
    }

    private fun webViewVersion(): String {
        return try {
            val p = WebView.getCurrentWebViewPackage()
            if (p != null) p.versionName ?: "" else ""
        } catch (t: Throwable) {
            ""
        }
    }
}
