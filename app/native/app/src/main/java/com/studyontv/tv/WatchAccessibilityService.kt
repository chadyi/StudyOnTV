package com.studyontv.tv

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent





class WatchAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString()
                Foreground.update(pkg)
                GateService.onForegroundChanged(this, pkg)
            }
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var connected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }
}
