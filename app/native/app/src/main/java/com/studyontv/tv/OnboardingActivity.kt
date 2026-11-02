package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OnboardingActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminAuth.configured(this)) {
            startActivity(Intent(this, ParentActivity::class.java)); finish(); return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF8F9F6.toInt())
        }
        root.addView(TextView(this).apply { text = "设置使用时间管理"; textSize = 28f; setTextColor(0xFF303B31.toInt()) })
        root.addView(TextView(this).apply { text = "支持电视、手机和平板。请开启以下权限。"; textSize = 16f; setTextColor(0xFF788571.toInt()) })
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; learningStyle(); setOnClickListener { action() } })
        }
        fun settings(action: String, withPackage: Boolean = false) {
            try { startActivity(Intent(action).apply { if (withPackage) data = Uri.parse("package:$packageName") }) }
            catch (e: Exception) { status.text = "请在系统设置中为本应用开启权限" }
        }
        button("悬浮窗权限") { settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        button("无障碍服务") { settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        button("使用情况访问") { settings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        button("自启动与后台运行") { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        status = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFB020.toInt()) }
        root.addView(status)
        button("完成，连接家长设备") {
            if (!WatchAccessibilityService.connected) { status.text = "请先开启无障碍服务"; return@button }
            Prefs.setOnboarded(this, true)
            GateService.start(this)
            startActivity(Intent(this, ParentActivity::class.java))
            finish()
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = "无障碍：${if (WatchAccessibilityService.connected) "已开启" else "未开启"} · 使用情况：${if (Foreground.hasUsageAccess(this)) "已开启" else "未开启"}"
    }
}
