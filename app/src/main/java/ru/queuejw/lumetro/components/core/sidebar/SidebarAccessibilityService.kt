package ru.queuejw.lumetro.components.core.sidebar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import ru.queuejw.lumetro.components.core.receivers.AppReceiver
import ru.queuejw.lumetro.components.freeform.WorkbenchManager
import ru.queuejw.lumetro.components.freeform.WorkbenchSettings
import ru.queuejw.lumetro.components.freeform.gesture.LeftGestureStripManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper

class SidebarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SidebarA11yService"
        var sidebarManager: SidebarManager? = null
            private set
        var workbenchManager: WorkbenchManager? = null
            private set
        var gestureStripManager: LeftGestureStripManager? = null
            private set
        private var instance: SidebarAccessibilityService? = null

        fun getInstance(): SidebarAccessibilityService? = instance

        fun isServiceEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${SidebarAccessibilityService::class.java.name}"
            return try {
                val enabled = android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (enabled == 1) {
                    val enabledServices = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    )
                    enabledServices?.contains(serviceName) == true
                } else false
            } catch (e: Exception) { false }
        }

        fun toggleWorkbench() {
            try {
                workbenchManager?.toggle()
            } catch (e: Exception) {
                Log.e(TAG, "toggleWorkbench error", e)
            }
        }

        fun isWorkbenchShowing(): Boolean {
            return try {
                workbenchManager?.isShowing() ?: false
            } catch (e: Exception) {
                false
            }
        }

        fun openRecentTasks() {
            try {
                instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            } catch (e: Exception) {
                Log.e(TAG, "openRecentTasks error", e)
            }
        }

        fun updateForegroundApp(packageName: String) {
            try {
                workbenchManager?.updateForegroundApp(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "updateForegroundApp error", e)
            }
        }

        fun showWorkbench() {
            try {
                workbenchManager?.show()
            } catch (e: Exception) {
                Log.e(TAG, "showWorkbench error", e)
            }
        }

        fun refreshGestureStrip() {
            try {
                gestureStripManager?.show()
            } catch (e: Exception) {
                Log.e(TAG, "refreshGestureStrip error", e)
            }
        }
    }

    private var receiver: BroadcastReceiver? = null
    private var appReceiver: AppReceiver? = null
    private var lastPackage = ""
    private var isFullscreen = false
    private var fullscreenCheckHandler = Handler(Looper.getMainLooper())
    private var fullscreenCheckRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                notificationTimeout = 100
            }
            Log.d(TAG, "Accessibility service connected")

            try {
                // ========== 初始化 Shizuku ==========
                try {
                    ShizukuHelper.getInstance().init(applicationContext)
                    Log.d(TAG, "Shizuku initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku init failed", e)
                }

                // ========== 初始化 WorkbenchManager ==========
                workbenchManager = WorkbenchManager.init(this, this)
                workbenchManager?.show()
                Log.d(TAG, "Workbench initialized via WorkbenchManager")

                // ========== 从持久化设置恢复手势条配置 ==========
                val settings = WorkbenchSettings(this)
                val density = resources.displayMetrics.density
                
                gestureStripManager = LeftGestureStripManager.getInstance(this, this).apply {
                    stripWidth = (settings.gestureStripWidth * density).toInt()
                    stripHeight = if (settings.gestureStripHeight > 0) (settings.gestureStripHeight * density).toInt() else 0
                    stripOffset = (settings.gestureStripOffset * density).toInt()
                    stripAlpha = settings.gestureStripAlpha
                    
                    Log.d(TAG, "Restored gesture strip settings: width=${settings.gestureStripWidth}, height=${settings.gestureStripHeight}, offset=${settings.gestureStripOffset}, alpha=${settings.gestureStripAlpha}")
                    
                    onSwipeRight = {
                        try {
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            Log.d(TAG, "Left gesture: swipe right -> back")
                        } catch (e: Exception) {
                            Log.e(TAG, "Swipe right failed", e)
                            try {
                                SidebarAccessibilityService.getInstance()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            } catch (e2: Exception) {
                                Log.e(TAG, "Back fallback failed", e2)
                            }
                        }
                    }
                    
                    show()
                }
                Log.d(TAG, "Left gesture strip initialized with restored settings")

                // ========== SidebarManager 延迟初始化 ==========
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        sidebarManager = SidebarManager(this).apply {
                            createGestureStrip()
                            configureTouchPassthrough()
                        }
                        Log.d(TAG, "Sidebar initialized successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to init sidebar", e)
                    }
                }, 500)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to init", e)
            }

            setupReceiver()
            setupAppReceiver()

        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    try {
                        val packageName = event.packageName?.toString() ?: return
                        if (packageName != lastPackage) {
                            lastPackage = packageName
                            updateForegroundApp(packageName)
                            checkFullscreenState(packageName)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Window state changed error", e)
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    fullscreenCheckRunnable?.let { fullscreenCheckHandler.removeCallbacks(it) }
                    fullscreenCheckRunnable = Runnable {
                        checkFullscreenState(lastPackage)
                    }
                    fullscreenCheckHandler.postDelayed(fullscreenCheckRunnable!!, 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error", e)
        }
    }

    // ========== dp转px ==========
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    // ========== 获取窗口类型（兼容方法） ==========
    private fun getWindowType(window: AccessibilityWindowInfo): Int {
        return try {
            window.getType()
        } catch (e: Exception) {
            -1
        }
    }

    // ========== 检查全屏状态（排除 Lumetro 自身） ==========
    private fun checkFullscreenState(packageName: String) {
        try {
            // ========== 如果是 Lumetro 自身，不处理全屏检测 ==========
            if (packageName == applicationContext.packageName) {
                return
            }
            
            val windows = windows ?: emptyList()
            var navigationBarVisible = false
            var hasTargetAppWindow = false
            
            val screenHeight = resources.displayMetrics.heightPixels
            
            for (window in windows) {
                try {
                    val type = getWindowType(window)
                    
                    if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.getRoot()
                        val windowPackage = root?.getPackageName()?.toString()
                        if (windowPackage != packageName) {
                            continue
                        }
                        hasTargetAppWindow = true
                    }
                    
                    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                        val bounds = android.graphics.Rect()
                        window.getBoundsInScreen(bounds)
                        
                        if (bounds.top > screenHeight * 0.7f && bounds.height() < 150.dpToPx()) {
                            navigationBarVisible = true
                        }
                    }
                } catch (e: Exception) {
                    // 忽略
                }
            }
            
            if (!hasTargetAppWindow) return
            
            val isFullscreenNow = !navigationBarVisible
            
            if (isFullscreenNow && !isFullscreen) {
                isFullscreen = true
                Log.d(TAG, "Fullscreen detected ($packageName), hiding workbench")
                workbenchManager?.hide()
                workbenchManager?.hideAppsList()
            } 
            else if (!isFullscreenNow && isFullscreen) {
                isFullscreen = false
                Log.d(TAG, "Exited fullscreen ($packageName), showing workbench")
                workbenchManager?.show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkFullscreenState error", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        fullscreenCheckHandler.removeCallbacksAndMessages(null)
        try {
            receiver?.let { unregisterReceiver(it) }
            appReceiver?.let { unregisterReceiver(it) }

            LeftGestureStripManager.destroyInstance()
            gestureStripManager = null

            WorkbenchManager.destroyInstance()
            workbenchManager = null

            sidebarManager?.destroy()
            sidebarManager = null
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
    }

    private fun setupReceiver() {
        try {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    when (intent?.action) {
                        "ru.queuejw.lumetro.SHOW_PANEL" -> {
                            // 磁贴面板已移除，空操作
                        }
                        "ru.queuejw.lumetro.UPDATE_PANEL_BG" -> {
                            // 磁贴面板已移除，空操作
                        }
                        "ru.queuejw.lumetro.UPDATE_TILES" -> {
                            // 磁贴面板已移除，空操作
                        }
                        "ru.queuejw.lumetro.EXPAND_NOTIFICATION" -> {
                            try {
                                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                            } catch (e: Exception) {
                                Log.e(TAG, "EXPAND_NOTIFICATION error", e)
                            }
                        }
                        "ru.queuejw.lumetro.TOGGLE_WORKBENCH" -> {
                            try {
                                toggleWorkbench()
                            } catch (e: Exception) {
                                Log.e(TAG, "TOGGLE_WORKBENCH error", e)
                            }
                        }
                        "ru.queuejw.lumetro.SHOW_WORKBENCH" -> {
                            try {
                                workbenchManager?.showFast()
                            } catch (e: Exception) {
                                Log.e(TAG, "SHOW_WORKBENCH error", e)
                            }
                        }
                        "ru.queuejw.lumetro.UPDATE_GESTURE_STRIP" -> {
                            try {
                                refreshGestureStrip()
                            } catch (e: Exception) {
                                Log.e(TAG, "UPDATE_GESTURE_STRIP error", e)
                            }
                        }
                    }
                }
            }
            registerReceiver(receiver, IntentFilter().apply {
                addAction("ru.queuejw.lumetro.SHOW_PANEL")
                addAction("ru.queuejw.lumetro.UPDATE_PANEL_BG")
                addAction("ru.queuejw.lumetro.UPDATE_TILES")
                addAction("ru.queuejw.lumetro.EXPAND_NOTIFICATION")
                addAction("ru.queuejw.lumetro.TOGGLE_WORKBENCH")
                addAction("ru.queuejw.lumetro.SHOW_WORKBENCH")
                addAction("ru.queuejw.lumetro.UPDATE_GESTURE_STRIP")
            })
        } catch (e: Exception) {
            Log.e(TAG, "setupReceiver error", e)
        }
    }

    private fun setupAppReceiver() {
        try {
            appReceiver = AppReceiver(
                onAppInstalled = { pkg ->
                    Log.d(TAG, "App installed: $pkg")
                },
                onAppRemoved = { pkg ->
                    Log.d(TAG, "App removed: $pkg")
                },
                onAppChanged = {
                    Log.d(TAG, "App changed")
                }
            )
            registerReceiver(appReceiver, IntentFilter(Intent.ACTION_PACKAGE_CHANGED).apply {
                addDataScheme("package")
            })
        } catch (e: Exception) {
            Log.e(TAG, "setupAppReceiver error", e)
        }
    }
}