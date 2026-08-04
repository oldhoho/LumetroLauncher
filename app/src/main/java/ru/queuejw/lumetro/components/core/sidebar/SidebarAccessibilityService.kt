package ru.queuejw.lumetro.components.core.sidebar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import ru.queuejw.lumetro.components.core.receivers.AppReceiver
import ru.queuejw.lumetro.components.freeform.WorkbenchOverlay

class SidebarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SidebarA11yService"
        var sidebarManager: SidebarManager? = null
            private set
        var workbenchOverlay: WorkbenchOverlay? = null
            private set
        private var instance: SidebarAccessibilityService? = null

        fun getInstance(): SidebarAccessibilityService? = instance

        fun isServiceEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${SidebarAccessibilityService::class.java.name}"
            return try {
                val enabled = android.provider.Settings.Secure.getInt(context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                if (enabled == 1) {
                    val enabledServices = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    enabledServices?.contains(serviceName) == true
                } else false
            } catch (e: Exception) { false }
        }

        fun toggleWorkbench() {
            workbenchOverlay?.toggle()
        }

        fun isWorkbenchShowing(): Boolean {
            return workbenchOverlay?.isShowing() ?: false
        }

        fun openRecentTasks() {
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        fun updateForegroundApp(packageName: String) {
            workbenchOverlay?.updateForegroundApp(packageName)
        }

        fun showWorkbench() {
            workbenchOverlay?.show()
        }

        fun refreshWorkbenchGesture() {
        }
    }

    private var receiver: BroadcastReceiver? = null
    private var appReceiver: AppReceiver? = null
    private var lastPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
        }
        Log.d(TAG, "Accessibility service connected")

        try {
            sidebarManager = SidebarManager(this).apply {
                Handler().postDelayed({
                    createGestureStrip()
                    configureTouchPassthrough()
                }, 500)
            }
            Log.d(TAG, "Sidebar initialized successfully")

            workbenchOverlay = WorkbenchOverlay(this)
            Log.d(TAG, "Workbench initialized successfully")

            Handler().postDelayed({
                workbenchOverlay?.show()
                Log.d(TAG, "Workbench shown")
            }, 500)

        } catch (e: Exception) { Log.e(TAG, "Failed to init", e) }

        setupReceiver()
        setupAppReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg.isNotEmpty() && pkg != lastPackage) {
                if (pkg.startsWith("android.")) return
                if (pkg.startsWith("com.google.android.")) return
                if (pkg == "android") return
                if (pkg == "com.android.systemui") return
                if (pkg == packageName) return

                lastPackage = pkg
                Log.d(TAG, "Foreground app changed: $pkg")
                workbenchOverlay?.updateForegroundApp(pkg)
            }
        }
    }

    override fun onInterrupt() {}

    private fun setupReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "ru.queuejw.lumetro.SHOW_PANEL" -> sidebarManager?.showPanel()
                    "ru.queuejw.lumetro.HIDE_PANEL" -> sidebarManager?.hidePanel()
                    "ru.queuejw.lumetro.RESTART_SERVICE" -> restartService()
                    "ru.queuejw.lumetro.UPDATE_TILES" -> sidebarManager?.refreshTilesIfNeeded()
                    "ru.queuejw.lumetro.RELOAD_ICONS" -> sidebarManager?.reloadIconPack()
                    "ru.queuejw.lumetro.UPDATE_PANEL_BG" -> sidebarManager?.refreshPanelBackground()
                    "ru.queuejw.lumetro.EXPAND_NOTIFICATION" -> {
                        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        sidebarManager?.hidePanelImmediately()
                        sidebarManager?.destroyGestureStrip()
                        workbenchOverlay?.hide()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        sidebarManager?.createGestureStrip()
                        workbenchOverlay?.show()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("ru.queuejw.lumetro.SHOW_PANEL")
            addAction("ru.queuejw.lumetro.HIDE_PANEL")
            addAction("ru.queuejw.lumetro.RESTART_SERVICE")
            addAction("ru.queuejw.lumetro.UPDATE_TILES")
            addAction("ru.queuejw.lumetro.RELOAD_ICONS")
            addAction("ru.queuejw.lumetro.UPDATE_PANEL_BG")
            addAction("ru.queuejw.lumetro.EXPAND_NOTIFICATION")
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    private fun setupAppReceiver() {
        appReceiver = AppReceiver(
            onAppInstalled = { pkg -> sidebarManager?.onAppInstalled(pkg); sidebarManager?.refreshAppsIfNeeded() },
            onAppRemoved = { pkg -> sidebarManager?.onAppRemoved(pkg); sidebarManager?.refreshAppsIfNeeded() },
            onAppChanged = { sidebarManager?.refreshAppsIfNeeded() }
        )
        AppReceiver.register(this, appReceiver!!)
    }

    private fun restartService() {
        sidebarManager?.destroy()
        sidebarManager = SidebarManager(this).apply { createGestureStrip(); configureTouchPassthrough() }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        receiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        appReceiver?.let { try { AppReceiver.unregister(this, it) } catch (e: Exception) {} }
        appReceiver = null; receiver = null
        sidebarManager?.destroy(); sidebarManager = null
        workbenchOverlay?.cleanup(); workbenchOverlay = null
    }
}
