package ru.queuejw.lumetro.components.freeform

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.core.sidebar.AppListPanel
import ru.queuejw.lumetro.model.App

class LandscapeWorkbenchOverlay(
    private val service: AccessibilityService,
    private val manager: WorkbenchManager
) {
    
    companion object {
        private const val TAG = "LandscapeWorkbench"
    }
    
    private val context: Context = service.applicationContext
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val coroutineScope = manager.getCoroutineScope()
    private val iconLoader: IconLoader? = manager.getIconLoader()
    private val iconCache: MutableMap<String, Bitmap> = manager.getIconCache()
    private val handler = Handler(Looper.getMainLooper())
    
    private var isShowing = false
    private var isAppListShowing = false
    private var isLockOverlayShowing = false
    
    private var buttonViewRef: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    
    private var appListViewRef: View? = null
    private var appListParams: WindowManager.LayoutParams? = null
    private var backgroundViewRef: View? = null
    private var backgroundParamsRef: WindowManager.LayoutParams? = null
    private var lockOverlayView: View? = null
    private var lockOverlayParams: WindowManager.LayoutParams? = null
    
    private var appListPanel: AppListPanel? = null
    private var appListContainerRef: LinearLayout? = null
    
    private var lockTouchStartX = 0f
    
    private val configChangeReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            // 横屏的旋转处理交给竖屏 WorkbenchOverlay 统一管理
            // 这里不做处理，避免冲突
        }
    }
}
    
    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        try {
            context.registerReceiver(configChangeReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "register configChangeReceiver failed", e)
        }
    }
    
    private fun getWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
    }
    
    private fun isLandscape(): Boolean {
        val dm = context.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }
    
    fun show() {
        if (isShowing) return
        
        val dm = context.resources.displayMetrics
        val buttonSize = (40 * dm.density).toInt()
        
        val button = createTriggerButton(buttonSize)
        buttonViewRef = button
        
        buttonParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (16 * dm.density).toInt()
            y = (16 * dm.density).toInt()
        }
        
        try {
            windowManager.addView(button, buttonParams)
            isShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "show failed", e)
        }
    }
    
    private fun createTriggerButton(size: Int): View {
        val button = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            isClickable = true
            
            setOnClickListener {
                showAppList()
            }
        }
        
        val iconView = TextView(context).apply {
            text = "⌕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        button.addView(iconView)
        
        return button
    }
    
    fun hide() {
        if (!isShowing) return
        
        try {
            buttonViewRef?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        
        buttonViewRef = null
        buttonParams = null
        isShowing = false
        
        hideAppList()
        hideLockOverlay()
    }
    
    fun toggle() {
        if (isShowing) hide() else show()
    }
    
    fun isShowing(): Boolean = isShowing
    
    fun showAppList() {
        if (isAppListShowing) {
            hideAppList()
            return
        }
        
        val dm = context.resources.displayMetrics
        
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
        }
        
        val listScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.4f
            )
            setBackgroundColor(Color.parseColor("#FF222222"))
        }
        
        val appListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        appListContainerRef = appListContainer
        listScrollView.addView(appListContainer)
        rootLayout.addView(listScrollView)
        
        if (appListPanel == null) {
            appListPanel = AppListPanel(
                context = context,
                iconLoader = iconLoader ?: IconLoader(false, null),
                coroutineScope = coroutineScope,
                onHidePanel = { hideAppList() },
                onRefreshTiles = {},
                onShowSettings = {
                    try {
                        val intent = Intent(context, WorkbenchSettingsActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        hideAppList()
                    } catch (e: Exception) {}
                },
                onShowFreezeDialog = {},
                onPinApp = {},
                onRefreshApps = {},
                onAppsChanged = { apps ->
                    handler.post {
                        appListContainerRef?.let { container ->
                            updateAppList(container, apps)
                        }
                    }
                },
                onPageChangeRequested = null,
                onOpenNotificationCenter = { hideAppList() },
                onOpenControlCenter = { hideAppList() },
                onLockRequested = {
                    hide()
                    showLockOverlay()
                },
                onUnlockRequested = {
                    hideLockOverlay()
                    show()
                }
            )
        }
        
        val keyboardView = appListPanel?.createView()
        keyboardView?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.6f
        )
        rootLayout.addView(keyboardView)
        
        loadAppListData(appListContainer)
        
        val backgroundView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    hideAppList()
                    true
                } else {
                    false
                }
            }
        }
        
        val windowType = getWindowType()
        
        backgroundParamsRef = WindowManager.LayoutParams(
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
        
        val panelWidth = (dm.widthPixels * 0.4f).toInt()
        
        appListParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }
        
        try {
            windowManager.addView(backgroundView, backgroundParamsRef)
            backgroundViewRef = backgroundView
            
            windowManager.addView(rootLayout, appListParams)
            appListViewRef = rootLayout
            isAppListShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "showAppList failed", e)
        }
    }
    
    private fun loadAppListData(container: LinearLayout) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val apps = manager.getCachedAppsSync()
                withContext(Dispatchers.Main) {
                    if (apps.isNotEmpty()) {
                        appListPanel?.loadApps(apps)
                        updateAppList(container, apps)
                    } else {
                        showEmptyMessage(container, "无法加载应用列表")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "load apps failed", e)
                withContext(Dispatchers.Main) {
                    showEmptyMessage(container, "加载失败: ${e.message}")
                }
            }
        }
    }
    
    private fun updateAppList(container: LinearLayout, apps: List<App>) {
        container.removeAllViews()
        
        if (apps.isEmpty()) {
            showEmptyMessage(container, "无匹配应用")
            return
        }
        
        for (app in apps) {
            val itemView = TextView(context).apply {
                text = app.mName
                textSize = 28f
                setTextColor(Color.WHITE)
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    launchApp(app)
                }
            }
            container.addView(itemView)
        }
        
        container.post {
            val scrollView = container.parent as? ScrollView
            scrollView?.scrollTo(0, 0)
        }
    }
    
    private fun showEmptyMessage(container: LinearLayout, message: String) {
        val emptyText = TextView(context).apply {
            text = message
            textSize = 28f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        container.removeAllViews()
        container.addView(emptyText)
    }
    
    private fun launchApp(app: App) {
        val pkg = app.mPackage ?: return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                hideAppList()
            }
        } catch (e: Exception) {}
    }
    
    // ========== 锁定图层（横向滑动解锁） ==========
    fun showLockOverlay() {
    if (isLockOverlayShowing) return

    val density = context.resources.displayMetrics.density

    val lockIcon = TextView(context).apply {
        text = "🚫"
        textSize = 20f  // 缩小
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END  // 右下角
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
    
    lockOverlayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        getWindowType(),
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

private var lockTouchStartY = 0f

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
    
    // public 方法供竖屏调用
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
    
    // ========== 锁屏解除 ==========
    fun onScreenOff() {
        hideLockOverlay()
    }
    
    // ========== 屏幕开启检测方向 ==========
    fun onScreenOn() {
        if (isLandscape()) {
            show()
        } else {
            hide()
        }
    }
    
    fun hideAppList() {
        if (!isAppListShowing) return
        
        try {
            backgroundViewRef?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        backgroundViewRef = null
        backgroundParamsRef = null
        
        try {
            appListViewRef?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        appListViewRef = null
        appListParams = null
        appListContainerRef = null
        isAppListShowing = false
    }
    
    fun cleanup() {
        try {
            context.unregisterReceiver(configChangeReceiver)
        } catch (e: Exception) {}
        
        hide()
        hideAppList()
        hideLockOverlay()
        appListPanel?.clearResources()
        appListPanel = null
    }
}