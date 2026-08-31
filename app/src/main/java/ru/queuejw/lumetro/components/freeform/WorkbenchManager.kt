package ru.queuejw.lumetro.components.freeform

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import ru.queuejw.lumetro.components.core.AppManager
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.model.App
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FreezeListAdapter(
    private val context: Context,
    private val appList: List<Pair<String, String>>,
    private var freezeList: Set<String>,
    private val onItemClick: (String, String, Boolean) -> Unit
) : RecyclerView.Adapter<FreezeListAdapter.ViewHolder>() {

    class ViewHolder(val container: LinearLayout, val textView: TextView) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
            gravity = Gravity.CENTER_VERTICAL
        }
        val textView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        container.addView(textView)
        return ViewHolder(container, textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (pkg, name) = appList[position]
        val isInList = freezeList.contains(pkg)
        holder.textView.text = "${if (isInList) "✓ " else "  "}$name"
        holder.textView.setTextColor(if (isInList) Color.parseColor("#FF4CAF50") else Color.WHITE)
        
        holder.container.setOnClickListener {
            onItemClick(pkg, name, isInList)
        }
    }

    override fun getItemCount(): Int = appList.size

    fun updateFreezeList(newList: Set<String>) {
        freezeList = newList
        // 只刷新可见项
        notifyDataSetChanged()
    }

    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()
}

class WorkbenchManager(
    private val context: Context,
    private val accessibilityService: AccessibilityService
) {

    companion object {
        private const val TAG = "WorkbenchManager"

        @Volatile
        private var instance: WorkbenchManager? = null

        fun getInstance(): WorkbenchManager? = instance

        fun init(context: Context, accessibilityService: AccessibilityService): WorkbenchManager {
            Log.d(TAG, "=== WorkbenchManager.init() 被调用 ===")
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = WorkbenchManager(context, accessibilityService)
                        Log.d(TAG, "WorkbenchManager 实例创建成功")
                    }
                }
            } else {
                Log.d(TAG, "WorkbenchManager 实例已存在")
            }
            return instance!!
        }

        fun destroyInstance() {
            synchronized(this) {
                instance?.cleanup()
                instance = null
            }
        }

        fun isWorkbenchShowing(): Boolean {
            return instance?.isShowing() ?: false
        }

        fun toggleWorkbench() {
            instance?.toggle()
        }

        fun showWorkbench() {
            instance?.show()
        }

        fun hideWorkbench() {
            instance?.hide()
        }

        fun updateForegroundApp(packageName: String) {
            instance?.updateForegroundApp(packageName)
        }
    }

    // ========== 性能日志 ==========
    private val perfLogEnabled = false
    
    private fun perfLog(message: String) {
        if (!perfLogEnabled) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] [WorkbenchManager] $message"
        Log.d("WorkbenchManager_Perf", logMessage)
        
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "workbench_performance_log.txt")
            file.appendText("$logMessage\n")
        } catch (e: Exception) {
            // 忽略
        }
    }

    // ========== 核心组件 ==========
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    private val prefs = context.getSharedPreferences("workbench", Context.MODE_PRIVATE)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val iconCache = mutableMapOf<String, Bitmap>()
    private var iconLoader: IconLoader? = null

    // ========== 工作台实例 ==========
    private var workbenchOverlayRef: WeakReference<WorkbenchOverlay>? = null

    // ========== 状态 ==========
    private var isInitialized = false
    private var cachedApps = mutableListOf<App>()
    private var settings = WorkbenchSettings(context)

    // ========== 当前 Activity 引用 ==========
    private var currentActivityRef: WeakReference<Activity>? = null

    // ========== 协程任务 ==========
    private var refreshJob: Job? = null
    private var preCreateJob: Job? = null

    // ========== 应用列表缓存 ==========
    private var cachedAppList: List<App>? = null
    private var cacheTimestamp: Long = 0
    private val CACHE_VALID_DURATION = 60000L
    private var cachedAppsHash: Int = 0
    private val cacheLock = Any()
    private var isLoadingApps = false
    
    // ========== 锁屏状态 ==========
    private var isScreenOff = false
    private var isWorkbenchPrepared = false
    
    // ========== 应用安装/卸载广播接收器 ==========
    private val appInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val data = intent.data
            val packageName = data?.schemeSpecificPart
            
            if (packageName == null || packageName == context.packageName) return
            
            when (action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    perfLog("App installed/replaced: $packageName")
                    // 清除缓存，强制刷新
                    invalidateAppCache()
                    coroutineScope.launch {
                        delay(300)
                        refreshApps()
                        workbenchOverlayRef?.get()?.forceRefreshIcons()
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    perfLog("App removed: $packageName")
                    // ========== 从槽位和黑名单中移除 ==========
                    removeAppFromWorkbench(packageName)
                    // 清除缓存，强制刷新
                    invalidateAppCache()
                    coroutineScope.launch {
                        delay(300)
                        refreshApps()
                        workbenchOverlayRef?.get()?.forceRefreshIcons()
                    }
                }
            }
        }
    }

    // ========== 从工作台移除已卸载的应用 ==========
    private fun removeAppFromWorkbench(packageName: String) {
        perfLog("removeAppFromWorkbench: $packageName")
        
        // 1. 从 appSlots 中移除
        val overlay = workbenchOverlayRef?.get()
        overlay?.removeAppFromSlots(packageName)
        
        // 2. 从黑名单中移除
        if (isInBlacklist(packageName)) {
            removeFromBlacklist(packageName)
            perfLog("Removed $packageName from blacklist")
        }
        
        // 3. 从冻结列表中移除
        if (isInFreezeList(packageName)) {
            removeFromFreezeList(packageName)
            perfLog("Removed $packageName from freeze list")
        }
        
        // 4. 从隐藏列表中移除
        if (isHidden(packageName)) {
            toggleHidden(packageName)
            perfLog("Removed $packageName from hidden list")
        }
        
        // 5. 从图标缓存中移除
        iconCache.remove(packageName)
    }

    // ========== 屏幕状态广播接收器 ==========
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    perfLog("Screen off, preparing for resume")
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOff = false
                    perfLog("User present, pre-initializing workbench")
                    preInitializeAfterUnlock()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕点亮
                }
            }
        }
    }

    // ========== 初始化 ==========
    init {
        perfLog("WorkbenchManager init START")
        val initStart = System.currentTimeMillis()
        
        try {
            ShizukuHelper.getInstance().init(context.applicationContext)
            Log.d(TAG, "Shizuku initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku init failed", e)
        }

        val iconPack = prefs.getString("icon_pack_package", null)
        iconLoader = IconLoader(iconPack != null, iconPack)

        initializeWorkbench()
        
        // 同步加载应用列表
        perfLog("init: loading apps synchronously")
        val apps = loadAppsFromPackageManager()
        synchronized(cacheLock) {
            cachedAppList = apps
            cacheTimestamp = System.currentTimeMillis()
            cachedAppsHash = apps.hashCode()
            cachedApps.clear()
            cachedApps.addAll(apps)
        }
        perfLog("init: loaded ${apps.size} apps")
        
        // ========== 注册应用安装/卸载广播 ==========
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            context.registerReceiver(appInstallReceiver, filter)
            perfLog("App install receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register app install receiver", e)
        }
        
        // ========== 注册屏幕状态广播 ==========
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(screenStateReceiver, filter)
            perfLog("Screen state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }
        
        perfLog("WorkbenchManager init END: ${System.currentTimeMillis() - initStart}ms")
    }

    private fun initializeWorkbench() {
        try {
            val overlay = WorkbenchOverlay(
                service = accessibilityService,
                manager = this
            )
            workbenchOverlayRef = WeakReference(overlay)
            Log.d(TAG, "Workbench initialized successfully")
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize workbench", e)
            isInitialized = false
        }
    }

    // ========== 解锁后预初始化 ==========
    private fun preInitializeAfterUnlock() {
        if (!isInitialized) {
            initializeWorkbench()
        }
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                perfLog("preInitializeAfterUnlock: pre-creating app list")
                val apps = getCachedApps()
                cachedApps.clear()
                cachedApps.addAll(apps)
                
                withContext(Dispatchers.Main) {
                    if (workbenchOverlayRef?.get() == null) {
                        val overlay = WorkbenchOverlay(
                            service = accessibilityService,
                            manager = this@WorkbenchManager
                        )
                        workbenchOverlayRef = WeakReference(overlay)
                        perfLog("preInitializeAfterUnlock: overlay re-created")
                    }
                }
            } catch (e: Exception) {
                perfLog("preInitializeAfterUnlock failed: ${e.message}")
                Log.e(TAG, "preInitializeAfterUnlock error", e)
            }
        }
    }

    // ========== 应用列表缓存方法 ==========
    
    suspend fun getCachedApps(forceRefresh: Boolean = false): List<App> {
        val now = System.currentTimeMillis()
        
        synchronized(cacheLock) {
            if (!forceRefresh && cachedAppList != null && (now - cacheTimestamp) < CACHE_VALID_DURATION) {
                perfLog("getCachedApps: using cache, ${cachedAppList?.size} apps")
                return cachedAppList!!
            }
        }
        
        var retryCount = 0
        while (isLoadingApps && retryCount < 10) {
            delay(50)
            retryCount++
            synchronized(cacheLock) {
                if (cachedAppList != null && (now - cacheTimestamp) < CACHE_VALID_DURATION) {
                    return cachedAppList!!
                }
            }
        }
        
        perfLog("getCachedApps: loading fresh")
        val loadStart = System.currentTimeMillis()
        
        synchronized(cacheLock) {
            isLoadingApps = true
        }
        
        try {
            val apps = withContext(Dispatchers.IO) {
                loadAppsFromPackageManager()
            }
            
            synchronized(cacheLock) {
                cachedAppList = apps
                cacheTimestamp = System.currentTimeMillis()
                cachedAppsHash = apps.hashCode()
                cachedApps.clear()
                cachedApps.addAll(apps)
                isLoadingApps = false
            }
            
            perfLog("getCachedApps: loaded ${apps.size} apps in ${System.currentTimeMillis() - loadStart}ms")
            return apps
        } catch (e: Exception) {
            perfLog("getCachedApps FAILED: ${e.message}")
            synchronized(cacheLock) {
                isLoadingApps = false
            }
            synchronized(cacheLock) {
                if (cachedAppList != null) {
                    return cachedAppList!!
                }
            }
            return emptyList()
        }
    }
    
    private fun loadAppsFromPackageManager(): List<App> {
        val pm = context.packageManager
        val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = ArrayList<App>()
        for (info in all) {
            if (info.packageName == context.packageName) continue
            val activities = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(info.packageName),
                PackageManager.GET_DISABLED_COMPONENTS
            )
            if (activities.isEmpty()) continue
            list.add(App(info.loadLabel(pm).toString(), info.packageName, 0))
        }
        return list
    }
    
    fun getCachedAppsSync(): List<App> {
        val now = System.currentTimeMillis()
        
        synchronized(cacheLock) {
            if (cachedAppList != null && (now - cacheTimestamp) < CACHE_VALID_DURATION) {
                return cachedAppList!!
            }
        }
        
        perfLog("getCachedAppsSync: loading synchronously")
        val apps = loadAppsFromPackageManager()
        synchronized(cacheLock) {
            cachedAppList = apps
            cacheTimestamp = now
            cachedAppsHash = apps.hashCode()
            cachedApps.clear()
            cachedApps.addAll(apps)
        }
        return apps
    }
    
    fun isAppCacheValid(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            return cachedAppList != null && (now - cacheTimestamp) < CACHE_VALID_DURATION
        }
    }
    
    fun invalidateAppCache() {
        synchronized(cacheLock) {
            cachedAppList = null
            cacheTimestamp = 0
            cachedAppsHash = 0
        }
        perfLog("invalidateAppCache: cache invalidated")
    }
    
    fun getCachedAppsHash(): Int {
        synchronized(cacheLock) {
            return cachedAppsHash
        }
    }

    // ========== 公共接口 ==========

    fun getIconLoader(): IconLoader? = iconLoader

    fun getIconCache(): MutableMap<String, Bitmap> = iconCache

    fun getSettings(): WorkbenchSettings = settings

    fun getCoroutineScope(): CoroutineScope = coroutineScope

    // ========== 工作台控制 ==========

    fun show() {
        if (!isInitialized) {
            initializeWorkbench()
        }
        workbenchOverlayRef?.get()?.show()
        Log.d(TAG, "Workbench shown")
    }

    fun showFast() {
        perfLog("showFast START")
        
        if (isShowing()) {
            perfLog("showFast: already showing")
            return
        }
        
        var overlay = workbenchOverlayRef?.get()
        if (overlay == null) {
            perfLog("showFast: overlay is null, creating new")
            overlay = WorkbenchOverlay(
                service = accessibilityService,
                manager = this
            )
            workbenchOverlayRef = WeakReference(overlay)
        }
        
        overlay.show()
        Log.d(TAG, "Workbench shown fast")
    }

    fun hide() {
        workbenchOverlayRef?.get()?.hide()
        Log.d(TAG, "Workbench hidden")
    }

    fun toggle() {
        val overlay = workbenchOverlayRef?.get()
        if (overlay?.isShowing() == true) {
            hide()
        } else {
            show()
        }
    }

    fun isShowing(): Boolean {
        return workbenchOverlayRef?.get()?.isShowing() ?: false
    }

    fun updateForegroundApp(packageName: String) {
        if (packageName == context.packageName) return
        workbenchOverlayRef?.get()?.updateForegroundApp(packageName)
    }

    fun showAppsList() {
        workbenchOverlayRef?.get()?.showAppsList()
    }

    fun hideAppsList() {
        workbenchOverlayRef?.get()?.hideAppsList()
    }

    fun toggleAppsList() {
        workbenchOverlayRef?.get()?.toggleAppsList()
    }

    // ========== 冻结列表管理 ==========

    fun showFreezeManagementDialog(activity: Activity) {
    try {
        currentActivityRef = WeakReference(activity)
        val freezeList = getFreezeList()
        val pm = context.packageManager
        val appList = mutableListOf<Pair<String, String>>()

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (info in apps) {
            if (info.packageName == context.packageName) continue
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val name = pm.getApplicationLabel(info).toString()
            appList.add(info.packageName to name)
        }
        appList.sortBy { it.second }

        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (400 * activity.resources.displayMetrics.density).toInt()
            )
        }
        
        // ========== 先声明 adapter 为可变变量 ==========
        var adapter: FreezeListAdapter? = null
        
        adapter = FreezeListAdapter(
            context = context,
            appList = appList,
            freezeList = freezeList,
            onItemClick = { pkg, name, isInList ->
                if (isInList) {
                    removeFromFreezeList(pkg)
                    Toast.makeText(context, "已从冻结列表移除: $name", Toast.LENGTH_SHORT).show()
                } else {
                    addToFreezeList(pkg)
                    Toast.makeText(context, "已添加到冻结列表: $name", Toast.LENGTH_SHORT).show()
                }
                // 使用 adapter 引用
                adapter?.updateFreezeList(getFreezeList())
            }
        )
        
        recyclerView.adapter = adapter

        android.app.AlertDialog.Builder(activity)
            .setTitle("冻结列表管理\n(点击 切换)")
            .setView(recyclerView)
            .setNegativeButton("关闭") { _, _ -> currentActivityRef = null }
            .setOnDismissListener { currentActivityRef = null }
            .show()

    } catch (e: Exception) {
        Log.e(TAG, "showFreezeManagementDialog error", e)
        Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

    fun showHiddenManagementDialog(activity: Activity) {
        try {
            currentActivityRef = WeakReference(activity)
            val hiddenList = getHiddenList()
            val pm = context.packageManager
            val appList = mutableListOf<Pair<String, String>>()
            
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info in apps) {
                if (info.packageName == context.packageName) continue
                val name = pm.getApplicationLabel(info).toString()
                appList.add(info.packageName to name)
            }
            appList.sortBy { it.second }
            
            val items = appList.map { (pkg, name) ->
                val isHidden = hiddenList.contains(pkg)
                "${if (isHidden) "✕ " else "  "}$name"
            }.toTypedArray()
            
            android.app.AlertDialog.Builder(activity)
                .setTitle("隐藏列表管理\n(点击切换)")
                .setItems(items) { _, which ->
                    try {
                        val (pkg, name) = appList[which]
                        toggleHidden(pkg)
                        Toast.makeText(context, if (hiddenList.contains(pkg)) "已隐藏: $name" else "已取消隐藏: $name", Toast.LENGTH_SHORT).show()
                        showHiddenManagementDialog(activity)
                    } catch (e: Exception) {
                        Log.e(TAG, "Hidden management item click error", e)
                        Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("关闭") { _, _ -> currentActivityRef = null }
                .setOnDismissListener { currentActivityRef = null }
                .show()
                
        } catch (e: Exception) {
            Log.e(TAG, "showHiddenManagementDialog error", e)
            Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 应用列表管理 ==========

    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val apps = getCachedApps(forceRefresh = true)
                cachedApps.clear()
                cachedApps.addAll(apps)
                Log.d(TAG, "Apps refreshed: ${cachedApps.size} apps")
                
                withContext(Dispatchers.Main) {
                    workbenchOverlayRef?.get()?.forceRefreshIcons()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh apps", e)
            }
        }
    }

    fun getCachedApps(): List<App> = cachedApps

    // ========== 冻结功能 ==========

    fun isAppFrozen(pkg: String): Boolean {
        return try {
            FreezeManager.isFrozen(context, pkg)
        } catch (e: Exception) {
            Log.e(TAG, "isAppFrozen error", e)
            false
        }
    }

    fun freezeApp(pkg: String, callback: ((Boolean) -> Unit)? = null) {
        val sh = ShizukuHelper.getInstance()
        if (!sh.isReady()) {
            Toast.makeText(context, "Shizuku 未就绪，无法冻结", Toast.LENGTH_SHORT).show()
            callback?.invoke(false)
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val success = sh.freezeApp(pkg)
                withContext(Dispatchers.Main) {
                    if (success) {
                        FreezeManager.setFrozen(context, pkg, true)
                        Toast.makeText(context, "已冻结", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "冻结失败", Toast.LENGTH_SHORT).show()
                    }
                    callback?.invoke(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "freezeApp error", e)
                    Toast.makeText(context, "冻结失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    callback?.invoke(false)
                }
            }
        }
    }

    fun unfreezeApp(pkg: String, callback: ((Boolean) -> Unit)? = null) {
        val sh = ShizukuHelper.getInstance()
        if (!sh.isReady()) {
            Toast.makeText(context, "Shizuku 未就绪，无法解冻", Toast.LENGTH_SHORT).show()
            callback?.invoke(false)
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val success = sh.unfreezeApp(pkg)
                withContext(Dispatchers.Main) {
                    if (success) {
                        FreezeManager.setFrozen(context, pkg, false)
                        Toast.makeText(context, "已解冻", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "解冻失败", Toast.LENGTH_SHORT).show()
                    }
                    callback?.invoke(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "unfreezeApp error", e)
                    Toast.makeText(context, "解冻失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    callback?.invoke(false)
                }
            }
        }
    }

    fun toggleFreezeApp(pkg: String, callback: ((Boolean) -> Unit)? = null) {
        if (isAppFrozen(pkg)) {
            unfreezeApp(pkg, callback)
        } else {
            freezeApp(pkg, callback)
        }
    }

    fun performOneKeyFreeze() {
        val sh = ShizukuHelper.getInstance()
        if (!sh.isReady()) {
            Toast.makeText(context, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        val list = FreezeManager.getList(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "冻结列表为空", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            var count = 0
            for (pkg in list) {
                if (FreezeManager.isFrozen(context, pkg)) continue
                if (sh.freezeApp(pkg)) {
                    FreezeManager.setFrozen(context, pkg, true)
                    count++
                    delay(50)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已冻结 $count 个应用", Toast.LENGTH_SHORT).show()
                refreshApps()
            }
        }
    }

    fun performOneKeyUnfreeze() {
        val sh = ShizukuHelper.getInstance()
        if (!sh.isReady()) {
            Toast.makeText(context, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        val list = FreezeManager.getList(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "冻结列表为空", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            var count = 0
            for (pkg in list) {
                if (!FreezeManager.isFrozen(context, pkg)) continue
                if (sh.unfreezeApp(pkg)) {
                    FreezeManager.setFrozen(context, pkg, false)
                    count++
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已解冻 $count 个应用", Toast.LENGTH_SHORT).show()
                refreshApps()
            }
        }
    }

    // ========== 黑名单管理 ==========

    fun getBlacklist(): Set<String> {
        return prefs.getStringSet("blacklist", emptySet()) ?: emptySet()
    }

    fun addToBlacklist(pkg: String) {
        val current = prefs.getStringSet("blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(pkg)
        prefs.edit().putStringSet("blacklist", current).apply()
        Log.d(TAG, "Added to blacklist: $pkg")
    }

    fun removeFromBlacklist(pkg: String) {
        val current = prefs.getStringSet("blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(pkg)
        prefs.edit().putStringSet("blacklist", current).apply()
        Log.d(TAG, "Removed from blacklist: $pkg")
    }

    fun clearBlacklist() {
        prefs.edit().putStringSet("blacklist", emptySet()).apply()
        Log.d(TAG, "Blacklist cleared")
    }

    fun setBlacklist(newList: Set<String>) {
        prefs.edit().putStringSet("blacklist", newList).apply()
        Log.d(TAG, "Blacklist set: ${newList.size} items")
    }

    fun isInBlacklist(pkg: String): Boolean {
        return getBlacklist().contains(pkg)
    }

    // ========== 图标包管理 ==========

    fun getIconPackPackage(): String? {
        return prefs.getString("icon_pack_package", null)
    }

    fun setIconPackPackage(pkg: String?) {
        prefs.edit().putString("icon_pack_package", pkg).apply()
        iconLoader = IconLoader(pkg != null, pkg)
        iconCache.clear()
        workbenchOverlayRef?.get()?.forceRefreshIcons()
        Log.d(TAG, "Icon pack set to: $pkg")
    }

    fun reloadIcons() {
        val pkg = getIconPackPackage()
        iconLoader = IconLoader(pkg != null, pkg)
        iconCache.clear()
        workbenchOverlayRef?.get()?.forceRefreshIcons()
        Log.d(TAG, "Icons reloaded")
    }

    // ========== 图标包选择器 ==========

    fun showIconPackPicker(activity: Activity) {
        try {
            Log.d(TAG, "=== showIconPackPicker() 被调用 ===")
            currentActivityRef = WeakReference(activity)

            val pm = context.packageManager
            val iconPacks = mutableListOf<Pair<String, String>>()

            val intents1 = pm.queryIntentActivities(Intent("org.adw.launcher.THEMES"), PackageManager.GET_META_DATA)
            val intents2 = pm.queryIntentActivities(Intent("com.gau.go.launcherex.theme"), PackageManager.GET_META_DATA)
            val intents3 = pm.queryIntentActivities(Intent("com.novalauncher.THEME"), PackageManager.GET_META_DATA)

            Log.d(TAG, "intents1: ${intents1.size}, intents2: ${intents2.size}, intents3: ${intents3.size}")

            val allIntents = (intents1 + intents2 + intents3).distinctBy { it.activityInfo.packageName }
            iconPacks.addAll(allIntents.mapNotNull { ri ->
                try {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == context.packageName) null
                    else {
                        val app = pm.getApplicationInfo(pkg, 0)
                        pkg to pm.getApplicationLabel(app).toString()
                    }
                } catch (e: Exception) {
                    null
                }
            }.distinctBy { it.first }.sortedBy { it.second })

            Log.d(TAG, "找到图标包数量: ${iconPacks.size}")

            if (iconPacks.isEmpty()) {
                Toast.makeText(context, "未找到图标包\n\n请安装第三方图标包后重试", Toast.LENGTH_LONG).show()
                return
            }

            val names = iconPacks.map { it.second }.toTypedArray()
            android.app.AlertDialog.Builder(activity)
                .setTitle("选择图标包")
                .setItems(names) { _, which ->
                    try {
                        val (pkg, name) = iconPacks[which]
                        setIconPackPackage(pkg)
                        Toast.makeText(context, "已应用: $name", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Apply icon pack error", e)
                        Toast.makeText(context, "应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消") { _, _ -> currentActivityRef = null }
                .setOnDismissListener { currentActivityRef = null }
                .show()

            Log.d(TAG, "=== 图标包加载完成 ===")

        } catch (e: Exception) {
            Log.e(TAG, "showIconPackPicker error", e)
            Toast.makeText(context, "加载图标包失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ========== 权限检查 ==========

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${context.packageName}/${SidebarAccessibilityService::class.java.name}"
        return try {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            if (enabled == 1) {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                enabledServices?.contains(serviceName) == true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "isAccessibilityServiceEnabled error", e)
            false
        }
    }

    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openAccessibilitySettings error", e)
            Toast.makeText(context, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    fun openOverlaySettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openOverlaySettings error", e)
            Toast.makeText(context, "无法打开悬浮窗设置", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 扩展函数 ==========

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
    
    // ========== Shizuku 权限 ==========

    fun isShizukuReady(): Boolean {
        return ShizukuHelper.getInstance().isReady()
    }

    fun checkShizukuStatus(): Boolean {
        ShizukuHelper.getInstance().checkStatus()
        return ShizukuHelper.getInstance().isReady()
    }

    // ========== 冻结列表管理 ==========

    fun getFreezeList(): List<String> {
        return FreezeManager.getList(context)
    }

    fun addToFreezeList(pkg: String) {
        FreezeManager.addToList(context, pkg)
    }

    fun removeFromFreezeList(pkg: String) {
        FreezeManager.removeFromList(context, pkg)
    }

    fun isInFreezeList(pkg: String): Boolean {
        return FreezeManager.getList(context).contains(pkg)
    }

    // ========== 隐藏名单管理 ==========

    fun getHiddenList(): List<String> {
        return FreezeManager.getHiddenList(context)
    }

    fun toggleHidden(pkg: String) {
        FreezeManager.toggleHidden(context, pkg)
    }

    fun isHidden(pkg: String): Boolean {
        return FreezeManager.getHiddenList(context).contains(pkg)
    }

    // ========== 权限检查 ==========

    fun checkAndRequestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    fun checkAndRequestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
        }
    }

    // ========== Shizuku 授权 ==========

    fun requestShizukuPermission() {
        try {
            ShizukuHelper.getInstance().checkStatus()
            if (!ShizukuHelper.getInstance().isReady()) {
                Toast.makeText(context, "请先启动 Shizuku 并授权", Toast.LENGTH_LONG).show()
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "requestShizukuPermission error", e)
                    Toast.makeText(context, "请手动打开 Shizuku 应用", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestShizukuPermission error", e)
            Toast.makeText(context, "Shizuku 未启动", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 清理 ==========

    fun cleanup() {
        perfLog("cleanup")
        try {
            context.unregisterReceiver(appInstallReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "unregister app install receiver failed", e)
        }
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "unregister receiver failed", e)
        }
        try {
            currentActivityRef = null
            refreshJob?.cancel()
            refreshJob = null
            preCreateJob?.cancel()
            preCreateJob = null
            workbenchOverlayRef?.get()?.cleanup()
            workbenchOverlayRef = null
            coroutineScope.cancel()
            Log.d(TAG, "WorkbenchManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
    class FreezeListAdapter(
    private val context: Context,
    private val appList: List<Pair<String, String>>,
    private var freezeList: List<String>,
    private val onItemClick: (String, String, Boolean) -> Unit
) : RecyclerView.Adapter<FreezeListAdapter.ViewHolder>() {

    class ViewHolder(val container: LinearLayout, val textView: TextView) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 14, 24, 14)
            gravity = Gravity.CENTER_VERTICAL
        }
        val textView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        container.addView(textView)
        return ViewHolder(container, textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (pkg, name) = appList[position]
        val isInList = freezeList.contains(pkg)
        holder.textView.text = "${if (isInList) "✓ " else "  "}$name"
        holder.textView.setTextColor(if (isInList) Color.parseColor("#FF4CAF50") else Color.WHITE)
        
        holder.container.setOnClickListener {
            onItemClick(pkg, name, isInList)
        }
    }

    override fun getItemCount(): Int = appList.size

    fun updateFreezeList(newList: List<String>) {
        freezeList = newList
        notifyDataSetChanged()
    }
}
}