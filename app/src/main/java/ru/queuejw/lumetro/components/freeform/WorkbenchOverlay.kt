package ru.queuejw.lumetro.components.freeform

import ru.queuejw.lumetro.components.freeform.util.LogBuffer
import ru.queuejw.lumetro.components.freeform.util.TouchLogger
import android.widget.ScrollView
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.sqrt
import kotlin.math.atan2
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.core.sidebar.AppListPanel
import ru.queuejw.lumetro.components.core.sidebar.KeyboardKeyBindings
import ru.queuejw.lumetro.components.freeform.helper.FreeformHackHelper
import ru.queuejw.lumetro.components.freeform.util.U
import ru.queuejw.lumetro.model.App
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.BroadcastReceiver

class WorkbenchOverlay(
    private val service: AccessibilityService,
    private val manager: WorkbenchManager
) {

    private val context: Context = service.applicationContext
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val coroutineScope = manager.getCoroutineScope()
    private val iconLoader: IconLoader? = manager.getIconLoader()
    private val iconCache: MutableMap<String, Bitmap> = manager.getIconCache()
    private val settings = manager.getSettings()
    private val prefs = context.getSharedPreferences("workbench", Context.MODE_PRIVATE)

    // ========== dpToPx 扩展函数（类级别，全局可用） ==========
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private fun Float.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
    // =========================================================

    private var appMenuView: View? = null
private var appMenuParams: WindowManager.LayoutParams? = null
    
    private val perfLogEnabled = true   // 调试时打开

private fun perfLog(message: String) {
    if (!perfLogEnabled) return
    // 只写内存缓冲，不写磁盘
    LogBuffer.add("WorkbenchOverlay", message)
    Log.d("WorkbenchOverlay_Perf", message)
}

    private var overlayViewRef: WeakReference<FrameLayout>? = null
    private var appsPanelViewRef: WeakReference<FrameLayout>? = null
    private var pageIndicatorRef: WeakReference<LinearLayout>? = null

    private var preCreatedAppListPanel: AppListPanel? = null
    private var preCreatedView: View? = null
    private var isAppListReady = false
    private var preCreateJob: Job? = null

    private var cachedAppsHash: Int = 0

    private var appsPanelParams: WindowManager.LayoutParams? = null
    private var workbenchParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var isScreenOff = false
    private var barHeight = 0
    private val appContainer = LinearLayout(context)
    private var isContainerInitialized = false

    private var isAppsPanelShowing = false
    private var landscapeOverlay: LandscapeWorkbenchOverlay? = null

    private var touchDownX = 0f
    private var touchDownY = 0f

    private val cachedApps = mutableListOf<App>()

    private val MAX_SLOTS = 7
    private val MAX_APPS = 50
    private val appSlots = mutableListOf<Pair<String, String>>()
    private val workbenchAppSlots = mutableListOf<Pair<String, String>>()
    private val searchAppSlots = mutableListOf<Pair<String, String>>()
    private var isSearchMode = false
    private var currentPage = 0
    private val ITEMS_PER_PAGE = 6
    private val blacklist = mutableSetOf<String>()
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private enum class Mode {
        NORMAL, ADD, REMOVE
    }

    private val TAG = "WorkbenchOverlay"
    private var currentMode = Mode.NORMAL
    private var foregroundPackage = ""

    private var longPressRunnable: Runnable? = null
    private var longPressPackage = ""
    private var longPressName = ""
    private var longPressDownY = 0f
    private var isLongPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())

    private var lockOverlayView: View? = null
    private var lockOverlayParams: WindowManager.LayoutParams? = null
    private var isLockOverlayShowing = false
    private var lockTotalDistance = 0f
    private var lockLastX = 0f
    private var lockLastY = 0f

    // ========== 竖屏锁定图层 ==========
    private var lockTouchStartX = 0f
    private var lockTouchStartY = 0f

    private fun showLockOverlay() {
        if (isLockOverlayShowing) return

        val density = context.resources.displayMetrics.density

        val lockIcon = TextView(context).apply {
            text = "🚫"
            textSize = 20f
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = (16 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
        }

        val rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            addView(lockIcon)

            setOnTouchListener { _, event ->
                handleLockTouch(event)
                true
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        lockOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(rootView, lockOverlayParams)
            lockOverlayView = rootView
            isLockOverlayShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "showLockOverlay failed", e)
        }
    }

    fun hideLockOverlay() {
        if (!isLockOverlayShowing) return

        try {
            lockOverlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}

        lockOverlayView = null
        lockOverlayParams = null
        isLockOverlayShowing = false
    }

    fun isLockShowing(): Boolean = isLockOverlayShowing

    private fun handleLockTouch(event: MotionEvent) {
        val density = context.resources.displayMetrics.density
        val unlockThreshold = 500f * density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lockTouchStartX = event.x
                lockTouchStartY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val horizontalDistance = kotlin.math.abs(event.x - lockTouchStartX)
                val verticalDistance = kotlin.math.abs(event.y - lockTouchStartY)

                if (horizontalDistance > unlockThreshold || verticalDistance > unlockThreshold) {
                    hideLockOverlay()
                }
            }
            MotionEvent.ACTION_UP -> {
                val horizontalDistance = kotlin.math.abs(event.x - lockTouchStartX)
                val verticalDistance = kotlin.math.abs(event.y - lockTouchStartY)

                if (horizontalDistance > unlockThreshold || verticalDistance > unlockThreshold) {
                    hideLockOverlay()
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    hideWorkbenchOnScreenOff()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOff = false
                    restoreWorkbenchOnScreenOn()
                }
            }
        }
    }

    private val configChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                handler.post {
                    val dm = context.resources.displayMetrics
                    val isLandscape = dm.widthPixels > dm.heightPixels

                    val hadLock = isLockOverlayShowing || (landscapeOverlay?.isLockShowing() == true)

                    hideLockOverlay()
                    landscapeOverlay?.hideLockOverlay()

                    if (hadLock) {
                        if (isLandscape) {
                            hide()
                            landscapeOverlay?.show()
                            landscapeOverlay?.showLockOverlay()
                        } else {
                            landscapeOverlay?.hide()
                            show()
                            showLockOverlay()
                        }
                    } else {
                        if (isLandscape) {
                            hide()
                            landscapeOverlay?.show()
                        } else {
                            landscapeOverlay?.hide()
                            show()
                        }
                    }
                }
            }
        }
    }

    init {
        perfLog("WorkbenchOverlay init START")
        val initStart = System.currentTimeMillis()

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        try {
            context.registerReceiver(screenStateReceiver, filter)
            context.registerReceiver(configChangeReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }

        loadBlacklist()
        initSlots()
        initAppContainer()

        try {
            iconLoader?.getIconForPackage(context, context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load icon", e)
        }

        landscapeOverlay = LandscapeWorkbenchOverlay(service, manager)

        perfLog("WorkbenchOverlay init END: ${System.currentTimeMillis() - initStart}ms")

        preCreateAppListPanel()
    }

    private fun hideWorkbenchOnScreenOff() {
        try {
            landscapeOverlay?.onScreenOff()

            val view = overlayViewRef?.get()
            if (view != null && isShowing) {
                windowManager.removeView(view)
            }
            isShowing = false
        } catch (e: Exception) {
            Log.e(TAG, "hideWorkbenchOnScreenOff failed", e)
        }
    }

    private fun restoreWorkbenchOnScreenOn() {
        try {
            val dm = context.resources.displayMetrics
            if (dm.widthPixels > dm.heightPixels) {
                landscapeOverlay?.onScreenOn()
            } else {
                val view = overlayViewRef?.get()
                val params = workbenchParams
                if (view != null && params != null && view.parent == null) {
                    windowManager.addView(view, params)
                    isShowing = true
                } else {
                    show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreWorkbenchOnScreenOn failed", e)
            show()
        }
    }

    private fun preCreateAppListPanel() {
        perfLog("preCreateAppListPanel START")
        val startTime = System.currentTimeMillis()

        preCreateJob?.cancel()
        preCreateJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val apps = manager.getCachedApps()
                cachedApps.clear()
                cachedApps.addAll(apps)
                cachedAppsHash = apps.hashCode()
                perfLog("preCreateAppListPanel: got ${apps.size} apps in ${System.currentTimeMillis() - startTime}ms")

                val createStart = System.currentTimeMillis()
                val loader = iconLoader ?: IconLoader(false, null)
                val panel = AppListPanel(
                    context = context,
                    iconLoader = loader,
                    coroutineScope = coroutineScope,
                    onHidePanel = { hideAppsList() },
                    onRefreshTiles = {},
                    onShowSettings = {
                        try {
                            val intent = Intent(context, WorkbenchSettingsActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            hideAppsList()
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开设置", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShowFreezeDialog = {},
                    onPinApp = {},
                    onRefreshApps = {},
                    onAppsChanged = { apps ->
                        updateSearchSlots(apps)
                    },
                    onPageChangeRequested = { direction ->
                        if (direction > 0) {
                            nextPage()
                        } else {
                            previousPage()
                        }
                    },
                    onOpenNotificationCenter = {
                        hideAppsList()
                        try {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                        } catch (e: Exception) {
                            try {
                                val statusBarService = context.getSystemService(Context.STATUS_BAR_SERVICE)
                                val method = statusBarService.javaClass.getMethod("expandNotificationsPanel")
                                method.invoke(statusBarService)
                            } catch (e2: Exception) {}
                        }
                    },
                    onOpenControlCenter = {
                        hideAppsList()
                        try {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                        } catch (e: Exception) {
                            try {
                                val statusBarService = context.getSystemService(Context.STATUS_BAR_SERVICE)
                                val method = statusBarService.javaClass.getMethod("expandSettingsPanel")
                                method.invoke(statusBarService)
                            } catch (e2: Exception) {}
                        }
                    },
    onLockRequested = {
        hide()
        showLockOverlay()
    },
    onUnlockRequested = {
        if (!isShowing) {
            show()
        }
    },
    onKeyLongPress = { digit ->
        showKeyBindingDialog(digit)
    },
    overlayContext = service,
    onHideWorkbench = { hide() }
)
                

                val view = panel.createView()
                perfLog("preCreateAppListPanel: created view in ${System.currentTimeMillis() - createStart}ms")

                val loadStart = System.currentTimeMillis()
                panel.loadApps(apps)
                perfLog("preCreateAppListPanel: loaded data in ${System.currentTimeMillis() - loadStart}ms")

                withContext(Dispatchers.Main) {
                    preCreatedAppListPanel = panel
                    preCreatedView = view
                    isAppListReady = true
                    val elapsed = System.currentTimeMillis() - startTime
                    perfLog("preCreateAppListPanel END: total ${elapsed}ms")
                    Log.d(TAG, "AppListPanel pre-created in ${elapsed}ms")
                }
            } catch (e: Exception) {
                perfLog("preCreateAppListPanel FAILED: ${e.message}")
                Log.e(TAG, "Pre-create AppListPanel failed", e)
            }
        }
    }

    private suspend fun getAppsWithCache(): List<App> {
        val apps = manager.getCachedApps()
        val newHash = apps.hashCode()

        if (cachedAppsHash != newHash) {
            perfLog("getAppsWithCache: apps changed, updating cache")
            cachedApps.clear()
            cachedApps.addAll(apps)
            cachedAppsHash = newHash
        }

        return if (cachedApps.isNotEmpty()) cachedApps else apps
    }

    private fun updateSearchSlots(apps: List<App>) {
        searchAppSlots.clear()

        for (i in 0 until 6) {
            searchAppSlots.add("" to "")
        }

        for (app in apps.take(MAX_APPS)) {
            val pkg = app.mPackage ?: continue
            searchAppSlots.add(pkg to app.mName)
        }

        if (isSearchMode) {
            mergeSlots()
            currentPage = 1
            refreshAppSlots()
            updatePageIndicator()
        }
    }

    private fun mergeSlots() {
        appSlots.clear()
        appSlots.addAll(workbenchAppSlots.take(6))
        appSlots.addAll(searchAppSlots)
    }

    private fun loadBlacklist() {
        val saved = manager.getBlacklist()
        blacklist.clear()
        blacklist.addAll(saved)
    }

    private fun saveBlacklist() {
        manager.setBlacklist(blacklist)
    }

    private fun initSlots() {
        workbenchAppSlots.clear()
        searchAppSlots.clear()
        appSlots.clear()

        val settingsPkg = "com.android.settings"
        if (isAppInstalled(settingsPkg)) {
            for (i in 0 until 6) {
                workbenchAppSlots.add(settingsPkg to "设置")
            }
        } else {
            for (i in 0 until 6) {
                workbenchAppSlots.add("" to "")
            }
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun initAppContainer() {
        appContainer.removeAllViews()
        appContainer.orientation = LinearLayout.HORIZONTAL
        appContainer.gravity = Gravity.CENTER
        appContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        appSlots.clear()
        appSlots.addAll(workbenchAppSlots)
        refreshAppSlots()
        isContainerInitialized = true
    }

    fun updateForegroundApp(packageName: String) {
        if (packageName == context.packageName) return
        if (blacklist.contains(packageName)) return
        if (manager.isAppFrozen(packageName)) return
        if (currentMode != Mode.NORMAL) return

        foregroundPackage = packageName

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()

            workbenchAppSlots.removeAll { it.first == "" }

            workbenchAppSlots.removeAll { it.first == packageName }
            workbenchAppSlots.add(0, packageName to appName)

            while (workbenchAppSlots.size > MAX_APPS) {
                workbenchAppSlots.removeAt(workbenchAppSlots.size - 1)
            }

            while (workbenchAppSlots.size < 6) {
                workbenchAppSlots.add("" to "")
            }

            if (!isSearchMode) {
                appSlots.clear()
                appSlots.addAll(workbenchAppSlots)
            }

            refreshAppSlots()
        } catch (e: Exception) {
            Log.e(TAG, "updateForegroundApp failed", e)
        }
    }

    fun show() {
        if (isShowing) return

        val displayMetrics = context.resources.displayMetrics
        if (displayMetrics.widthPixels > displayMetrics.heightPixels) {
            perfLog("show: landscape mode, hiding workbench")
            return
        }

        if (overlayViewRef?.get() != null) {
            val view = overlayViewRef?.get()
            val params = workbenchParams
            if (view != null && params != null) {
                try {
                    windowManager.addView(view, params)
                    isShowing = true
                    perfLog("show: re-added existing view")
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "show: re-add failed", e)
                    overlayViewRef = null
                }
            }
        }
        if (isScreenOff) {
            prepareViews()
            return
        }

        currentPage = 0
        activateFreeformMode()

        val navBarHeight = getNavBarHeight()
        barHeight = navBarHeight

        val container = createWorkbenchView(displayMetrics.widthPixels)
        overlayViewRef = WeakReference(container)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val workbenchHeight = settings.height.dpToPx()
        workbenchParams = WindowManager.LayoutParams(
            displayMetrics.widthPixels,
            workbenchHeight,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(container, workbenchParams)
            isShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "show failed", e)
        }

        updatePageIndicator()
    }

    fun hide() {
        if (isAppsPanelShowing) {
            preCreatedAppListPanel?.clearSearch()
            hideAppsList()
        }
        currentMode = Mode.NORMAL
        currentPage = 0
        isSearchMode = false

        overlayViewRef?.get()?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "hide failed", e)
            }
        }
        overlayViewRef = null
        workbenchParams = null
        isShowing = false
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun isShowing(): Boolean = isShowing

    fun forceRefreshIcons() {
        perfLog("forceRefreshIcons")
        refreshAppSlots()

        if (isAppsPanelShowing) {
            val apps = manager.getCachedApps()
            cachedApps.clear()
            cachedApps.addAll(apps)
            cachedAppsHash = apps.hashCode()
            preCreatedAppListPanel?.refresh(apps)
            Log.d(TAG, "App list panel refreshed with ${apps.size} apps")
        }

        val apps = manager.getCachedApps()
        var count = 0
        for (app in apps) {
            app.mPackage?.let { pkg ->
                val icon = iconLoader?.getIconForPackage(context, pkg)
                if (icon != null) {
                    count++
                }
            }
        }
        Log.d(TAG, "Preloaded $count icons")
    }

    fun removeAppFromSlots(packageName: String) {
        perfLog("removeAppFromSlots: $packageName")
        workbenchAppSlots.removeAll { it.first == packageName }
        if (!isSearchMode) {
            appSlots.clear()
            appSlots.addAll(workbenchAppSlots)
        }
        refreshAppSlots()
    }

    // ========== 应用列表面板 ==========
    fun showAppsList() {
        perfLog("showAppsList START")

        if (isAppsPanelShowing) {
            hideAppsList()
            return
        }

        if (!isShowing) {
            show()
        }

        isSearchMode = true
        searchAppSlots.clear()

        for (i in 0 until 6) {
            searchAppSlots.add("" to "")
        }

        val allApps = cachedApps.ifEmpty { manager.getCachedApps() }
        for (app in allApps.take(MAX_APPS)) {
            val pkg = app.mPackage ?: continue
            searchAppSlots.add(pkg to app.mName)
        }

        mergeSlots()

        currentPage = 1
        refreshAppSlots()

        if (cachedApps.isEmpty()) {
            perfLog("showAppsList: cachedApps is empty, loading from manager")
            coroutineScope.launch(Dispatchers.IO) {
                val apps = manager.getCachedApps()
                cachedApps.clear()
                cachedApps.addAll(apps)
                cachedAppsHash = apps.hashCode()
                withContext(Dispatchers.Main) {
                    showAppsList()
                }
            }
            return
        }

        if (isAppListReady && preCreatedView != null && preCreatedAppListPanel != null) {
            showPreCreatedAppList()
            return
        }

        perfLog("showAppsList: pre-created not ready, using fallback")
        showAppsListFallback()
    }

    private fun getOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } catch (e: Exception) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
    }

    private fun showPreCreatedAppList() {
        val startTime = System.currentTimeMillis()
        perfLog("showPreCreatedAppList START")

        try {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels

            val widthRatio = settings.appListWidthRatio.coerceIn(0.5f, 1.0f)
            val heightRatio = settings.appListHeightRatio.coerceIn(0.1f, 2.0f)
            val verticalOffset = settings.appListVerticalOffset.coerceIn(-400, 400).dpToPx()
            val cornerRadius = settings.appListCornerRadius.coerceIn(0, 50).dpToPx().toFloat()
            val dimAlpha = settings.appListDimAlpha.coerceIn(0.0f, 0.9f)

            val appsWidth = (screenWidth * widthRatio).toInt()
            val workbenchHeight = settings.height.dpToPx()
            val availableHeight = screenHeight - workbenchHeight
            val appsHeight = (availableHeight * heightRatio).toInt()
            val topMargin = ((availableHeight - appsHeight) / 2) + verticalOffset

            val rootContainer = FrameLayout(context).apply {
                val alphaInt = (dimAlpha * 255).toInt()
                setBackgroundColor(Color.argb(alphaInt, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            touchDownX = event.x
                            touchDownY = event.y
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = kotlin.math.abs(event.x - touchDownX)
                            val dy = kotlin.math.abs(event.y - touchDownY)
                            if (dx < 20f && dy < 20f) {
                                hideAppsList()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }

                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        perfLog("Back key pressed, hiding app list")
                        hideAppsList()
                        true
                    } else {
                        false
                    }
                }
            }

            val contentContainer = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                        }
                    }
                    clipToOutline = true
                    elevation = 24f
                }
            }

            val contentParams = FrameLayout.LayoutParams(appsWidth, appsHeight)
            contentParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            contentParams.topMargin = topMargin
            contentContainer.layoutParams = contentParams

            val view = preCreatedView
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                contentContainer.addView(view)

                val currentApps = if (cachedApps.isNotEmpty()) cachedApps else runBlocking { manager.getCachedApps() }

                if (cachedApps.isEmpty() && currentApps.isNotEmpty()) {
                    cachedApps.clear()
                    cachedApps.addAll(currentApps)
                    cachedAppsHash = currentApps.hashCode()
                }

                preCreatedAppListPanel?.refresh(currentApps)
                perfLog("showPreCreatedAppList: refreshed with ${currentApps.size} apps")
            }

            rootContainer.addView(contentContainer)

            val windowType = getOverlayWindowType()
            perfLog("showPreCreatedAppList: using windowType=$windowType")

            appsPanelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            appsPanelViewRef = WeakReference(rootContainer)
            val addStart = System.currentTimeMillis()
            windowManager.addView(rootContainer, appsPanelParams)
            perfLog("showPreCreatedAppList: addView in ${System.currentTimeMillis() - addStart}ms")
            isAppsPanelShowing = true

            rootContainer.post {
                rootContainer.requestFocus()
            }

            val elapsed = System.currentTimeMillis() - startTime
            perfLog("showPreCreatedAppList END: ${elapsed}ms")
            Log.d(TAG, "Pre-created AppList shown in ${elapsed}ms")

        } catch (e: Exception) {
            perfLog("showPreCreatedAppList FAILED: ${e.message}")
            Log.e(TAG, "showPreCreatedAppList failed", e)
            showAppsListFallback()
        }
    }

    private fun showAppsListFallback() {
        val startTime = System.currentTimeMillis()
        perfLog("showAppsListFallback START")

        try {
            if (cachedApps.isEmpty()) {
                perfLog("showAppsListFallback: cachedApps is empty, loading...")
                val apps = runBlocking { manager.getCachedApps() }
                cachedApps.clear()
                cachedApps.addAll(apps)
                cachedAppsHash = apps.hashCode()
                if (apps.isEmpty()) {
                    perfLog("showAppsListFallback: no apps loaded!")
                    Toast.makeText(context, "无法加载应用列表", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels

            val widthRatio = settings.appListWidthRatio.coerceIn(0.5f, 1.0f)
            val heightRatio = settings.appListHeightRatio.coerceIn(0.1f, 2.0f)
            val verticalOffset = settings.appListVerticalOffset.coerceIn(0, 400).dpToPx()
            val cornerRadius = settings.appListCornerRadius.coerceIn(0, 50).dpToPx().toFloat()
            val dimAlpha = settings.appListDimAlpha.coerceIn(0.0f, 0.9f)

            val appsWidth = (screenWidth * widthRatio).toInt()
            val workbenchHeight = settings.height.dpToPx()
            val availableHeight = screenHeight - workbenchHeight
            val appsHeight = (availableHeight * heightRatio).toInt()
            val topMargin = ((availableHeight - appsHeight) / 2) + verticalOffset

            val rootContainer = FrameLayout(context).apply {
                val alphaInt = (dimAlpha * 255).toInt()
                setBackgroundColor(Color.argb(alphaInt, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            touchDownX = event.x
                            touchDownY = event.y
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = kotlin.math.abs(event.x - touchDownX)
                            val dy = kotlin.math.abs(event.y - touchDownY)
                            if (dx < 20f && dy < 20f) {
                                hideAppsList()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }

                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        perfLog("Back key pressed, hiding app list")
                        hideAppsList()
                        true
                    } else {
                        false
                    }
                }
            }

            val contentContainer = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                        }
                    }
                    clipToOutline = true
                    elevation = 24f
                }
            }

            val contentParams = FrameLayout.LayoutParams(appsWidth, appsHeight)
            contentParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            contentParams.topMargin = topMargin
            contentContainer.layoutParams = contentParams

            val createStart = System.currentTimeMillis()
            val loader = iconLoader ?: IconLoader(false, null)
            val panel = AppListPanel(
                context = context,
                iconLoader = loader,
                coroutineScope = coroutineScope,
                onHidePanel = { hideAppsList() },
                onRefreshTiles = {},
                onShowSettings = {
                    try {
                        val intent = Intent(context, WorkbenchSettingsActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        hideAppsList()
                    } catch (e: Exception) {
                        Toast.makeText(context, "无法打开设置", Toast.LENGTH_SHORT).show()
                    }
                },
                onShowFreezeDialog = {},
                onPinApp = {},
                onRefreshApps = {},
                onAppsChanged = { apps ->
                    updateSearchSlots(apps)
                },
                onPageChangeRequested = { direction ->
                    if (direction > 0) {
                        nextPage()
                    } else {
                        previousPage()
                    }
                },
                onOpenNotificationCenter = {
                    hideAppsList()
                    try {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    } catch (e: Exception) {
                        try {
                            val statusBarService = context.getSystemService(Context.STATUS_BAR_SERVICE)
                            val method = statusBarService.javaClass.getMethod("expandNotificationsPanel")
                            method.invoke(statusBarService)
                        } catch (e2: Exception) {}
                    }
                },
                onOpenControlCenter = {
                    hideAppsList()
                    try {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                    } catch (e: Exception) {
                        try {
                            val statusBarService = context.getSystemService(Context.STATUS_BAR_SERVICE)
                            val method = statusBarService.javaClass.getMethod("expandSettingsPanel")
                            method.invoke(statusBarService)
                        } catch (e2: Exception) {}
                    }
                },
    onLockRequested = {
        hide()
        showLockOverlay()
    },
    onUnlockRequested = {
        if (!isShowing) {
            show()
        }
    },
    onKeyLongPress = { digit ->
        showKeyBindingDialog(digit)
    },
    overlayContext = service,
    onHideWorkbench = { hide() }
)
            preCreatedAppListPanel = panel

            val view = panel.createView()
            preCreatedView = view
            perfLog("showAppsListFallback: created view in ${System.currentTimeMillis() - createStart}ms")

            contentContainer.addView(view)
            view.setOnTouchListener { _, _ -> false }

            val loadStart = System.currentTimeMillis()
            panel.loadApps(cachedApps)
            perfLog("showAppsListFallback: loaded ${cachedApps.size} apps in ${System.currentTimeMillis() - loadStart}ms")

            rootContainer.addView(contentContainer)

            val windowType = getOverlayWindowType()

            appsPanelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            appsPanelViewRef = WeakReference(rootContainer)
            windowManager.addView(rootContainer, appsPanelParams)
            isAppsPanelShowing = true
            isAppListReady = true

            rootContainer.post {
                rootContainer.requestFocus()
            }

            val elapsed = System.currentTimeMillis() - startTime
            perfLog("showAppsListFallback END: ${elapsed}ms")
            Log.d(TAG, "Fallback AppList shown in ${elapsed}ms")

        } catch (e: Exception) {
            perfLog("showAppsListFallback FAILED: ${e.message}")
            Log.e(TAG, "showAppsListFallback failed", e)
            Toast.makeText(context, "无法打开应用列表", Toast.LENGTH_SHORT).show()
        }
    }

    fun hideAppsList() {
        perfLog("hideAppsList")
        if (!isAppsPanelShowing) return
        preCreatedAppListPanel?.clearSearch()

        isSearchMode = false
        appSlots.clear()
        appSlots.addAll(workbenchAppSlots)
        currentPage = 0
        refreshAppSlots()
        updatePageIndicator()

        try {
            appsPanelViewRef?.get()?.let {
                windowManager.removeView(it)
                (it as? ViewGroup)?.removeAllViews()
            }
        } catch (e: Exception) {
            Log.e(TAG, "hideAppsList failed", e)
        }
        appsPanelViewRef = null
        appsPanelParams = null
        isAppsPanelShowing = false
    }

    fun toggleAppsList() {
        if (isAppsPanelShowing) {
            hideAppsList()
        } else {
            showAppsList()
        }
    }

    private fun prepareViews() {
        if (overlayViewRef?.get() == null) {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val container = createWorkbenchView(screenWidth)
            overlayViewRef = WeakReference(container)
            perfLog("prepareViews: views pre-created")
        }
    }

    // ========== 工作台视图 ==========
    private fun createWorkbenchView(screenWidth: Int): FrameLayout {
        val workbenchHeight = settings.height.dpToPx()
        barHeight = workbenchHeight

        val container = GestureContainer(context,
            onHomeGesture = { performHome() },
            onPrevPage = { previousPage() },
            onNextPage = { nextPage() },
            onRecentGesture = { performRecent() },
            onOpenSearch = { showAppsList() }
        ).apply {
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                elevation = 12f
            }
        }

        val glowLine = View(context).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            )
        }
        container.addView(glowLine)

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                screenWidth,
                workbenchHeight
            )
        }

        val fixedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (!isContainerInitialized) {
            initAppContainer()
        }
        appContainer.parent?.let { (it as? ViewGroup)?.removeView(appContainer) }

        appContainer.gravity = Gravity.CENTER
        fixedContainer.addView(appContainer)
        topBar.addView(fixedContainer)
        container.addView(topBar)

        return container
    }

    // ========== 手势容器 ==========
private inner class GestureContainer(
    context: Context,
    private val onHomeGesture: () -> Unit,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
    private val onRecentGesture: () -> Unit,
    private val onOpenSearch: () -> Unit
) : FrameLayout(context) {

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f
    private var gesturePhase = 0
    private var lastMoveY = 0f
    private var gestureCompleted = false
    private var isIntercepting = false
    private var isTimeout = false
    private var isDragging = false
    private var downTime = 0L

    private val handler = Handler(Looper.getMainLooper())

    private var timeoutRunnable: Runnable? = null

    private val SWIPE_THRESHOLD = 50f          // 从 25f 提到 50f
    private val TIMEOUT_MS = 500L
    private val ANGLE_MIN = 10.0
    private val ANGLE_MAX = 90.0
    private val DISTANCE_MIN = 15f
    private val LONG_PRESS_GUARD_MS = 400L

    private val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
    private val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                touchStartRawX = event.rawX
                touchStartRawY = event.rawY
                lastMoveY = event.y
                gesturePhase = 0
                gestureCompleted = false
                isIntercepting = false
                isTimeout = false
                isDragging = false
                downTime = System.currentTimeMillis()

                TouchLogger.log(
                    "IC_DOWN x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY} " +
                    "inMain=${isInMainArea(event.x)} inLeftBottom=${isInLeftBottomRegion(event.rawX, event.rawY)}"
                )

                timeoutRunnable = Runnable {
                    if (!gestureCompleted) {
                        isTimeout = true
                        gestureCompleted = true
                        isIntercepting = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        TouchLogger.log("IC_TIMEOUT")
                    }
                }
                handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)

                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureCompleted || isTimeout) return false

                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val distance = sqrt(dx * dx + dy * dy)
                val elapsed = System.currentTimeMillis() - downTime

                if (isIntercepting) {
                    TouchLogger.log("IC_MOVE already intercepting d=$distance")
                    return true
                }

                // 长按保护期：400ms 内小幅移动不拦截
                if (elapsed < LONG_PRESS_GUARD_MS && distance < SWIPE_THRESHOLD) {
                    TouchLogger.log("IC_MOVE guard d=$distance elapsed=$elapsed")
                    return false
                }

                if (distance > SWIPE_THRESHOLD) {
                    val inMainArea = isInMainArea(touchStartX)
                    val inLeftBottom = isInLeftBottomRegion(touchStartRawX, touchStartRawY)

                    TouchLogger.log(
                        "IC_MOVE exceed d=$distance dx=$dx dy=$dy elapsed=$elapsed " +
                        "inMain=$inMainArea inLeftBottom=$inLeftBottom"
                    )

                    if (inMainArea || inLeftBottom) {
                        isIntercepting = true
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        TouchLogger.log("IC_MOVE -> INTERCEPT")
                        return true
                    } else {
                        gestureCompleted = true
                        gesturePhase = 0
                        timeoutRunnable?.let { handler.removeCallbacks(it) }
                        TouchLogger.log("IC_MOVE -> NOT INTERCEPT (outside area)")
                        return false
                    }
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                TouchLogger.log(
                    "IC_UP/CANCEL action=${event.action} gestureCompleted=$gestureCompleted isTimeout=$isTimeout isIntercepting=$isIntercepting"
                )
                if (!gestureCompleted) {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    resetState()
                }
                return false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (gestureCompleted || isTimeout) return true

                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val distance = sqrt(dx * dx + dy * dy)

                if (distance < 10) return true

                if (isInMainArea(touchStartX)) {
                    if (gesturePhase == 0 && dy < -SWIPE_THRESHOLD) {
                        gesturePhase = 1
                        lastMoveY = event.y
                        TouchLogger.log("TE phase=1 (up-detect) dy=$dy")
                    }
                    if (gesturePhase == 1 && event.y - lastMoveY > SWIPE_THRESHOLD) {
                        gesturePhase = 2
                        TouchLogger.log("TE phase=2 (search gesture)")
                    }
                    if (gesturePhase == 0 && dx > SWIPE_THRESHOLD) {
                        gesturePhase = 4
                        TouchLogger.log("TE phase=4 (swipe left)")
                    }
                    if (gesturePhase == 0 && dx < -SWIPE_THRESHOLD) {
                        gesturePhase = 5
                        TouchLogger.log("TE phase=5 (swipe right)")
                    }
                }

                if (isInLeftBottomRegion(touchStartRawX, touchStartRawY)) {
                    val angle = kotlin.math.abs(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())))
                    if (distance > DISTANCE_MIN && angle in ANGLE_MIN..ANGLE_MAX) {
                        if (gesturePhase != 3) {
                            gesturePhase = 3
                            TouchLogger.log("TE phase=3 (recent) angle=$angle")
                        }
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                TouchLogger.log(
                    "TE_UP action=${event.action} phase=$gesturePhase isTimeout=$isTimeout gestureCompleted=$gestureCompleted"
                )
                timeoutRunnable?.let { handler.removeCallbacks(it) }

                if (isTimeout) {
                    resetState()
                    return true
                }

                if (!gestureCompleted) {
                    when (gesturePhase) {
                        1 -> {
                            gestureCompleted = true
                            TouchLogger.log("TE -> onOpenSearch")
                            onOpenSearch()
                            resetState()
                            return true
                        }
                        2 -> {
                            gestureCompleted = true
                            TouchLogger.log("TE -> onHomeGesture")
                            onHomeGesture()
                            resetState()
                            return true
                        }
                        3 -> {
                            gestureCompleted = true
                            TouchLogger.log("TE -> onRecentGesture")
                            onRecentGesture()
                            resetState()
                            return true
                        }
                        4 -> {
                            gestureCompleted = true
                            TouchLogger.log("TE -> onPrevPage")
                            onPrevPage()
                            resetState()
                            return true
                        }
                        5 -> {
                            gestureCompleted = true
                            TouchLogger.log("TE -> onNextPage")
                            onNextPage()
                            resetState()
                            return true
                        }
                        else -> {
                            TouchLogger.log("TE -> no phase matched")
                        }
                    }
                }

                resetState()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetState() {
        gesturePhase = 0
        gestureCompleted = false
        isIntercepting = false
        isTimeout = false
        isDragging = false
        timeoutRunnable = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun isInMainArea(x: Float): Boolean {
        val width = width.toFloat()
        return x > width / 4
    }

    private fun isInLeftBottomRegion(rawX: Float, rawY: Float): Boolean {
        val inLeft = rawX < screenWidth / 4
        val inBottom = rawY > screenHeight / 2
        return inLeft && inBottom
    }
}

    // ========== 分页功能 ==========
    private fun previousPage() {
        val filtered = getFilteredApps()
        val totalPages = ((filtered.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        currentPage = (currentPage - 1 + totalPages) % totalPages
        refreshAppSlots()
        updatePageIndicator()
    }

    private fun nextPage() {
        val filtered = getFilteredApps()
        val totalPages = ((filtered.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        currentPage = (currentPage + 1) % totalPages
        refreshAppSlots()
        updatePageIndicator()
    }

    private fun getFilteredApps(): List<Pair<String, String>> {
        return when (currentMode) {
            Mode.NORMAL, Mode.ADD -> {
                if (isSearchMode) {
                    appSlots.filter {
                        it.first.isNotEmpty() && !blacklist.contains(it.first)
                    }
                } else {
                    appSlots.filter {
                        it.first.isNotEmpty() && !blacklist.contains(it.first) && !manager.isAppFrozen(it.first)
                    }
                }
            }
            Mode.REMOVE -> {
                blacklist.map { pkg ->
                    try {
                        val pm = context.packageManager
                        val info = pm.getApplicationInfo(pkg, 0)
                        val name = pm.getApplicationLabel(info).toString()
                        pkg to name
                    } catch (e: Exception) {
                        pkg to pkg
                    }
                }
            }
        }
    }

    private fun getCurrentPageApps(): List<Pair<String, String>> {
        val filtered = getFilteredApps()
        val start = currentPage * ITEMS_PER_PAGE
        val end = minOf(start + ITEMS_PER_PAGE, filtered.size)
        return if (start < filtered.size) filtered.subList(start, end) else emptyList()
    }

    private fun getTotalPages(): Int {
        val total = getFilteredApps().size
        return ((total + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
    }

    private fun updatePageIndicator() {
        val totalPages = getTotalPages()
        if (totalPages <= 1) {
            pageIndicatorRef?.get()?.visibility = View.GONE
            return
        }

        var indicator = pageIndicatorRef?.get()
        if (indicator == null) {
            indicator = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.VISIBLE
                val dotSize = 2.dpToPx()
                setPadding(0, 0, 0, 0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    dotSize
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 1.dpToPx()
                }
            }
            overlayViewRef?.get()?.addView(indicator)
            pageIndicatorRef = WeakReference(indicator)
        }

        indicator?.let {
            it.removeAllViews()
            it.visibility = View.VISIBLE

            val dotSize = 2.dpToPx()
            val spacing = 2.dpToPx()
            val halfSpacing = spacing / 2

            for (i in 0 until totalPages) {
                val dot = View(context).apply {
                    val lp = LinearLayout.LayoutParams(dotSize, dotSize)
                    lp.setMargins(halfSpacing, 0, halfSpacing, 0)
                    layoutParams = lp
                    setBackgroundColor(if (i == currentPage) Color.WHITE else Color.parseColor("#33FFFFFF"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, dotSize / 2f)
                            }
                        }
                        clipToOutline = true
                    }
                }
                it.addView(dot)
            }

            val params = it.layoutParams as? FrameLayout.LayoutParams
            params?.let { p ->
                p.height = dotSize
                p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                p.bottomMargin = 1.dpToPx()
                it.layoutParams = p
            }
        }
    }

    private fun resetWorkbench() {
        currentPage = 0
        isSearchMode = false
        appSlots.clear()
        appSlots.addAll(workbenchAppSlots)
        refreshAppSlots()
        updatePageIndicator()
    }

    fun refreshAppSlots() {
        appContainer.removeAllViews()

        val screenWidth = context.resources.displayMetrics.widthPixels
        val slotWidth = screenWidth / MAX_SLOTS

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 0)
        }

        val currentApps = getCurrentPageApps()

        val slot1View = createSlot1View(slotWidth)
        wrapper.addView(slot1View)

        val maxItems = MAX_SLOTS - 1
        for (i in 0 until maxItems) {
            if (i < currentApps.size) {
                val (pkg, name) = currentApps[i]
                val isForeground = (pkg == foregroundPackage)
                wrapper.addView(createAppItem(pkg, name, isForeground, slotWidth))
            } else {
                wrapper.addView(createEmptySlot(slotWidth))
            }
        }

        appContainer.addView(wrapper)
        updatePageIndicator()
    }

    // ========== 添加 createAppItem 方法 ==========
    // ========== 创建应用图标 ==========
private fun createAppItem(packageName: String, appName: String, isForeground: Boolean, slotWidth: Int): View {
    // ========== 空白占位符处理 ==========
    if (packageName.isEmpty()) {
        return createEmptySlot(slotWidth)
    }

    val isFrozen = manager.isAppFrozen(packageName)

    val item = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            slotWidth,
            LinearLayout.LayoutParams.MATCH_PARENT
        )

        when {
            currentMode == Mode.ADD && !blacklist.contains(packageName) -> setBackgroundColor(Color.parseColor("#22FFFFFF"))
            currentMode == Mode.REMOVE && blacklist.contains(packageName) -> setBackgroundColor(Color.parseColor("#33FF4444"))
            isForeground -> setBackgroundColor(Color.parseColor("#33FF8800"))
            else -> setBackgroundColor(Color.TRANSPARENT)
        }

        setOnClickListener {
            when (currentMode) {
                Mode.NORMAL -> {
                    if (packageName.isNotEmpty()) {
                        if (isFrozen) {
                            unfreezeApp(packageName, appName)
                        } else {
                            switchToApp(packageName, appName)
                        }
                    }
                }
                Mode.ADD -> {
                    if (packageName.isNotEmpty() && !blacklist.contains(packageName)) {
                        blacklist.add(packageName)
                        saveBlacklist()
                        workbenchAppSlots.removeAll { it.first == packageName }
                        if (!isSearchMode) {
                            appSlots.clear()
                            appSlots.addAll(workbenchAppSlots)
                        }
                        refreshAppSlots()
                        Toast.makeText(context, "已屏蔽 $appName", Toast.LENGTH_SHORT).show()
                    } else if (blacklist.contains(packageName)) {
                        Toast.makeText(context, "$appName 已在黑名单中", Toast.LENGTH_SHORT).show()
                    }
                }
                Mode.REMOVE -> {
                    if (packageName.isNotEmpty() && blacklist.contains(packageName)) {
                        blacklist.remove(packageName)
                        saveBlacklist()
                        refreshAppSlots()
                        Toast.makeText(context, "已移除 $appName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (packageName.isNotEmpty()) {
                        longPressPackage = packageName
                        longPressName = appName
                        longPressDownY = event.rawY
                        isLongPressTriggered = false

                        val runnable = Runnable {
                            if (longPressRunnable != null) {
                                isLongPressTriggered = true
                                try {
                                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(30, 50))
                                    } else {
                                        vibrator.vibrate(30)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Vibrate failed", e)
                                }

                                val actionText = if (isFrozen) "上滑解冻" else "上滑冻结"
                                Toast.makeText(context, "$actionText $appName", Toast.LENGTH_SHORT).show()
                                view.setBackgroundColor(Color.parseColor("#44FFAA00"))
                            }
                        }
                        longPressRunnable = runnable
                        handler.postDelayed(runnable, 500)
                    }
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = longPressDownY - event.rawY
                    if (Math.abs(dy) > 30 && !isLongPressTriggered) {
                        handler.removeCallbacksAndMessages(null)
                        longPressRunnable = null
                        isLongPressTriggered = false
                        longPressPackage = ""
                        longPressName = ""
                        view.setBackgroundColor(Color.TRANSPARENT)
                        when {
                            currentMode == Mode.ADD && !blacklist.contains(packageName) -> view.setBackgroundColor(Color.parseColor("#22FFFFFF"))
                            currentMode == Mode.REMOVE && blacklist.contains(packageName) -> view.setBackgroundColor(Color.parseColor("#33FF4444"))
                            isForeground -> view.setBackgroundColor(Color.parseColor("#33FF8800"))
                            else -> view.setBackgroundColor(Color.TRANSPARENT)
                        }
                        return@setOnTouchListener false
                    }

                    if (isLongPressTriggered && longPressPackage.isNotEmpty()) {
                        if (dy > 60) {
                            handler.removeCallbacksAndMessages(null)
                            longPressRunnable = null

                            if (isFrozen) {
                                unfreezeApp(packageName, appName)
                            } else {
                                freezeApp(packageName, appName)
                            }

                            isLongPressTriggered = false
                            longPressPackage = ""
                            longPressName = ""
                            return@setOnTouchListener true
                        }
                    }
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    longPressRunnable = null

                    if (isLongPressTriggered) {
                        when {
                            isFrozen -> view.setBackgroundColor(Color.parseColor("#33AADDFF"))
                            currentMode == Mode.ADD && !blacklist.contains(packageName) -> view.setBackgroundColor(Color.parseColor("#22FFFFFF"))
                            currentMode == Mode.REMOVE && blacklist.contains(packageName) -> view.setBackgroundColor(Color.parseColor("#33FF4444"))
                            isForeground -> view.setBackgroundColor(Color.parseColor("#33FF8800"))
                            else -> view.setBackgroundColor(Color.TRANSPARENT)
                        }
                    }

                    isLongPressTriggered = false
                    longPressPackage = ""
                    longPressName = ""
                    false
                }

                else -> false
            }
        }
    }

    if (packageName.isNotEmpty()) {
        val iconSize = if (isForeground) 32.dpToPx() else 28.dpToPx()

        // ========== 图标容器（用于添加角标） ==========
        val iconContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }

        val iconView = ImageView(context).apply {
            val bitmap = iconLoader?.getIconForPackage(context, packageName)
            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                setImageDrawable(fallbackIcon(packageName))
            }
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
        }
        iconContainer.addView(iconView)

        // ========== 冻结角标（右上角❄） ==========
        if (isFrozen) {
            val badgeSize = 10.dpToPx()
            val badge = TextView(context).apply {
                text = "❄"
                textSize = 7f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#FF4488FF"))
                layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
            }
            iconContainer.addView(badge)
        }

        item.addView(iconContainer)

        val nameView = TextView(context).apply {
            text = appName
            textSize = 7f
            setTextColor(if (isForeground) Color.WHITE else Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 2.dpToPx(), 0, 0)
            maxLines = 1
            if (isForeground) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }
        item.addView(nameView)
    }

    return item
}


    private fun createSlot1View(slotWidth: Int): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                slotWidth,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            when (currentMode) {
                Mode.NORMAL -> setBackgroundColor(Color.TRANSPARENT)
                Mode.ADD -> setBackgroundColor(Color.parseColor("#22FFFFFF"))
                Mode.REMOVE -> setBackgroundColor(Color.parseColor("#33FF4444"))
            }

            setOnClickListener {
                when (currentMode) {
                    Mode.NORMAL -> {
                        showAppsList()
                    }
                    Mode.ADD -> {
                        currentMode = Mode.NORMAL
                        refreshAppSlots()
                        Toast.makeText(context, "退出添加模式", Toast.LENGTH_SHORT).show()
                    }
                    Mode.REMOVE -> {
                        currentMode = Mode.NORMAL
                        refreshAppSlots()
                        Toast.makeText(context, "退出移除模式", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            setOnLongClickListener {
                when (currentMode) {
                    Mode.NORMAL -> {
                        currentMode = Mode.ADD
                        refreshAppSlots()
                        Toast.makeText(context, "添加模式（点击应用加入黑名单）", Toast.LENGTH_SHORT).show()
                        true
                    }
                    Mode.ADD -> {
                        currentMode = Mode.REMOVE
                        refreshAppSlots()
                        Toast.makeText(context, "移除模式（点击移除黑名单）", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
        }

        when (currentMode) {
            Mode.NORMAL -> {
                val iconView = createWindowsIcon()
                item.addView(iconView)

                val nameView = TextView(context).apply {
                    text = "搜索"
                    textSize = 7f
                    setTextColor(Color.parseColor("#CCFFFFFF"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 2.dpToPx(), 0, 0)
                    maxLines = 1
                }
                item.addView(nameView)
            }
            Mode.ADD -> {
                val iconView = TextView(context).apply {
                    text = "+"
                    textSize = 24f
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                item.addView(iconView)

                val nameView = TextView(context).apply {
                    text = "添加"
                    textSize = 7f
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 2.dpToPx(), 0, 0)
                    maxLines = 1
                }
                item.addView(nameView)
            }
            Mode.REMOVE -> {
                val iconView = TextView(context).apply {
                    text = "✕"
                    textSize = 24f
                    setTextColor(Color.parseColor("#88FF4444"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                item.addView(iconView)

                val nameView = TextView(context).apply {
                    text = "退出"
                    textSize = 7f
                    setTextColor(Color.parseColor("#88FF4444"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 2.dpToPx(), 0, 0)
                    maxLines = 1
                }
                item.addView(nameView)
            }
        }

        return item
    }

    private fun createWindowsIcon(): ImageView {
        return ImageView(context).apply {
            try {
                val resId = context.resources.getIdentifier("windows11_logo", "mipmap", context.packageName)
                if (resId != 0) {
                    setImageResource(resId)
                } else {
                    setImageResource(android.R.drawable.ic_menu_recent_history)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createWindowsIcon failed", e)
                setImageResource(android.R.drawable.ic_menu_recent_history)
            }
            val iconSize = 32.dpToPx()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
    }

    private val keyBindings = KeyboardKeyBindings.getInstance(context)

    private var bindingDialogView: View? = null
    private var bindingDialogParams: WindowManager.LayoutParams? = null

    private fun showKeyBindingDialog(digit: String) {
        try {
            dismissKeyBindingDialog()

            val density = context.resources.displayMetrics.density

            val rootLayout = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
                setOnClickListener {
                    dismissKeyBindingDialog()
                }
            }

            val contentLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FF1A1A1A"))
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            }

            val titleView = TextView(context).apply {
                text = "设置 $digit 键"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16.dpToPx())
            }
            contentLayout.addView(titleView)

            val selectAppBtn = TextView(context).apply {
                text = "📱 绑定应用"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                setBackgroundColor(Color.parseColor("#FF2A2A2A"))
                setOnClickListener {
                    dismissKeyBindingDialog()
                    showAppPickerDialog(digit)
                }
            }
            contentLayout.addView(selectAppBtn)

            val selectShortcutBtn = TextView(context).apply {
                text = "⚡ 绑定快捷方式"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                setBackgroundColor(Color.parseColor("#FF2A2A2A"))
                setOnClickListener {
                    dismissKeyBindingDialog()
                    showShortcutPickerDialog(digit)
                }
            }
            val shortcutLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx()
            }
            selectShortcutBtn.layoutParams = shortcutLp
            contentLayout.addView(selectShortcutBtn)

            val currentBinding = keyBindings.getBinding(digit)
            if (currentBinding.type != KeyboardKeyBindings.BindingType.NONE) {
                val clearBtn = TextView(context).apply {
                    text = "❌ 清除绑定（${currentBinding.label}）"
                    textSize = 15f
                    setTextColor(Color.parseColor("#FFFF6666"))
                    setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                    setBackgroundColor(Color.parseColor("#FF2A2A2A"))
                    setOnClickListener {
                        keyBindings.clearBinding(digit)
                        Toast.makeText(context, "$digit 键绑定已清除", Toast.LENGTH_SHORT).show()
                        dismissKeyBindingDialog()
                        refreshAppList()
                    }
                }
                val clearLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8.dpToPx()
                }
                clearBtn.layoutParams = clearLp
                contentLayout.addView(clearBtn)
            }

            val cancelBtn = TextView(context).apply {
                text = "取消"
                textSize = 15f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                setOnClickListener {
                    dismissKeyBindingDialog()
                }
            }
            val cancelLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16.dpToPx()
            }
            cancelBtn.layoutParams = cancelLp
            contentLayout.addView(cancelBtn)

            val contentParams = FrameLayout.LayoutParams(
                (280 * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            contentLayout.layoutParams = contentParams

            rootLayout.addView(contentLayout)

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }

            bindingDialogParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(rootLayout, bindingDialogParams)
            bindingDialogView = rootLayout
        } catch (e: Exception) {
            Log.e(TAG, "showKeyBindingDialog failed", e)
        }
    }

    private fun dismissKeyBindingDialog() {
        bindingDialogView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        bindingDialogView = null
        bindingDialogParams = null
    }

    private var appPickerView: View? = null
    private var appPickerParams: WindowManager.LayoutParams? = null

    private fun showAppPickerDialog(digit: String) {
        try {
            dismissAppPicker()

            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { pm.getApplicationLabel(it).toString() }

            val density = context.resources.displayMetrics.density

            val rootLayout = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
            }

            val contentLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FF1A1A1A"))
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            }

            val titleView = TextView(context).apply {
                text = "选择应用绑定到 $digit 键"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16.dpToPx())
            }
            contentLayout.addView(titleView)

            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                val itemView = TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                    setOnClickListener {
                        keyBindings.setBinding(digit, KeyboardKeyBindings.Binding(
                            type = KeyboardKeyBindings.BindingType.APP,
                            value = app.packageName,
                            label = label
                        ))
                        Toast.makeText(context, "$digit 键已绑定到 $label", Toast.LENGTH_SHORT).show()
                        dismissAppPicker()
                        refreshAppList()
                    }
                }
                listContainer.addView(itemView)
            }

            scrollView.addView(listContainer)
            contentLayout.addView(scrollView)

            val cancelBtn = TextView(context).apply {
                text = "取消"
                textSize = 15f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                setOnClickListener {
                    dismissAppPicker()
                }
            }
            contentLayout.addView(cancelBtn)

            val contentParams = FrameLayout.LayoutParams(
                (300 * density).toInt(),
                (500 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER
            }
            contentLayout.layoutParams = contentParams

            rootLayout.addView(contentLayout)

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }

            appPickerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(rootLayout, appPickerParams)
            appPickerView = rootLayout
        } catch (e: Exception) {
            Log.e(TAG, "showAppPickerDialog failed", e)
        }
    }

    private fun dismissAppPicker() {
        appPickerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        appPickerView = null
        appPickerParams = null
    }

    private var shortcutPickerView: View? = null
    private var shortcutPickerParams: WindowManager.LayoutParams? = null

    private fun showShortcutPickerDialog(digit: String) {
        try {
            dismissShortcutPicker()

            val density = context.resources.displayMetrics.density

            val rootLayout = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
            }

            val contentLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FF1A1A1A"))
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            }

            val titleView = TextView(context).apply {
                text = "选择快捷方式绑定到 $digit 键"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16.dpToPx())
            }
            contentLayout.addView(titleView)

            val shortcuts = listOf(
                Triple("🌐 打开浏览器", "android.intent.action.VIEW|https://www.google.com", "打开浏览器"),
                Triple("📷 打开相机", "android.media.action.IMAGE_CAPTURE", "打开相机"),
                Triple("📞 打开拨号", "android.intent.action.DIAL", "打开拨号"),
                Triple("💬 打开短信", "android.intent.action.MAIN|android.intent.category.APP_MESSAGING", "打开短信"),
                Triple("⚙️ 打开设置", "android.settings.SETTINGS", "打开设置"),
                Triple("📶 打开WiFi设置", "android.settings.WIFI_SETTINGS", "WiFi设置"),
                Triple("🔵 打开蓝牙设置", "android.settings.BLUETOOTH_SETTINGS", "蓝牙设置"),
                Triple("🔋 打开电池设置", "android.settings.BATTERY_SAVER_SETTINGS", "电池设置"),
                Triple("📱 打开应用设置", "android.settings.APPLICATION_SETTINGS", "应用设置"),
                Triple("🎵 打开音乐", "android.intent.action.MUSIC_PLAYER", "打开音乐")
            )

            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            for ((displayName, intentUri, label) in shortcuts) {
                val itemView = TextView(context).apply {
                    text = displayName
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                    setOnClickListener {
                        keyBindings.setBinding(digit, KeyboardKeyBindings.Binding(
                            type = KeyboardKeyBindings.BindingType.SHORTCUT,
                            value = intentUri,
                            label = label
                        ))
                        Toast.makeText(context, "$digit 键已绑定到 $label", Toast.LENGTH_SHORT).show()
                        dismissShortcutPicker()
                        refreshAppList()
                    }
                }
                listContainer.addView(itemView)
            }

            scrollView.addView(listContainer)
            contentLayout.addView(scrollView)

            val cancelBtn = TextView(context).apply {
                text = "取消"
                textSize = 15f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                setOnClickListener {
                    dismissShortcutPicker()
                }
            }
            contentLayout.addView(cancelBtn)

            val contentParams = FrameLayout.LayoutParams(
                (300 * density).toInt(),
                (500 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER
            }
            contentLayout.layoutParams = contentParams

            rootLayout.addView(contentLayout)

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }

            shortcutPickerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(rootLayout, shortcutPickerParams)
            shortcutPickerView = rootLayout
        } catch (e: Exception) {
            Log.e(TAG, "showShortcutPickerDialog failed", e)
        }
    }

    private fun dismissShortcutPicker() {
        shortcutPickerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        shortcutPickerView = null
        shortcutPickerParams = null
    }

    private fun refreshAppList() {
        preCreatedAppListPanel?.refresh(cachedApps)
    }

    private fun createEmptySlot(slotWidth: Int): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                slotWidth,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }
    }

    private fun freezeApp(pkg: String, name: String) {
        manager.freezeApp(pkg) { success ->
            if (success) {
                refreshAppSlots()
            }
        }
    }

    private fun unfreezeApp(pkg: String, name: String) {
        manager.unfreezeApp(pkg) { success ->
            if (success) {
                refreshAppSlots()
            }
        }
    }

    private fun fallbackIcon(packageName: String): Drawable? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            context.getDrawable(android.R.drawable.sym_def_app_icon)
        }
    }

    private fun switchToApp(packageName: String, appName: String) {
        try {
            val tasks = activityManager.getRunningTasks(50)
            for (task in tasks) {
                val topActivity = task.topActivity
                if (topActivity != null && topActivity.packageName == packageName) {
                    activityManager.moveTaskToFront(task.id, 0)
                    Toast.makeText(context, appName, Toast.LENGTH_SHORT).show()
                    resetWorkbench()
                    hideAppsList()
                    return
                }
            }

            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Toast.makeText(context, appName, Toast.LENGTH_SHORT).show()
                resetWorkbench()
                hideAppsList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToApp failed", e)
            Toast.makeText(context, "启动失败", Toast.LENGTH_SHORT).show()
        }

        workbenchAppSlots.removeAll { it.first == "" }
        workbenchAppSlots.removeAll { it.first == packageName }
        workbenchAppSlots.add(0, packageName to appName)
        while (workbenchAppSlots.size > MAX_APPS) {
            workbenchAppSlots.removeAt(workbenchAppSlots.size - 1)
        }

        while (workbenchAppSlots.size < 6) {
            workbenchAppSlots.add("" to "")
        }

        isSearchMode = false
        appSlots.clear()
        appSlots.addAll(workbenchAppSlots)
        currentPage = 0
        refreshAppSlots()
    }

    private fun getNavBarHeight(): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        if (result == 0) {
            val statusBarHeight = getStatusBarHeight()
            result = (statusBarHeight * 1.5f).toInt()
        }
        if (result == 0) {
            result = (120 * context.resources.displayMetrics.density).toInt()
        }
        return result
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        if (result == 0) {
            result = (80 * context.resources.displayMetrics.density).toInt()
        }
        return result
    }

    private fun activateFreeformMode() {
        if (U.canDrawOverlays(context) && U.hasFreeformSupport(context)) {
            if (!FreeformHackHelper.getInstance().isFreeformHackActive()) {
                FreeformHackHelper.getInstance().reset()
                U.stopFreeformHack(context)
                U.startFreeformHack(context, true)
            }
        }
    }

    private fun performHome() {
        try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.e(TAG, "performHome via service failed", e)
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(homeIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "performHome via intent failed", e2)
            }
        }
        resetWorkbench()
    }

    private fun performRecent() {
        try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        } catch (e: Exception) {
            Log.e(TAG, "performRecent via service failed", e)
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                intent.setPackage("com.android.systemui")
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "performRecent via intent failed", e2)
                Toast.makeText(context, "无法打开最近任务", Toast.LENGTH_SHORT).show()
            }
        }
        resetWorkbench()
    }

    fun cleanup() {
    perfLog("cleanup")
    try {
        context.unregisterReceiver(screenStateReceiver)
        context.unregisterReceiver(configChangeReceiver)
    } catch (e: Exception) {
        Log.e(TAG, "unregisterReceiver failed", e)
    }
    preCreateJob?.cancel()
    preCreateJob = null
    hide()
    landscapeOverlay?.cleanup()
    if (isAppsPanelShowing) {
        preCreatedAppListPanel?.clearSearch()
        hideAppsList()
    }
    
    // ========== 顺便清理其他浮层 ==========
    dismissKeyBindingDialog()
    dismissAppPicker()
    dismissShortcutPicker()
    hideLockOverlay()
    handler.removeCallbacksAndMessages(null)
}
}