package com.studyontv.tv

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build







object Foreground {

    
    @Volatile
    var lastPackage: String? = null
        private set

    private var ownActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    fun resumeOwn(activity: android.app.Activity) {
        ownActivity = java.lang.ref.WeakReference(activity)
        update(activity.packageName)
    }

    fun pauseOwn(activity: android.app.Activity) {
        if (ownActivity?.get() === activity) ownActivity = null
        if (ownActivity?.get() == null && lastPackage == activity.packageName) lastPackage = null
    }

    fun update(pkg: String?) {
        if (!pkg.isNullOrEmpty()) lastPackage = pkg
    }

    
    private val launcherPackages = setOf(
        "com.android.tv.launcher",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.android.launcher",
        "com.android.launcher3",
        "com.tcl.launcher",
        "com.mitv.tvhome",
        "com.hisense.tv.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher"
    )

    fun isLauncher(pkg: String?): Boolean = pkg != null && launcherPackages.contains(pkg)

    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        ownActivity?.get()?.let {
            if (Build.VERSION.SDK_INT < 24 || !it.isInMultiWindowMode) return it.packageName
        }
        lastPackage?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
                var best: String? = null
                var last = 0L
                stats?.forEach { if (it.lastTimeUsed > last) { last = it.lastTimeUsed; best = it.packageName } }
                if (!best.isNullOrEmpty() && last > now - 15_000) return best
            }
        }

        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }
}
