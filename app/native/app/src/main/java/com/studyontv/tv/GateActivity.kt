package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView









class GateActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        immersive()

        val reason = intent.getStringExtra("reason") ?: "timeup"
        val base = Prefs.localBase(this)
        WebShell.PrefsHolder.baseUrl = base

        val shell = WebShell(this)
        web = shell.create(base + "#/gate?reason=" + reason)
        web.addJavascriptInterface(GateBridge(), "SOTVNative")
        setContentView(web)
        web.requestFocus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val reason = intent?.getStringExtra("reason") ?: return
        web.loadUrl(Prefs.localBase(this) + "#/gate?reason=" + reason)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val js = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "window.TV&&TV.move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "window.TV&&TV.move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "window.TV&&TV.move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "window.TV&&TV.move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "window.TV&&TV.select()"
            KeyEvent.KEYCODE_BACK -> return true 
            else -> return super.onKeyDown(keyCode, event)
        }
        web.evaluateJavascript(js, null)
        return true
    }

    
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

    inner class GateBridge {
        
        @JavascriptInterface
        fun studyStarted() {
            Prefs.setRemainMinutes(this@GateActivity, 0)
        }

        
        @JavascriptInterface
        fun grantMinutes(minutes: Int) {
            Prefs.setRemainMinutes(this@GateActivity, minutes)
            GateService.requestGateClear(this@GateActivity)
            finish()
        }

        @JavascriptInterface
        fun backToApp() {
            startActivity(Intent(this@GateActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        @JavascriptInterface
        fun closeGate() {
            finish()
        }
    }
}
