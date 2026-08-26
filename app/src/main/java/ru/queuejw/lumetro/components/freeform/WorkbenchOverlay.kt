package ru.queuejw.lumetro.components.freeform

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
import ru.queuejw.lumetro.components.freeform.helper.FreeformHackHelper
import ru.queuejw.lumetro.components.freeform.util.U
import ru.queuejw.lumetro.model.App
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // ========== 性能日志 ==========
    private val perfLogEnabled = false
    
    private fun perfLog(message: String) {
        if (!perfLogEnabled) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] [WorkbenchOverlay] $message"
        Log.d("WorkbenchOverlay_Perf", logMessage)
        
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "workbench_performance_log.txt")
            file.appendText("$logMessage\n")
        } catch (e: Exception) {
            // 忽略
        }
    }

    // ========== 使用 WeakReference 防止内存泄漏 ==========
    private var overlayViewRef: WeakReference<FrameLayout>? = null
    private var appsPanelViewRef: WeakReference<FrameLayout>? = null
    private var pageIndicatorRef: WeakReference<LinearLayout>? = null
    
    // ========== 预创建的 AppListPanel ==========
    private var preCreatedAppListPanel: AppListPanel? = null
    private var preCreatedView: View? = null
    private var isAppListReady = false
    private var preCreateJob: Job? = null
    
    // ========== 缓存的应用列表哈希值 ==========
    private var cachedAppsHash: Int = 0
    
    private var appsPanelParams: WindowManager.LayoutParams? = null
    private var workbenchParams: WindowManager.LayoutParams? = null
    
    private var isShowing = false
    private var isScreenOff = false
    private var barHeight = 0
    private val appContainer = LinearLayout(context)
    private var isContainerInitialized = false

    // ========== 应用列表面板 ==========
    private var isAppsPanelShowing = false
    private val cachedApps = mutableListOf<App>()

    // ========== 数据 ==========
    private val MAX_SLOTS = 7
    private val MAX_APPS = 50
    private val appSlots = mutableListOf<Pair<String, String>>()
    private var currentPage = 0
    private val ITEMS_PER_PAGE = 6
    private val blacklist = mutableSetOf<String>()
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // ========== 模式 ==========
    private enum class Mode {
        NORMAL, ADD, REMOVE
    }
    
    private val TAG = "WorkbenchOverlay"
    private var currentMode = Mode.NORMAL
    private var foregroundPackage = ""

    // ========== 长按冻结 ==========
    private var longPressRunnable: Runnable? = null
    private var longPressPackage = ""
    private var longPressName = ""
    private var longPressDownY = 0f
    private var isLongPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())

    // ========== 广播接收器 ==========
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    // ========== 锁屏时完全移除工作台 ==========
                    hideWorkbenchOnScreenOff()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOff = false
                    // ========== 解锁后立即恢复工作台 ==========
                    restoreWorkbenchOnScreenOn()
                }
            }
        }
    }

    // ========== 初始化 ==========
    init {
        perfLog("WorkbenchOverlay init START")
        val initStart = System.currentTimeMillis()
        
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            context.registerReceiver(screenStateReceiver, filter)
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

        perfLog("WorkbenchOverlay init END: ${System.currentTimeMillis() - initStart}ms")
        
        preCreateAppListPanel()
    }

    // ========== 锁屏时完全移除工作台 ==========
    private fun hideWorkbenchOnScreenOff() {
        try {
            val view = overlayViewRef?.get()
            if (view != null && isShowing) {
                windowManager.removeView(view)
                perfLog("hideWorkbenchOnScreenOff: workbench removed")
            }
            // 标记为未显示，但保留视图引用以便快速恢复
            isShowing = false
        } catch (e: Exception) {
            Log.e(TAG, "hideWorkbenchOnScreenOff failed", e)
        }
    }

    // ========== 解锁后立即恢复工作台 ==========
    private fun restoreWorkbenchOnScreenOn() {
        try {
            val view = overlayViewRef?.get()
            val params = workbenchParams
            
            if (view != null && params != null) {
                // 视图已存在，直接重新添加
                windowManager.addView(view, params)
                isShowing = true
                perfLog("restoreWorkbenchOnScreenOn: workbench restored")
            } else {
                // 视图不存在，重新创建
                perfLog("restoreWorkbenchOnScreenOn: view is null, recreating")
                show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreWorkbenchOnScreenOn failed", e)
            // 降级方案：重新创建
            show()
        }
    }

    // ========== 预创建 AppListPanel ==========
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
                    onRefreshApps = {}
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

    // ========== 黑名单管理 ==========
    private fun loadBlacklist() {
        val saved = manager.getBlacklist()
        blacklist.clear()
        blacklist.addAll(saved)
    }

    private fun saveBlacklist() {
        manager.setBlacklist(blacklist)
    }

    // ========== 应用列表 ==========
    private fun initSlots() {
        appSlots.clear()
    }

    private fun initAppContainer() {
        appContainer.removeAllViews()
        appContainer.orientation = LinearLayout.HORIZONTAL
        appContainer.gravity = Gravity.CENTER
        appContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
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

            appSlots.removeAll { it.first == packageName }
            appSlots.add(0, packageName to appName)

            while (appSlots.size > MAX_APPS) {
                appSlots.removeAt(appSlots.size - 1)
            }

            refreshAppSlots()
        } catch (e: Exception) {
            Log.e(TAG, "updateForegroundApp failed", e)
        }
    }

    // ========== 显示/隐藏 ==========
    fun show() {
        if (isShowing) return
        if (overlayViewRef?.get() != null) {
            // 视图已存在但未显示，重新添加
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

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val navBarHeight = getNavBarHeight()
        barHeight = navBarHeight

        val container = createWorkbenchView(screenWidth)
        overlayViewRef = WeakReference(container)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val workbenchHeight = settings.height.dpToPx()
        workbenchParams = WindowManager.LayoutParams(
            screenWidth,
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
    
    // ========== 刷新应用槽位 ==========
    refreshAppSlots()
    
    // ========== 如果搜索面板打开，刷新面板数据 ==========
    if (isAppsPanelShowing) {
        val apps = manager.getCachedApps()
        cachedApps.clear()
        cachedApps.addAll(apps)
        cachedAppsHash = apps.hashCode()
        preCreatedAppListPanel?.refresh(apps)
        Log.d(TAG, "App list panel refreshed with ${apps.size} apps")
    }
    
    // ========== 预加载图标 ==========
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

// WorkbenchOverlay.kt - 添加方法

// ========== 从槽位中移除指定应用 ==========
fun removeAppFromSlots(packageName: String) {
    perfLog("removeAppFromSlots: $packageName")
    // 从 appSlots 中移除
    appSlots.removeAll { it.first == packageName }
    // 刷新显示
    refreshAppSlots()
}

    // ========== 应用列表面板 ==========
    fun showAppsList() {
        perfLog("showAppsList START")
        
        if (isAppsPanelShowing) {
            hideAppsList()
            return
        }

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

    // ========== 获取窗口类型 ==========
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

    // ========== 显示预创建的应用列表 ==========
    private fun showPreCreatedAppList() {
        val startTime = System.currentTimeMillis()
        perfLog("showPreCreatedAppList START")
        
        try {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels
            
            val widthRatio = settings.appListWidthRatio
            val heightRatio = settings.appListHeightRatio
            val verticalOffset = settings.appListVerticalOffset.dpToPx()
            val cornerRadius = settings.appListCornerRadius.dpToPx().toFloat()
            val dimAlpha = settings.appListDimAlpha
            
            val appsWidth = (screenWidth * widthRatio).toInt()
            val workbenchHeight = settings.height.dpToPx()
            val availableHeight = screenHeight - workbenchHeight
            val appsHeight = (availableHeight * heightRatio).toInt()
            val topMargin = ((availableHeight - appsHeight) / 2) + verticalOffset

            // ========== 根容器：全屏覆盖 ==========
            val rootContainer = FrameLayout(context).apply {
                val alphaInt = (dimAlpha * 255).toInt()
                setBackgroundColor(Color.argb(alphaInt, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        hideAppsList()
                        true
                    } else {
                        false
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

            // ========== 内容容器 ==========
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
                
                val refreshStart = System.currentTimeMillis()
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
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val field = WindowManager.LayoutParams::class.java.getField("layoutInDisplayCutoutMode")
                        val mode = WindowManager.LayoutParams::class.java.getField("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES")
                        field.set(this, mode.getInt(null))
                    } catch (e: Exception) {
                        // 忽略
                    }
                }
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

    // ========== 降级方案：实时创建应用列表 ==========
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
            
            val widthRatio = settings.appListWidthRatio
            val heightRatio = settings.appListHeightRatio
            val verticalOffset = settings.appListVerticalOffset.dpToPx()
            val cornerRadius = settings.appListCornerRadius.dpToPx().toFloat()
            val dimAlpha = settings.appListDimAlpha
            
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
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        hideAppsList()
                        true
                    } else {
                        false
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
                onRefreshApps = {}
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
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val field = WindowManager.LayoutParams::class.java.getField("layoutInDisplayCutoutMode")
                        val mode = WindowManager.LayoutParams::class.java.getField("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES")
                        field.set(this, mode.getInt(null))
                    } catch (e: Exception) {
                        // 忽略
                    }
                }
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

    // ========== 预准备视图 ==========
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
    private class GestureContainer(
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

        private val handler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        private val SWIPE_THRESHOLD = 25f
        private val TIMEOUT_MS = 500L
        private val ANGLE_MIN = 10.0
        private val ANGLE_MAX = 90.0
        private val DISTANCE_MIN = 15f

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

                    timeoutRunnable = Runnable {
                        if (!gestureCompleted) {
                            isTimeout = true
                            gestureCompleted = true
                            isIntercepting = false
                            parent?.requestDisallowInterceptTouchEvent(false)
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

                    if (isIntercepting) return true

                    if (distance > SWIPE_THRESHOLD) {
                        val inMainArea = isInMainArea(touchStartX)
                        val inLeftBottom = isInLeftBottomRegion(touchStartRawX, touchStartRawY)

                        if (inMainArea || inLeftBottom) {
                            isIntercepting = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        } else {
                            gestureCompleted = true
                            gesturePhase = 0
                            timeoutRunnable?.let { handler.removeCallbacks(it) }
                            return false
                        }
                    }
                    return false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                        }
                        if (gesturePhase == 1 && event.y - lastMoveY > SWIPE_THRESHOLD) {
                            gesturePhase = 2
                        }
                        if (gesturePhase == 0 && dx > SWIPE_THRESHOLD) {
                            gesturePhase = 4
                        }
                        if (gesturePhase == 0 && dx < -SWIPE_THRESHOLD) {
                            gesturePhase = 5
                        }
                    }

                    if (isInLeftBottomRegion(touchStartRawX, touchStartRawY)) {
                        val angle = kotlin.math.abs(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())))
                        if (distance > DISTANCE_MIN && angle in ANGLE_MIN..ANGLE_MAX) {
                            if (gesturePhase != 3) {
                                gesturePhase = 3
                            }
                        }
                    }

                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }

                    if (isTimeout) {
                        resetState()
                        return true
                    }

                    if (!gestureCompleted) {
                        when (gesturePhase) {
                            1 -> {
                                gestureCompleted = true
                                onOpenSearch()
                                resetState()
                                return true
                            }
                            2 -> {
                                gestureCompleted = true
                                onHomeGesture()
                                resetState()
                                return true
                            }
                            3 -> {
                                gestureCompleted = true
                                onRecentGesture()
                                resetState()
                                return true
                            }
                            4 -> {
                                gestureCompleted = true
                                onPrevPage()
                                resetState()
                                return true
                            }
                            5 -> {
                                gestureCompleted = true
                                onNextPage()
                                resetState()
                                return true
                            }
                            else -> {}
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
                appSlots.filter {
                    it.first.isNotEmpty() && !blacklist.contains(it.first) && !manager.isAppFrozen(it.first)
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

    // ========== 极简页面指示器 ==========
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
            
            for (i in 0 until totalPages) {
                val dot = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        setMargins(spacing / 2, 0, spacing / 2, 0)
                    }
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
        refreshAppSlots()
        updatePageIndicator()
    }

    // ========== 应用槽位 ==========
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
                        resetWorkbench()
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

    // ========== 应用项 ==========
    private fun createAppItem(packageName: String, appName: String, isForeground: Boolean, slotWidth: Int): View {
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
                isFrozen -> {
                    setBackgroundColor(Color.parseColor("#33AADDFF"))
                }
                currentMode == Mode.ADD && !blacklist.contains(packageName) -> {
                    setBackgroundColor(Color.parseColor("#22FFFFFF"))
                }
                currentMode == Mode.REMOVE && blacklist.contains(packageName) -> {
                    setBackgroundColor(Color.parseColor("#33FF4444"))
                }
                isForeground -> {
                    setBackgroundColor(Color.parseColor("#33FF8800"))
                }
                else -> {
                    setBackgroundColor(Color.TRANSPARENT)
                }
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
                            appSlots.removeAll { it.first == packageName }
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
                                isFrozen -> view.setBackgroundColor(Color.parseColor("#33AADDFF"))
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

            val iconView = ImageView(context).apply {
                val bitmap = iconLoader?.getIconForPackage(context, packageName)
                if (bitmap != null) {
                    setImageBitmap(bitmap)
                } else {
                    setImageDrawable(fallbackIcon(packageName))
                }
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                if (isFrozen) {
                    setAlpha(0.5f)
                }
            }
            item.addView(iconView)

            val nameView = TextView(context).apply {
                text = if (isFrozen) "❄️ $appName" else appName
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

    // ========== 冻结/解冻 ==========
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

    // ========== 其他辅助方法 ==========
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToApp failed", e)
            Toast.makeText(context, "启动失败", Toast.LENGTH_SHORT).show()
        }

        val existingIndex = appSlots.indexOfFirst { it.first == packageName }
        if (existingIndex >= 0) {
            appSlots.removeAt(existingIndex)
        }
        if (!blacklist.contains(packageName)) {
            appSlots.add(0, packageName to appName)
            while (appSlots.size > MAX_APPS) {
                appSlots.removeAt(appSlots.size - 1)
            }
        }
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

    // ========== 清理 ==========
    fun cleanup() {
        perfLog("cleanup")
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "unregisterReceiver failed", e)
        }
        preCreateJob?.cancel()
        preCreateJob = null
        hide()
        if (isAppsPanelShowing) {
            preCreatedAppListPanel?.clearSearch()
            hideAppsList()
        }
        handler.removeCallbacksAndMessages(null)
    }

    // ========== 扩展函数 ==========
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}