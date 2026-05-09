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

class SidebarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SidebarA11yService"
        var sidebarManager: SidebarManager? = null
            private set

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
    }

    private var receiver: BroadcastReceiver? = null
    private var appReceiver: AppReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        } catch (e: Exception) { Log.e(TAG, "Failed to init sidebar", e) }
        setupReceiver()
        setupAppReceiver()
    }

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
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        sidebarManager?.createGestureStrip()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        appReceiver?.let { try { AppReceiver.unregister(this, it) } catch (e: Exception) {} }
        appReceiver = null; receiver = null
        sidebarManager?.destroy(); sidebarManager = null
    }
}
