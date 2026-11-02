package com.studyontv.tv

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class GateService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = SystemClock.elapsedRealtime()
    private var wasUsing = false
    private var lastGate = 0L
    private val tick = object : Runnable {
        override fun run() {
            try { checkForeground() } catch (e: Exception) { android.util.Log.e("StudyOnTV", "Screen time check failed", e) }
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkForeground() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTick
        lastTick = now
        if (wasUsing) DeviceRuntime.learning.consume(elapsed)
        if (InputSupport.active) { wasUsing = false; return }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val pkg = Foreground.current(this)
        val active = power.isInteractive && !locked && !exempt(pkg)
        val protecting = power.isInteractive && !locked && !exempt(pkg, false)
        val status = DeviceRuntime.learning.status()
        val reason = status.getString("reason")
        wasUsing = active && reason.isEmpty()
        if (!power.isInteractive || locked || reason.isEmpty() || exempt(pkg, false)) WatchAccessibilityService.hideGate()
        if (protecting && reason.isNotEmpty() && now - lastGate > 1800 && !WatchAccessibilityService.hasGate()) {
            lastGate = now
            if (!WatchAccessibilityService.showGate(reason)) {
                startActivity(Intent(this, GateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reason", reason))
            }
        }
    }

    private fun exempt(pkg: String?, includeLauncher: Boolean = true): Boolean {
        if (pkg == null || pkg == packageName) return true
        if (pkg == "com.android.systemui" || pkg.contains("packageinstaller") || pkg == "android") return true
        if (pkg.contains("permissioncontroller") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return true
        if (pkg in setOf("com.android.settings", "com.google.android.dialer", "com.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.emergency")) return true
        if (!includeLauncher) return false
        val launcher = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return pkg == launcher || Foreground.isLauncher(pkg)
    }

    override fun onCreate() {
        super.onCreate()
        DeviceRuntime.ensure(this)
        instance = this
        Prefs.setServiceRunning(this, true)
        val channelId = "sotv_gate"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "使用时间管理", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setContentTitle("题了个题 · 使用时间管理中").setContentText("点击打开学习任务")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1001, notification)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        instance = null
        Prefs.setServiceRunning(this, false)
        super.onDestroy()
    }

    companion object {
        private var instance: GateService? = null
        fun start(context: Context) {
            val intent = Intent(context, GateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun onForegroundChanged(context: Context, pkg: String?) {
            instance?.handler?.post { instance?.checkForeground() }
        }
    }
}
