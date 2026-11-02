package com.studyontv.tv

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






class GateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var gated = false

    private val tick = object : Runnable {
        override fun run() {
            try {
                checkForeground()
            } catch (t: Throwable) {
                
            }
            handler.postDelayed(this, 1500)
        }
    }

    private fun checkForeground() {
        val pkg = Foreground.current(this) ?: return
        if (pkg == packageName) return
        if (Foreground.isLauncher(pkg)) return
        if (Prefs.updateInProgress(this)) return                 
        if (pkg.contains("packageinstaller")) return             
        if (pkg.startsWith("com.android.settings")) return        

        val exhausted = Prefs.remainMinutes(this) <= 0
        if (exhausted && !gated) {
            gated = true
            openGate("timeup")
        }
    }

    private fun openGate(reason: String) {
        val i = Intent(this, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("reason", reason)
        startActivity(i)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.setServiceRunning(this, true)
        startForegroundCompat()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GATE) {
            gated = true
            openGate(intent.getStringExtra("reason") ?: "timeup")
        }
        if (intent?.action == ACTION_CLEAR_GATE) {
            gated = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Prefs.setServiceRunning(this, false)
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val channelId = "sotv_gate"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "屏幕时间守护", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else @Suppress("DEPRECATION") Notification.Builder(this)

        val notification = builder
            .setContentTitle("StudyOnTV 正在守护电视时间")
            .setContentText("先学再看，时间到会自动提醒")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_GATE = "com.studyontv.tv.GATE"
        const val ACTION_CLEAR_GATE = "com.studyontv.tv.CLEAR_GATE"

        @Volatile
        private var instance: GateService? = null

        fun start(context: Context) {
            val i = Intent(context, GateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun requestGate(context: Context, reason: String) {
            val i = Intent(context, GateService::class.java).setAction(ACTION_GATE).putExtra("reason", reason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        
        fun requestGateClear(context: Context) {
            instance?.let { it.gated = false }
        }

        fun onForegroundChanged(context: Context, pkg: String?) {
            if (pkg == null) return
            if (pkg == context.packageName) {
                
                instance?.let { it.gated = false }
                return
            }
            if (!Foreground.isLauncher(pkg) && !Prefs.updateInProgress(context) &&
                !pkg.contains("packageinstaller") && Prefs.remainMinutes(context) <= 0) {
                instance?.let {
                    if (!it.gated) {
                        it.gated = true
                        it.openGate("timeup")
                    }
                }
            }
        }
    }
}
