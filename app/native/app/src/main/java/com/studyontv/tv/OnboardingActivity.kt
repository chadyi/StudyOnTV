package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView





class OnboardingActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF0A0F1C.toInt())
        root.setPadding(dp(64), dp(48), dp(64), dp(48))

        root.addView(title("欢迎使用 题了个题"))

        val sub = TextView(this)
        sub.text = "先学再看，让电视时间更有价值。\n先开下面三个权限，然后手机扫码就能管这台电视。"
        sub.setTextColor(0xFFA9B7CE.toInt())
        sub.textSize = 18f
        sub.setPadding(0, dp(12), 0, dp(28))
        root.addView(sub)

        root.addView(permRow("1. 悬浮窗权限", "让学习门禁可以盖在任何应用上面",
            View.OnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }))

        root.addView(permRow("2. 无障碍服务", "实时知道孩子打开了哪个应用",
            View.OnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }))

        root.addView(permRow("3. 使用情况访问", "无障碍被系统回收时的兜底方案",
            View.OnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }))

        root.addView(permRow("4. 自启动 / 后台白名单", "开机自动守护，不被系统清理",
            View.OnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            }))

        status = TextView(this)
        status.setTextColor(0xFF6E8BC4.toInt())
        status.textSize = 15f
        status.setPadding(0, dp(20), 0, dp(8))
        root.addView(status)

        val done = Button(this)
        done.text = "完成，显示家长扫码"
        done.textSize = 20f
        done.setTextColor(0xFF14100A.toInt())
        done.background = rounded(0xFFFFB020.toInt(), 0xFFFF6A1A.toInt(), 16)
        done.setOnClickListener {
            Prefs.setOnboarded(this, true)
            GateService.start(this)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("screen", "pair")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
        val dlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(64))
        dlp.topMargin = dp(16)
        root.addView(done, dlp)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = "当前状态：" +
                "悬浮窗 " + ok(canDrawOverlay()) + " · " +
                "无障碍 " + ok(WatchAccessibilityService.connected) + " · " +
                "使用情况 " + ok(Foreground.hasUsageAccess(this))
        }
    }

    private fun ok(v: Boolean) = if (v) "✓" else "✗"

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(from: Int, to: Int, radiusDp: Int): android.graphics.drawable.Drawable {
        val g = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(from, to)
        )
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun title(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.textSize = 30f
        return v
    }

    private fun permRow(t: String, d: String, onClick: View.OnClickListener): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(14), 0, dp(14))

        val b = Button(this)
        b.text = t
        b.textSize = 18f
        b.setTextColor(0xFFF2F2F4.toInt())
        b.background = rounded(0xFF17171B.toInt(), 0xFF232329.toInt(), 14)
        b.setPadding(dp(28), dp(14), dp(28), dp(14))
        b.isAllCaps = false
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setOnClickListener(onClick)
        box.addView(b)

        val dv = TextView(this)
        dv.text = d
        dv.setTextColor(0xFF7E90AE.toInt())
        dv.textSize = 14f
        box.addView(dv)
        return box
    }
}
