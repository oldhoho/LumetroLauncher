package ru.queuejw.lumetro.components.core.sidebar
import kotlinx.coroutines.isActive

import android.view.LayoutInflater
import ru.queuejw.lumetro.R
import android.graphics.drawable.ColorDrawable
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.components.core.AppManager
import ru.queuejw.lumetro.components.core.db.tile.TileDatabase
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.core.icons.IconPackManager
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.prefs.Prefs
import ru.queuejw.lumetro.components.settings.PanelBgPickerActivity
import ru.queuejw.lumetro.components.ui.recyclerview.SpanSize
import ru.queuejw.lumetro.components.ui.recyclerview.SpannedGridLayoutManager
import ru.queuejw.lumetro.model.App
import ru.queuejw.lumetro.model.TileEntity
import java.text.Collator
import java.util.Collections
import java.util.Locale
import ru.queuejw.lumetro.components.core.sidebar.GlassTileHelper

class SidebarManager(private val context: Context) {

    enum class PanelLevel { HIDDEN, TILES, APPS }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var gestureView: View? = null
    private var gestureParams: WindowManager.LayoutParams? = null
    private var panelView: FrameLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var editPanelView: FrameLayout? = null
    private var editPanelParams: WindowManager.LayoutParams? = null
    private var currentPopup: PopupWindow? = null
    private var isPanelVisible = false
    private var currentLevel = PanelLevel.HIDDEN

    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var stripWidth = 6.dpToPx()
    private val tilesWidth = 280.dpToPx()
    private val appsWidth = screenWidth - 40.dpToPx()
    private val tilesX get() = screenWidth - tilesWidth
    private val appsX get() = screenWidth - appsWidth
    private val hiddenX = screenWidth
    private var swipeThreshold = 40.dpToPx()

    private var gestureDownX = 0f; private var gestureDownY = 0f
    private var gestureStartX = hiddenX; private var isGestureDragging = false
    private var panelDownX = 0f; private var panelDownY = 0f
    private var panelStartX = hiddenX; private var isPanelDragging = false
    private var currentAnimator: ValueAnimator? = null
    private var onPanelStateChangeListener: ((Boolean, PanelLevel) -> Unit)? = null

    private var contentContainer: FrameLayout? = null
    private var tilesRecyclerView: RecyclerView? = null
    private var panelBgBitmap: Bitmap? = null
    private var appsRecyclerView: RecyclerView? = null
    private var tileAdapter: TileAdapter? = null
    private var appAdapter: AppListAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null

    private var cachedTiles = mutableListOf<TileEntity>()
    private var cachedApps = emptyList<App>()
    private var sortedCachedApps = emptyList<App>()

    private var db: TileDatabase? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val packageManager = context.packageManager
    private val appManager = AppManager()
    private val prefs = Prefs(context)
    private var iconLoader = IconLoader(prefs.iconPackPackage != null, prefs.iconPackPackage)
    private val collator by lazy { Collator.getInstance(Locale.CHINESE) }

    private val standardColors = arrayOf(
        "#FF000000" to "纯黑", "#FF1A1A1A" to "曜石黑", "#FF2D2D2D" to "深炭黑", "#FF3C3C3C" to "石墨黑",
        "#FF4A4A4A" to "暗夜灰", "#FF5C5C5C" to "星石灰",
        "#FFD80073" to "洋红", "#FFA20025" to "深红", "#FFE51400" to "红色", "#FFFA6800" to "橙色",
        "#FFF0A30A" to "琥珀", "#FFE3C800" to "黄色", "#FF825A2C" to "棕色", "#FF6D8764" to "橄榄",
        "#FF647687" to "钢青", "#FF76608A" to "紫褐", "#FF87794E" to "灰褐", "#FFA4C400" to "青柠",
        "#FF60A917" to "绿色", "#FF008A00" to "翡翠", "#FF1BA1E2" to "青色", "#FF00ABA9" to "蓝绿",
        "#FF0050EF" to "钴蓝", "#FF6A00FF" to "靛蓝", "#FFAA00FF" to "紫罗兰", "#FFF472D0" to "粉色",
        "#FF536DFE" to "靛蓝补充", "#FF448AFF" to "亮蓝", "#FF40C4FF" to "亮蓝2", "#FF18FFFF" to "青色2",
        "#FF64FFDA" to "蓝绿补充", "#FF69F0AE" to "浅绿", "#FFB2FF59" to "亮绿", "#FFEEFF41" to "亮黄",
        "#FFFFD740" to "琥珀2", "#FFFFAB40" to "橙色2", "#FFFF6E40" to "深橙", "#FFFF5252" to "红补充",
        "#FFFF4081" to "粉红补充", "#FFE040FB" to "紫补充", "#FF7C4DFF" to "深紫", "#FF651FFF" to "紫罗兰2",
        "#FF6200EA" to "深紫2", "#FF304FFE" to "深蓝", "#FF2962FF" to "蓝补充", "#FF0091EA" to "亮蓝3",
        "#FF00B8D4" to "青色3", "#FF00BFA5" to "蓝绿2"
    )

    private var refreshTilesPending = false
    private var freezeListScrollY = 0

    init {
        try { ShizukuHelper.getInstance().init(context.applicationContext) } catch (e: Exception) { Log.e("SidebarManager", "Shizuku init failed", e) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    db = TileDatabase.getTileData(context.applicationContext ?: context)
                    val tiles = db?.getTilesDao()?.getTilesData() ?: emptyList()
                    cachedTiles.addAll(tiles.filter { it.tileType != -1 })
                    if (tiles.isEmpty()) {
                        val dao = db?.getTilesDao()
                        for (i in 0..15) {
                            dao?.insertTile(TileEntity(tilePosition = i, tileColor = null, tileCornerRadius = -1, tileType = -1, tileSize = 0, tileLabel = null, tilePackage = null))
                        }
                        val fresh = dao?.getTilesData() ?: emptyList()
                        cachedTiles.clear()
                        cachedTiles.addAll(fresh.filter { it.tileType == -1 })
                    }
                    tiles.forEach { t -> t.tilePackage?.let { iconLoader.getIconForPackage(context, it) } }
                    cachedApps = appManager.getInstalledApps(context, false)
                    sortedCachedApps = sortApps(cachedApps)
                } catch (e: Exception) { Log.e("SidebarManager", "Init failed", e) }
            }
        }, 100)
    }

    fun setOnPanelStateChangeListener(l: (Boolean, PanelLevel) -> Unit) { onPanelStateChangeListener = l }

    private fun getWindowType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

    fun configureTouchPassthrough() {
        gestureParams?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; gestureView?.let { v -> try { windowManager.updateViewLayout(v, it) } catch (e: Exception) {} } }
    }

    private fun showPopup(pw: PopupWindow, a: View) {
        pw.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame))
        val loc = IntArray(2); a.getLocationOnScreen(loc)
        pw.showAtLocation(a, Gravity.NO_GRAVITY, loc[0], loc[1] + a.height)
    }

    fun createGestureStrip() {
        if (gestureView != null) destroyGestureStrip()
        val h = if (prefs.gestureStripHeight > 0) prefs.gestureStripHeight.dpToPx() else WindowManager.LayoutParams.MATCH_PARENT
        gestureParams = WindowManager.LayoutParams(prefs.gestureStripWidth.dpToPx(), h, getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.RIGHT or Gravity.TOP; x = 0; y = prefs.gestureStripOffset.dpToPx() }
        gestureView = View(context).apply {
            setBackgroundColor(((prefs.gestureStripAlpha * 255).toInt() shl 24) or 0xFFFFFF)
            setOnTouchListener { _, e -> handleGesture(e) }
            isFocusable = false; isClickable = false; isLongClickable = false; setWillNotDraw(true)
        }
        try { windowManager.addView(gestureView, gestureParams) } catch (e: Exception) { Log.e("SidebarManager", "Failed to add gesture view", e) }
    }

    private fun handleGesture(e: MotionEvent): Boolean {
        try {
            val rx = e.rawX; val ry = e.rawY
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { gestureDownX = rx; gestureDownY = ry; gestureStartX = panelParams?.x ?: hiddenX; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = rx - gestureDownX
                    if (!isGestureDragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(ry - gestureDownY)) {
                        isGestureDragging = true
                        if (!isPanelVisible) { createPanel(); try { windowManager.addView(panelView, panelParams) } catch (e: Exception) { Log.e("SidebarManager", "addView failed", e) }; isPanelVisible = true; gestureStartX = hiddenX }
                    }
                    if (isGestureDragging) { panelParams?.x = (gestureStartX + dx).toInt().coerceIn(if (currentLevel == PanelLevel.APPS) tilesX else appsX, hiddenX); panelView?.let { windowManager.updateViewLayout(it, panelParams) } }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isGestureDragging && isPanelVisible) {
                        val cx = panelParams?.x ?: hiddenX; val tdx = rx - gestureDownX
                        when {
                            tdx < -swipeThreshold -> when (currentLevel) { PanelLevel.HIDDEN -> anim(tilesX, PanelLevel.TILES); PanelLevel.TILES -> anim(appsX, PanelLevel.APPS); else -> {} }
                            tdx > swipeThreshold -> when (currentLevel) { PanelLevel.APPS -> anim(hiddenX, PanelLevel.HIDDEN); PanelLevel.TILES -> anim(hiddenX, PanelLevel.HIDDEN); else -> {} }
                            else -> { if (cx > (tilesX + hiddenX) / 2) anim(hiddenX, PanelLevel.HIDDEN) else anim(tilesX, PanelLevel.TILES) }
                        }
                    }
                    gestureDownX = 0f; gestureDownY = 0f; isGestureDragging = false; return true
                }
            }
        } catch (e: Exception) { Log.e("SidebarManager", "Gesture error", e) }
        return false
    }

    fun createPanel() {
    if (panelView != null) return
    panelParams = WindowManager.LayoutParams(appsWidth, WindowManager.LayoutParams.MATCH_PARENT, getWindowType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.LEFT or Gravity.TOP; x = hiddenX; y = 0 }
    contentContainer = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(tilesWidth, FrameLayout.LayoutParams.MATCH_PARENT)
        try {
            if (prefs.panelBackgroundImage.isEmpty()) setBackgroundColor(Color.parseColor(prefs.panelBackgroundColor))
            alpha = prefs.panelBackgroundAlpha
        }
        catch (ex: Exception) { setBackgroundColor(Color.DKGRAY) }
        val bg = prefs.panelBackgroundImage
        if (bg.isNotEmpty()) {
            try {
                val raw = android.util.Base64.decode(bg, android.util.Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bm != null) panelBgBitmap = bm; this.background = BitmapDrawable(context.resources, bm)
            } catch (e: Exception) {}
        }
        // 液态玻璃效果（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val resId = context.resources.getIdentifier("liquid_glass_shader", "raw", context.packageName)
                if (resId != 0) {
                    val inputStream = context.resources.openRawResource(resId)
                    val shaderString = inputStream.bufferedReader().use { it.readText() }
                    val shader = RuntimeShader(shaderString)
                    loadPanelBgBitmap()?.let { bgBitmap ->
                        val bitmapShader = BitmapShader(bgBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        shader.setInputShader("content", bitmapShader)
                        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
                        shader.setFloatUniform("offset", 0f, 0f)
                        shader.setFloatUniform("cornerRadii", 16f, 16f, 16f, 16f)
                        shader.setFloatUniform("refractionHeight", 24f)
                        shader.setFloatUniform("refractionAmount", 0.5f)
                        shader.setFloatUniform("depthEffect", 1.0f)
                        val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                        setRenderEffect(effect)
                    }
                }
            } catch (e: Exception) {
                Log.e("SidebarManager", "Liquid glass effect failed", e)
            }
        }
    }
    panelView = object : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { panelDownX = ev.rawX; panelDownY = ev.rawY; panelStartX = panelParams?.x ?: hiddenX; isPanelDragging = false; return false }
                MotionEvent.ACTION_MOVE -> {
                    if (!isPanelDragging) { val adx = Math.abs(ev.rawX - panelDownX); if (adx > touchSlop && adx > Math.abs(ev.rawY - panelDownY)) { isPanelDragging = true; return true } }
                    return isPanelDragging
                }
            }
            return false
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    if (isPanelDragging) { panelParams?.x = (panelStartX + event.rawX - panelDownX).toInt().coerceIn(if (currentLevel == PanelLevel.APPS) tilesX else appsX, hiddenX); panelView?.let { windowManager.updateViewLayout(it, panelParams) }; return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPanelDragging) {
                        val cx = panelParams?.x ?: hiddenX; val dx = event.rawX - panelDownX
                        when {
                            dx < -swipeThreshold -> when (currentLevel) { PanelLevel.HIDDEN -> anim(tilesX, PanelLevel.TILES); PanelLevel.TILES -> anim(appsX, PanelLevel.APPS); else -> {} }
                            dx > swipeThreshold -> when (currentLevel) { PanelLevel.APPS -> anim(hiddenX, PanelLevel.HIDDEN); PanelLevel.TILES -> anim(hiddenX, PanelLevel.HIDDEN); else -> {} }
                            else -> { if (cx > (tilesX + hiddenX) / 2) anim(hiddenX, PanelLevel.HIDDEN) else anim(tilesX, PanelLevel.TILES) }
                        }
                        isPanelDragging = false; return true
                    }
                }
                MotionEvent.ACTION_OUTSIDE -> { if (currentLevel != PanelLevel.HIDDEN) anim(hiddenX, PanelLevel.HIDDEN); return true }
            }
            return super.onTouchEvent(event)
        }
    }.apply { addView(contentContainer) }
    if (cachedTiles.isEmpty()) { coroutineScope.launch(Dispatchers.IO) { val fresh = TileDatabase.getTileData(context.applicationContext).getTilesDao().getTilesData(); cachedTiles.clear(); cachedTiles.addAll(fresh.filter { it.tileType != -1 }); withContext(Dispatchers.Main) { loadTilesContent() } } }
    loadTilesContent()
}

    private fun loadPanelBgBitmap(): Bitmap? {
        val bg = prefs.panelBackgroundImage
        if (bg.isEmpty() && prefs.panelBackgroundImage.isEmpty()) return null
        return try {
            val raw = android.util.Base64.decode(bg, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (e: Exception) { null }
    }
    private fun loadTilesContent() {
    val ct = contentContainer ?: return; ct.removeAllViews()
    val weatherCity = context.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("city", "北京") ?: "北京"
    if (cachedTiles.none { it.tileType == -2 }) {
        cachedTiles.add(0, TileEntity(tilePosition = -1, tileColor = null, tileCornerRadius = -1, tileType = -2, tileSize = 3, tileLabel = weatherCity, tilePackage = null))
    }
    if (cachedTiles.none { it.tileType == -2 }) {
        cachedTiles.add(0, TileEntity(tilePosition = -1, tileColor = null, tileCornerRadius = -1, tileType = -2, tileSize = 2, tileLabel = "天气", tilePackage = null))
    }
    if (cachedTiles.isEmpty()) {
        ct.addView(TextView(context).apply { text = "暂无磁贴\n\n从应用列表中长按固定"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        tilesRecyclerView = null; return
    }
    tilesRecyclerView = RecyclerView(context).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setPadding(0, 0, 0, 0); clipToPadding = false; itemAnimator = null; adapter = null; setHasFixedSize(true)
    }
    val lm = SpannedGridLayoutManager(RecyclerView.VERTICAL, 8, 4).apply {
        spanSizeLookup = SpannedGridLayoutManager.SpanSizeLookup { p -> when (cachedTiles.getOrNull(p)?.tileSize) { 1 -> SpanSize(1, 2); 2 -> SpanSize(2, 1); 3 -> SpanSize(2, 2); 4 -> SpanSize(4, 2); else -> SpanSize(1, 1) } }
    }
    cachedTiles.forEach { t -> t.tilePackage?.let { iconLoader.getIconForPackage(context, it) } }
    tileAdapter = TileAdapter(cachedTiles, 48.dpToPx())
    tilesRecyclerView?.layoutManager = lm; tilesRecyclerView?.adapter = tileAdapter
    ct.addView(tilesRecyclerView); setupDragAndDrop()
}

private fun setupDragAndDrop() {
    itemTouchHelper?.attachToRecyclerView(null)
    itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
            val fp = vh.bindingAdapterPosition; val tp = t.bindingAdapterPosition
            if (isFrozen(cachedTiles[fp]) || isFrozen(cachedTiles[tp])) return false
            if (fp < tp) for (i in fp until tp) Collections.swap(cachedTiles, i, i + 1) else for (i in fp downTo tp + 1) Collections.swap(cachedTiles, i, i - 1)
            tileAdapter?.notifyItemMoved(fp, tp); return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {}
        override fun isLongPressDragEnabled() = true
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            coroutineScope.launch(Dispatchers.IO) {
                val dao = db?.getTilesDao(); val all = dao?.getTilesData()?.toMutableList() ?: return@launch
                cachedTiles.forEachIndexed { i, t -> all.indexOfFirst { it.id == t.id }.takeIf { it >= 0 }?.let { all[it].tilePosition = i } }
                all.sortBy { it.tilePosition }; dao.updateAllTiles(all)
            }
        }
    })
    itemTouchHelper?.attachToRecyclerView(tilesRecyclerView)
}

fun refreshPanelBackground() {
    val bg = prefs.panelBackgroundImage
    if (bg.isNotEmpty()) {
        try {
            val raw = android.util.Base64.decode(bg, android.util.Base64.DEFAULT)
            val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bm != null) panelBgBitmap = bm; contentContainer?.background = BitmapDrawable(context.resources, bm)
        } catch (e: Exception) {}
    }
}

fun refreshTilesIfNeeded() {
    coroutineScope.launch(Dispatchers.IO) {
        val all = db?.getTilesDao()?.getTilesData() ?: return@launch
        val validTiles = all.filter { it.tileType != -1 }
        validTiles.forEach { t -> t.tilePackage?.let { iconLoader.getIconForPackage(context, it) } }
        withContext(Dispatchers.Main) {
            cachedTiles.clear()
            cachedTiles.addAll(validTiles)
            tileAdapter = TileAdapter(cachedTiles, 48.dpToPx())
            tilesRecyclerView?.adapter = tileAdapter
            if (cachedTiles.isEmpty() && currentLevel == PanelLevel.TILES) loadTilesContent()
        }
    }
}

fun onAppInstalled(pkg: String) {
    if (!prefs.autoPinEnabled) return
    coroutineScope.launch(Dispatchers.IO) {
        val dao = db?.getTilesDao(); val tiles = dao?.getTilesData()?.toMutableList() ?: return@launch
        val slot = tiles.indexOfFirst { it.tileType == -1 }
        if (slot >= 0) {
            val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (ex: Exception) { pkg.substringAfterLast(".") }
            tiles[slot] = TileEntity(tiles[slot].id, slot, null, -1, 0, 0, name, pkg)
            dao.updateAllTiles(tiles); withContext(Dispatchers.Main) { refreshTilesIfNeeded() }
        }
    }
}

fun onAppRemoved(pkg: String) {
    coroutineScope.launch(Dispatchers.IO) {
        val dao = db?.getTilesDao(); val tiles = dao?.getTilesData()?.toMutableList() ?: return@launch
        tiles.indexOfFirst { it.tilePackage == pkg }.takeIf { it >= 0 }?.let { idx ->
            tiles[idx] = TileEntity(tiles[idx].id, idx, null, -1, -1, 0, null, null)
            dao.updateAllTiles(tiles); withContext(Dispatchers.Main) { refreshTilesIfNeeded() }
        }
    }
}

fun refreshAppsIfNeeded() {
    coroutineScope.launch {
        cachedApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val all = pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.GET_DISABLED_COMPONENTS)
            if (!isActive) return@withContext emptyList<App>()
            val list = ArrayList<App>()
            for (info in all) {
                if (info.packageName == context.packageName) continue
                val activities = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(info.packageName),
                    PackageManager.GET_DISABLED_COMPONENTS
                )
                if (activities.isEmpty()) continue
                list.add(App(info.loadLabel(packageManager).toString(), info.packageName, 0))
            }
            list
        }
        sortedCachedApps = sortApps(cachedApps)
        appAdapter?.updateData(filteredApps())
    }
}

// ===== TileAdapter =====
inner class TileAdapter(private var tiles: List<TileEntity>, private val iconSize: Int) : RecyclerView.Adapter<TileAdapter.VH>() {
    inner class VH(val c: FrameLayout, val icon: ImageView, val label: TextView, val back: FrameLayout, val backLabel: TextView) : RecyclerView.ViewHolder(c)
    fun updateData(t: List<TileEntity>) { if (t != tiles) { tiles = t; notifyDataSetChanged() } }
    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): VH {
        val outer = FrameLayout(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            setPadding(3, 3, 3, 3)
        }
        val front = FrameLayout(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.VISIBLE
        }
        val iv = ImageView(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tv = TextView(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 2.dpToPx()
            }
            textSize = 10f; setTextColor(Color.WHITE); maxLines = 1
            setShadowLayer(2f, 0f, 1f, Color.BLACK); gravity = Gravity.CENTER
        }
        front.addView(iv); front.addView(tv)
        val back = FrameLayout(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#AA000000"))
        }
        val backLabel = TextView(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER }
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 4; setTypeface(null, android.graphics.Typeface.BOLD)
        }
        back.addView(backLabel)
        outer.addView(front); outer.addView(back)
        return VH(outer, iv, tv, back, backLabel)
    }
    override fun onBindViewHolder(h: VH, pos: Int) {
    val t = tiles[pos]; h.label.text = t.tileLabel ?: ""; h.icon.visibility = View.VISIBLE
    val bg = try { t.tileColor?.let { Color.parseColor(it) } ?: Color.parseColor("#FF0050EF") } catch (ex: Exception) { Color.parseColor("#FF0050EF") }
        if (t.tileType == -2) {
    h.label.visibility = View.GONE
    h.icon.visibility = View.GONE
    h.c.post {
        h.c.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000)
            setCornerRadius(16f)
            setStroke(1, 0x30FFFFFF.toInt())
        }
        val wv = LayoutInflater.from(context).inflate(R.layout.tile_weather, null)
        val district = wv.findViewById<TextView>(R.id.districtName)
        val temp = wv.findViewById<TextView>(R.id.today_tem)
        val tomorrow = wv.findViewById<TextView>(R.id.tomorrow)
        val overmorrow = wv.findViewById<TextView>(R.id.overmorrow)
        val feels = wv.findViewById<TextView>(R.id.tv_feels)
        val humidity = wv.findViewById<TextView>(R.id.tv_humidity)
        val wind = wv.findViewById<TextView>(R.id.tv_wind)
        val update = wv.findViewById<TextView>(R.id.updateTime)
        district.text = t.tileLabel ?: "北京"
        h.c.removeAllViews()
        h.c.addView(wv)
        coroutineScope.launch(Dispatchers.IO) {
            val data = ru.queuejw.lumetro.components.weather.WeatherFetcher.fetchWeather(t.tileLabel ?: "北京")
            withContext(Dispatchers.Main) {
                if (data != null) {
                    temp.text = "${data.temp}°"
                    update.text = data.text
                    feels.text = "${data.feelsLike}°"
                    humidity.text = "${data.humidity}%"
                    wind.text = "${data.windDir}${data.windScale}级"
                    if (data.forecast.size >= 2) {
                        tomorrow.text = "${data.forecast[0].tempMax}°/${data.forecast[0].tempMin}° ${data.forecast[0].text}"
                    }
                    if (data.forecast.size >= 3) {
                        overmorrow.text = "${data.forecast[1].tempMax}°/${data.forecast[1].tempMin}° ${data.forecast[1].text}"
                    }
                }
            }
        }
    }
}
    val cornerRadiusPx = if (t.tileCornerRadius != -1) t.tileCornerRadius.dpToPx().toFloat() else 0f
    val base = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.TRANSPARENT); setCornerRadius(cornerRadiusPx) }
    val shadow = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColors(intArrayOf(0x00000000.toInt(), 0x40000000.toInt())); gradientType = GradientDrawable.LINEAR_GRADIENT; orientation = GradientDrawable.Orientation.TOP_BOTTOM; setCornerRadius(cornerRadiusPx) }
    val highlight = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColors(intArrayOf(0x28FFFFFF.toInt(), 0x00000000.toInt())); gradientType = GradientDrawable.LINEAR_GRADIENT; orientation = GradientDrawable.Orientation.TOP_BOTTOM; setCornerRadius(cornerRadiusPx) }
    val ld = android.graphics.drawable.LayerDrawable(arrayOf(base, shadow, highlight))
    GlassTileHelper.applyGlassShell(h.c, cornerRadiusPx)
    var hasBg = false; val pkg = t.tilePackage
    if (!pkg.isNullOrEmpty()) {
        val bp = context.getSharedPreferences("tile_custom_icons", Context.MODE_PRIVATE).getString("bg_$pkg", null)
        if (bp != null) try {
            val raw = android.util.Base64.decode(bp, android.util.Base64.DEFAULT); val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bm != null) { h.c.background = BitmapDrawable(context.resources, bm); h.icon.visibility = View.GONE; hasBg = true }
        } catch (ex: Exception) {}
    }
    val sm = when (t.tileSize) { 1 -> 1.2f; 2 -> 1.2f; 3 -> 1.5f; 4 -> 1.8f; else -> 1f }; val ss = (iconSize * sm).toInt()
    h.icon.layoutParams = FrameLayout.LayoutParams(ss, ss).apply { gravity = Gravity.CENTER }
    if (!hasBg && !pkg.isNullOrEmpty()) {
        val bmp = iconLoader.getIconForPackage(context, pkg)
        if (bmp != null) h.icon.setImageBitmap(Bitmap.createScaledBitmap(bmp, ss, ss, true))
    }
    val isFrozenApp = pkg?.let { FreezeManager.isFrozen(context, it) } ?: false
    h.c.scaleX = 0.8f; h.c.scaleY = 0.8f; h.c.alpha = 0f
    h.c.animate().scaleX(1f).scaleY(1f).alpha(if (isFrozenApp) 0.5f else 1f).setDuration(300).setStartDelay((pos % 8) * 30L).start()
    h.c.setOnClickListener {
        if (isFrozenApp && pkg != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val sh = ShizukuHelper.getInstance()
                if (sh.unfreezeApp(pkg)) {
                    FreezeManager.setFrozen(context, pkg, false)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已解冻，启动中...", Toast.LENGTH_SHORT).show()
                        refreshTilesIfNeeded()
                        try { AppManager.launchApp(pkg, context) } catch (e: Exception) {}
                        hidePanel()
                    }
                } else { withContext(Dispatchers.Main) { Toast.makeText(context, "解冻失败", Toast.LENGTH_SHORT).show() } }
            }
        } else {
            h.c.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.7f).setDuration(100).withEndAction {
                pkg?.let { coroutineScope.launch { try { AppManager.launchApp(it, context) } catch (ex: Exception) {}; hidePanel() } }
            }.start()
        }
    }
    h.c.setOnLongClickListener { showTilePopup(h.c, t); true }
    val doFlip = object : Runnable {
        override fun run() {
            val anim = ValueAnimator.ofFloat(0f, 180f).apply {
                duration = 350 + (Math.random() * 250).toLong()
                repeatCount = 1
                repeatMode = ValueAnimator.REVERSE
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val v = a.animatedValue as Float
                    h.c.rotationY = v
                }
            }
            anim.start()
            h.c.postDelayed(this, 5000 + (Math.random() * 25000).toLong())
        }
    }
    h.c.postDelayed(doFlip, 1000 + (Math.random() * 29000).toLong())
}
    override fun getItemCount() = tiles.size
}
private fun showTilePopup(a: View, t: TileEntity) {
    currentPopup?.dismiss()
    val pv = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(8, 8, 8, 8) }
    val pkg = t.tilePackage
    val inFreezeList = pkg?.let { FreezeManager.getList(context).contains(it) } ?: false
    if (inFreezeList) {
        val isSysFrozen = pkg?.let { FreezeManager.isFrozen(context, it) } ?: false
        pv.addView(tv(if (isSysFrozen) "解冻应用" else "冻结应用") {
            currentPopup?.dismiss()
            pkg?.let { p ->
                coroutineScope.launch(Dispatchers.IO) {
                    val sh = ShizukuHelper.getInstance()
                    if (isSysFrozen) {
                        if (sh.unfreezeApp(p)) { FreezeManager.setFrozen(context, p, false); withContext(Dispatchers.Main) { refreshTilesIfNeeded(); Toast.makeText(context, "已解冻", Toast.LENGTH_SHORT).show() } }
                        else withContext(Dispatchers.Main) { Toast.makeText(context, "解冻失败", Toast.LENGTH_SHORT).show() }
                    } else {
                        if (sh.freezeApp(p)) { FreezeManager.setFrozen(context, p, true); withContext(Dispatchers.Main) { refreshTilesIfNeeded(); Toast.makeText(context, "已冻结", Toast.LENGTH_SHORT).show() } }
                        else withContext(Dispatchers.Main) { Toast.makeText(context, "冻结失败", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        })
    }
    if (t.tileType == -2) {
        pv.addView(tv("切换城市") {
            currentPopup?.dismiss()
            val input = EditText(context).apply { setText(t.tileLabel ?: "北京"); setSingleLine(); setPadding(16, 8, 16, 8); setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE); setHint("输入城市名"); setHintTextColor(Color.GRAY) }
            val popup = PopupWindow(input, 500.dpToPx(), 100.dpToPx(), true).apply { setBackgroundDrawable(ColorDrawable(Color.BLACK)); showAtLocation(a, Gravity.CENTER, 0, 0) }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    val city = input.text.toString().trim()
                    if (city.isNotEmpty()) {
                        t.tileLabel = city
                        context.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().putString("city", city).apply()
                        popup.dismiss()
                        refreshTilesIfNeeded()
                    }
                }
                true
            }
        })
    }
    pv.addView(tv("编辑") { currentPopup?.dismiss(); showEditPanel(t) })
    pv.addView(tv("更改大小") { currentPopup?.dismiss(); t.tileSize = (t.tileSize + 1) % 5; coroutineScope.launch(Dispatchers.IO) { db?.getTilesDao()?.updateTile(t); withContext(Dispatchers.Main) { refreshTilesIfNeeded() } } })
    pv.addView(tv("取消固定") { currentPopup?.dismiss(); unpinTile(t) })
    pkg?.let { pv.addView(tv("应用信息") { currentPopup?.dismiss(); context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$it")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) }
    currentPopup = PopupWindow(pv, 200.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT, true); showPopup(currentPopup!!, a)
}

private fun tv(text: String, click: () -> Unit) = TextView(context).apply { this.text = text; textSize = 16f; setTextColor(Color.WHITE); setPadding(32, 16, 32, 16); setOnClickListener { click() } }

private fun isFrozen(t: TileEntity) = (context.getSharedPreferences("tile_settings", Context.MODE_PRIVATE).getStringSet("frozen_tiles", emptySet()) ?: emptySet()).contains(t.id.toString())

private fun unpinTile(t: TileEntity) {
    coroutineScope.launch(Dispatchers.IO) {
        val dao = db?.getTilesDao(); val tiles = dao?.getTilesData()?.toMutableList() ?: return@launch
        tiles.indexOfFirst { it.id == t.id }.takeIf { it >= 0 }?.let { idx ->
            tiles[idx] = TileEntity(tiles[idx].id, idx, null, -1, -1, 0, null, null)
            dao.updateAllTiles(tiles)
            val remainingSlots = tiles.count { it.tileType == -1 }
            if (remainingSlots < 2) {
                val newPos = tiles.size
                dao.insertTile(TileEntity(tilePosition = newPos, tileColor = null, tileCornerRadius = -1, tileType = -1, tileSize = 0, tileLabel = null, tilePackage = null))
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, "已取消固定", Toast.LENGTH_SHORT).show(); refreshTilesIfNeeded() }
        }
    }
}

private fun filteredApps(): List<App> {
    val pinned = cachedTiles.filter { it.tileType != -1 && it.tilePackage != null }.map { it.tilePackage }.toSet()
    val hidden = FreezeManager.getHiddenList(context).toSet()
    return sortApps(cachedApps).filter { it.mPackage !in pinned && it.mPackage !in hidden }
}

private fun sortApps(apps: List<App>): List<App> = apps.sortedWith(compareBy(collator) { app: App -> app.mName })

private fun sortByUsage(apps: List<App>): List<App> {
    val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return apps
    val now = System.currentTimeMillis()
    val stats = usageManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, 0, now)
    val usageMap = mutableMapOf<String, Long>()
    stats?.forEach { stat -> usageMap[stat.packageName] = stat.lastTimeUsed }
    return apps.sortedByDescending { usageMap[it.mPackage] ?: 0L }
}

// ===== 设置弹窗 =====
private fun showPanelBgDialog() {
    currentPopup?.dismiss()
    val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16); setBackgroundColor(Color.DKGRAY) }
    val scrollView = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(300.dpToPx(), 450.dpToPx()) }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    content.addView(TextView(context).apply { text = "面板背景颜色"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 8, 0, 4) })
    val colorInput = EditText(context).apply { setText(prefs.panelBackgroundColor); setSingleLine(); setPadding(8, 8, 8, 8); setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE) }
    content.addView(colorInput)

    content.addView(TextView(context).apply { text = "面板背景图(Base64)"; setTextColor(Color.WHITE); textSize = 12f; setPadding(0, 12, 0, 4) })
    val bgImageInput = EditText(context).apply { hint = "留空清除"; setSingleLine(); setPadding(8, 8, 8, 8); setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE) }
    content.addView(bgImageInput)
    content.addView(android.widget.Button(context).apply { text = "从相册选择背景图"; setOnClickListener { currentPopup?.dismiss(); hidePanel(); context.startActivity(Intent(context, PanelBgPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })

    content.addView(TextView(context).apply { text = "桌面壁纸"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    content.addView(android.widget.Button(context).apply { text = "选择壁纸"; setOnClickListener { currentPopup?.dismiss(); hidePanel(); context.startActivity(Intent(context, PanelBgPickerActivity::class.java).putExtra("target", "main_bg").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })

    content.addView(TextView(context).apply { text = "面板透明度"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val alphaSlider = SeekBar(context).apply { max = 100; progress = (prefs.panelBackgroundAlpha * 100).toInt() }
    content.addView(alphaSlider)

    content.addView(TextView(context).apply { text = "磁贴背景透明度"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val tileAlphaSlider = SeekBar(context).apply { max = 100; progress = (prefs.tileAlpha * 100).toInt() }
    content.addView(tileAlphaSlider)

    content.addView(TextView(context).apply { text = "手势灵敏度"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val sensSlider = SeekBar(context).apply { max = 100; progress = swipeThreshold / 2 }
    content.addView(sensSlider)

    content.addView(TextView(context).apply { text = "手势条宽度(dp)"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val stripWInput = EditText(context).apply { setText(prefs.gestureStripWidth.toString()); setSingleLine(); setPadding(8, 8, 8, 8); setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE) }
    content.addView(stripWInput)

    content.addView(TextView(context).apply { text = "手势条高度(dp, 0=全屏)"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val stripHInput = EditText(context).apply { setText(prefs.gestureStripHeight.toString()); setSingleLine(); setPadding(8, 8, 8, 8); setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE) }
    content.addView(stripHInput)

    content.addView(TextView(context).apply { text = "手势条偏移(dp)"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val stripOInput = EditText(context).apply { setText(prefs.gestureStripOffset.toString()); setSingleLine(); setPadding(8, 8, 8, 8); setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE) }
    content.addView(stripOInput)

    content.addView(TextView(context).apply { text = "手势条透明度"; setTextColor(Color.WHITE); textSize = 14f; setPadding(0, 12, 0, 4) })
    val stripAlphaSlider = SeekBar(context).apply { max = 100; progress = (prefs.gestureStripAlpha * 100).toInt() }
    content.addView(stripAlphaSlider)

    content.addView(android.widget.Button(context).apply {
        text = "选择图标包"
        setOnClickListener { showIconPackPicker() }
    })
    content.addView(android.widget.Button(context).apply {
        text = "冻结列表"
        setOnClickListener { currentPopup?.dismiss(); showFreezeListDialog() }
    })
    content.addView(android.widget.Button(context).apply {
        text = "应用"
        setOnClickListener {
            val c = colorInput.text.toString()
            if (c.isNotEmpty()) prefs.panelBackgroundColor = c
            prefs.panelBackgroundAlpha = alphaSlider.progress / 100f
            prefs.tileAlpha = tileAlphaSlider.progress / 100f
            swipeThreshold = sensSlider.progress * 2

            stripWInput.text.toString().toIntOrNull()?.let { if (it > 0) { prefs.gestureStripWidth = it; destroyGestureStrip(); createGestureStrip() } }
            stripHInput.text.toString().toIntOrNull()?.let { if (it >= 0) { prefs.gestureStripHeight = it; destroyGestureStrip(); createGestureStrip() } }
            stripOInput.text.toString().toIntOrNull()?.let { prefs.gestureStripOffset = it; destroyGestureStrip(); createGestureStrip() }
            prefs.gestureStripAlpha = stripAlphaSlider.progress / 100f
            destroyGestureStrip(); createGestureStrip()

            val bg = bgImageInput.text.toString()
            if (bg.isNotEmpty()) prefs.panelBackgroundImage = bg
            try {
                if (bg.isNotEmpty()) {
                    val raw = android.util.Base64.decode(bg, android.util.Base64.DEFAULT)
                    val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    if (bm != null) panelBgBitmap = bm; contentContainer?.background = BitmapDrawable(context.resources, bm)
                }
            } catch (e: Exception) { }
            currentPopup?.dismiss(); hidePanel()
        }
    })
    content.addView(android.widget.Button(context).apply {
        text = "导出设置"
        setOnClickListener {
            try {
                val prefsDir = java.io.File(context.filesDir.parent, "shared_prefs")
                val src = java.io.File(prefsDir, "settings.xml")
                val destDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dest = java.io.File(destDir, "lumetro_backup.xml")
                val freezeSrc = java.io.File(prefsDir, "freeze.xml")
                val freezeDest = java.io.File(destDir, "lumetro_freeze_backup.xml")
                if (freezeSrc.exists()) freezeSrc.copyTo(freezeDest, true)
                val tileDbSrc = java.io.File(context.filesDir.parent, "databases/userTiles")
                val tileDbDest = java.io.File(destDir, "lumetro_tiles_backup.db")
                coroutineScope.launch(Dispatchers.IO) { db?.getTilesDao()?.getTilesData() }
                db?.close()
                if (tileDbSrc.exists()) tileDbSrc.copyTo(tileDbDest, true)
                src.copyTo(dest, true)
                Toast.makeText(context, "已导出: ${dest.absolutePath}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() }
        }
    })
    content.addView(android.widget.Button(context).apply {
        text = "导入设置"
        setOnClickListener {
            try {
    val prefsDir = java.io.File(context.filesDir.parent, "shared_prefs")
    val dest = java.io.File(prefsDir, "settings.xml")
    val srcDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    val freezeSrcFile = java.io.File(srcDir, "lumetro_freeze_backup.xml")
    val freezeDestFile = java.io.File(prefsDir, "freeze.xml")
    if (freezeSrcFile.exists()) freezeSrcFile.copyTo(freezeDestFile, true)
    val tileDbSrcFile = java.io.File(srcDir, "lumetro_tiles_backup.db")
    val tileDbDestFile = java.io.File(context.filesDir.parent, "databases/userTiles")
    if (tileDbSrcFile.exists()) {
    db?.close()
    // 删掉旧数据库文件和wal/shm
    tileDbDestFile.delete()
    java.io.File(context.filesDir.parent, "databases/userTiles-wal").delete()
    java.io.File(context.filesDir.parent, "databases/userTiles-shm").delete()
    tileDbSrcFile.copyTo(tileDbDestFile, true)
}
    val src = java.io.File(srcDir, "lumetro_backup.xml")
    if (src.exists()) {
        src.copyTo(dest, true)
        Toast.makeText(context, "已导入，重启生效", Toast.LENGTH_SHORT).show()
    } else { Toast.makeText(context, "备份文件不存在", Toast.LENGTH_SHORT).show() }
} catch (e: Exception) { Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show() }
        }
    })

    scrollView.addView(content); layout.addView(scrollView)
    currentPopup?.dismiss()
    currentPopup = PopupWindow(layout, 320.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT, true)
    currentPopup?.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame))
    currentPopup?.showAtLocation(contentContainer, Gravity.CENTER, 0, 0)
}
private fun showIconPackPicker() {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 16, 16, 16)
        setBackgroundColor(Color.DKGRAY)
    }
    layout.addView(TextView(context).apply {
        text = "选择图标包"
        textSize = 20f
        setTextColor(Color.CYAN)
        setPadding(0, 8, 0, 16)
    })
    val listLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    listLayout.addView(TextView(context).apply { text = "加载中..."; setTextColor(Color.GRAY) })
    val scrollView = ScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(300.dpToPx(), 350.dpToPx())
    }
    scrollView.addView(listLayout)
    layout.addView(scrollView)
    layout.addView(android.widget.Button(context).apply {
        text = "关闭"
        setOnClickListener { currentPopup?.dismiss() }
    })
    currentPopup?.dismiss()
    currentPopup = PopupWindow(layout, 340.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT, true)
    currentPopup?.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame))
    currentPopup?.showAtLocation(contentContainer, Gravity.CENTER, 0, 0)
    coroutineScope.launch(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val intents1 = pm.queryIntentActivities(Intent("org.adw.launcher.THEMES"), PackageManager.GET_META_DATA)
            val intents2 = pm.queryIntentActivities(Intent("com.gau.go.launcherex.theme"), PackageManager.GET_META_DATA)
            val intents3 = pm.queryIntentActivities(Intent("com.novalauncher.THEME"), PackageManager.GET_META_DATA)
            val allIntents = (intents1 + intents2 + intents3).distinctBy { it.activityInfo.packageName }
            val iconPacks = allIntents.mapNotNull { ri ->
                try {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == context.packageName) null
                    else {
                        val app = pm.getApplicationInfo(pkg, 0)
                        pkg to pm.getApplicationLabel(app).toString()
                    }
                } catch (e: Exception) { null }
            }.distinctBy { it.first }.sortedBy { it.second }
            withContext(Dispatchers.Main) {
                listLayout.removeAllViews()
                if (iconPacks.isEmpty()) {
                    listLayout.addView(TextView(context).apply {
                        text = "未找到图标包"; setTextColor(Color.GRAY); setPadding(0, 16, 0, 0)
                    })
                } else {
                    iconPacks.forEach { (pkg, name) ->
                        val item = TextView(context).apply {
                            this.text = name; textSize = 16f; setTextColor(Color.WHITE)
                            setPadding(16, 14, 16, 14); setBackgroundColor(Color.parseColor("#FF2A2A2A"))
                            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            params.setMargins(0, 0, 0, 4); layoutParams = params
                            setOnClickListener {
                                prefs.iconPackPackage = pkg
                                reloadIconPack()
                                currentPopup?.dismiss()
                                Toast.makeText(context, "已应用: $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                        listLayout.addView(item)
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listLayout.removeAllViews()
                listLayout.addView(TextView(context).apply {
                    text = "加载失败: ${e.message}"; setTextColor(Color.RED)
                })
            }
        }
    }
}

private fun performOneKeyFreeze() {
    val sh = ShizukuHelper.getInstance()
    ShizukuHelper.getInstance().checkStatus()
    if (!sh.isReady()) { Toast.makeText(context, "Shizuku 未就绪", Toast.LENGTH_SHORT).show(); return }
    val list = FreezeManager.getList(context)
    if (list.isEmpty()) { Toast.makeText(context, "冻结列表为空", Toast.LENGTH_SHORT).show(); return }
    coroutineScope.launch(Dispatchers.IO) {
        var count = 0
        for (pkg in list) {
            if (FreezeManager.isFrozen(context, pkg)) continue
            iconLoader.getIconForPackage(context, pkg)
            if (sh.freezeApp(pkg)) { FreezeManager.setFrozen(context, pkg, true); count++; Thread.sleep(50) }
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "已冻结 $count 个应用", Toast.LENGTH_SHORT).show()
            refreshTilesIfNeeded()
            refreshAppsIfNeeded()
        }
    }
}

private fun showFreezeListDialog() {
    val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(Color.DKGRAY) }
    layout.addView(TextView(context).apply { text = "可冻结应用列表"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 8, 0, 16) })
    val scrollView = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(280.dpToPx(), 350.dpToPx()) }
    val listLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val freezeList = FreezeManager.getList(context)
    val pm = context.packageManager
    val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    val appList = ArrayList<App>()
    for (info in installedApps) {
        if (info.packageName == context.packageName) continue
        if ((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
        appList.add(App(info.loadLabel(pm).toString(), info.packageName, 0))
    }
    appList.sortBy { it.mName }
    for (app in appList) {
        val pkg = app.mPackage ?: continue
        val isInList = freezeList.contains(pkg)
        val itemLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8); gravity = Gravity.CENTER_VERTICAL }
        itemLayout.addView(TextView(context).apply { text = app.mName; setTextColor(Color.WHITE); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        itemLayout.addView(TextView(context).apply {
            text = if (isInList) "移除" else "添加"; setTextColor(if (isInList) Color.RED else Color.GREEN); textSize = 14f; setPadding(16, 4, 0, 4)
            setOnClickListener {
                if (isInList) FreezeManager.removeFromList(context, pkg) else FreezeManager.addToList(context, pkg)
                freezeListScrollY = scrollView.scrollY
                currentPopup?.dismiss(); showFreezeListDialog()
            }
        })
        listLayout.addView(itemLayout)
    }
    val hiddenApps = FreezeManager.getHiddenList(context)
    if (hiddenApps.isNotEmpty()) {
        listLayout.addView(TextView(context).apply { text = "已隐藏应用"; textSize = 16f; setTextColor(Color.YELLOW); setPadding(0, 16, 0, 8) })
        for (pkg in hiddenApps) {
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            val itemLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8); gravity = Gravity.CENTER_VERTICAL }
            itemLayout.addView(TextView(context).apply { text = name; setTextColor(Color.WHITE); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            itemLayout.addView(TextView(context).apply { text = "恢复"; setTextColor(Color.GREEN); textSize = 14f; setPadding(16, 4, 0, 4); setOnClickListener { FreezeManager.toggleHidden(context, pkg); currentPopup?.dismiss(); showFreezeListDialog() } })
            listLayout.addView(itemLayout)
        }
    }
    layout.addView(android.widget.Button(context).apply {
        text = "一键解冻"
        setOnClickListener {
            currentPopup?.dismiss()
            coroutineScope.launch(Dispatchers.IO) {
                val list = FreezeManager.getList(context)
                val sh = ShizukuHelper.getInstance()
                var count = 0
                for (pkg in list) {
                    if (!FreezeManager.isFrozen(context, pkg)) continue
                    if (sh.unfreezeApp(pkg)) { FreezeManager.setFrozen(context, pkg, false); count++ }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已解冻 $count 个应用", Toast.LENGTH_SHORT).show()
                    refreshTilesIfNeeded()
                    refreshAppsIfNeeded()
                }
            }
        }
    })
    scrollView.addView(listLayout); layout.addView(scrollView)
    layout.addView(android.widget.Button(context).apply { text = "关闭"; setOnClickListener { currentPopup?.dismiss() } })
    currentPopup?.dismiss()
    currentPopup = PopupWindow(layout, 320.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT, true)
    currentPopup?.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame))
    currentPopup?.showAtLocation(contentContainer, Gravity.CENTER, 0, 0)
    scrollView.post { scrollView.scrollTo(0, freezeListScrollY) }
}

// ===== 编辑面板 =====
private fun showEditPanel(t: TileEntity) {
    hideEditPanel()
    editPanelParams = WindowManager.LayoutParams((screenWidth * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, getWindowType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
    editPanelView = FrameLayout(context).apply { addView(createEditPanelView(t)); setBackgroundColor(Color.WHITE); setOnTouchListener { _, _ -> true } }
    try { windowManager.addView(editPanelView, editPanelParams) } catch (ex: Exception) {}
}
private fun hideEditPanel() { editPanelView?.let { try { windowManager.removeView(it) } catch (ex: Exception) {} }; editPanelView = null; editPanelParams = null; currentPopup?.dismiss(); currentPopup = null }

fun reloadIconPack() {
    iconLoader.resetIconLoader(true)
    iconLoader = IconLoader(prefs.iconPackPackage != null, prefs.iconPackPackage)
    coroutineScope.launch(Dispatchers.IO) {
        db?.getTilesDao()?.getTilesData()?.filter { it.tileType != -1 && !it.tilePackage.isNullOrEmpty() }?.forEach { iconLoader.getIconForPackage(context, it.tilePackage!!) }
        withContext(Dispatchers.Main) { refreshTilesIfNeeded(); if (currentLevel == PanelLevel.APPS) loadAppsContent() }
    }
}

private fun createEditPanelView(t: TileEntity): View {
    val sv = ScrollView(context)
    val rl = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 20) }
    rl.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = "编辑磁贴"; textSize = 18f; setTextColor(Color.BLACK); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(TextView(context).apply { text = "✕"; textSize = 20f; setTextColor(Color.BLACK); setPadding(20, 0, 0, 0); setOnClickListener { hideEditPanel() } })
    })
    rl.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 10; bottomMargin = 10 }; setBackgroundColor(Color.LTGRAY) })
    rl.addView(TextView(context).apply { text = "标签"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 0, 0, 5) })
    val li = EditText(context).apply { setText(t.tileLabel); setSingleLine(); setPadding(15, 10, 15, 10); isFocusable = true; isFocusableInTouchMode = true }
    rl.addView(li)
    li.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_UP) { li.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(li, InputMethodManager.SHOW_IMPLICIT) }; false }
    rl.addView(TextView(context).apply { text = "背景"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5) })
    val il = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    il.addView(ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(when (t.tileSize) { 1 -> 48.dpToPx(); 2 -> 72.dpToPx(); 3 -> 90.dpToPx(); 4 -> 120.dpToPx(); else -> 48.dpToPx() }, when (t.tileSize) { 1 -> 48.dpToPx(); 2 -> 72.dpToPx(); 3 -> 90.dpToPx(); 4 -> 120.dpToPx(); else -> 48.dpToPx() }); scaleType = ImageView.ScaleType.FIT_CENTER; t.tilePackage?.let { pkg -> coroutineScope.launch(Dispatchers.IO) { iconLoader.getIconForPackage(context, pkg)?.let { withContext(Dispatchers.Main) { setImageBitmap(it) } } } } })
    il.addView(TextView(context).apply {
        text = "点击选择背景图"; textSize = 14f; setTextColor(Color.BLUE); setPadding(15, 0, 0, 0)
        setOnClickListener {
            val intent = Intent(context, PanelBgPickerActivity::class.java).apply { putExtra("tile_package", t.tilePackage); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            hideEditPanel(); hidePanel()
        }
    })
    il.addView(TextView(context).apply { text = "清除"; textSize = 14f; setTextColor(Color.RED); setPadding(15, 0, 0, 0); setOnClickListener { context.getSharedPreferences("tile_custom_icons", Context.MODE_PRIVATE).edit().remove("bg_" + t.tilePackage).apply(); refreshTilesIfNeeded(); hideEditPanel() } })
    rl.addView(il)
    rl.addView(TextView(context).apply { text = "磁贴大小"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5) })
    val so = arrayOf("小", "中", "大", "横条", "竖条"); var ss = t.tileSize
    val st = TextView(context).apply { text = "当前: ${so[ss]}"; setPadding(10, 5, 10, 5) }; rl.addView(st)
    rl.addView(android.widget.Button(context).apply {
        text = "选择大小"; setOnClickListener {
            currentPopup?.dismiss()
            val scrollView = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(250.dpToPx(), 350.dpToPx()) }
            val colorList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(8, 8, 8, 8) }
            so.forEachIndexed { i, o -> colorList.addView(tv(o) { ss = i; st.text = "当前: ${so[ss]}"; currentPopup?.dismiss() }) }
            scrollView.addView(colorList)
            currentPopup = PopupWindow(scrollView, 250.dpToPx(), 350.dpToPx(), true); showPopup(currentPopup!!, it)
        }
    })
    rl.addView(TextView(context).apply { text = "颜色"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5) })
    var sc = t.tileColor ?: "#FF0050EF"
    val cp = View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40.dpToPx()).apply { setMargins(0, 5, 0, 5) }; setBackgroundColor(Color.parseColor(sc)) }; rl.addView(cp)
    rl.addView(android.widget.Button(context).apply {
        text = "选择颜色"; setOnClickListener {
            currentPopup?.dismiss()
            val scrollView = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(250.dpToPx(), 400.dpToPx()) }
            val colorList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(8, 8, 8, 8) }
            standardColors.forEach { (c, n) -> val il2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 8, 16, 8) }; il2.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()); setBackgroundColor(Color.parseColor(c)) }); il2.addView(TextView(context).apply { text = n; textSize = 14f; setTextColor(Color.WHITE); setPadding(16, 0, 0, 0) }); il2.setOnClickListener { sc = c; cp.setBackgroundColor(Color.parseColor(sc)); currentPopup?.dismiss() }; colorList.addView(il2) }
            scrollView.addView(colorList)
            currentPopup = PopupWindow(scrollView, 250.dpToPx(), 400.dpToPx(), true); showPopup(currentPopup!!, it)
        }
    })
    rl.addView(TextView(context).apply { text = "圆角大小"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 15, 0, 5) })
    val csb = SeekBar(context).apply { max = 20; progress = if (t.tileCornerRadius != -1) t.tileCornerRadius else 0 }; rl.addView(csb)
    val cv = TextView(context).apply { text = "${csb.progress} dp"; setPadding(0, 5, 0, 5) }; rl.addView(cv)
    csb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(sb: SeekBar?, p: Int, fu: Boolean) { cv.text = "$p dp" }; override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {} })
    rl.addView(android.widget.Button(context).apply {
        text = "保存"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(li.windowToken, 0); if (li.text.isNotEmpty()) t.tileLabel = li.text.toString(); t.tileSize = ss; t.tileColor = sc; t.tileCornerRadius = csb.progress; coroutineScope.launch(Dispatchers.IO) { db?.getTilesDao()?.updateTile(t); withContext(Dispatchers.Main) { Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show(); refreshTilesIfNeeded(); hideEditPanel() } } }
    })
    rl.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dpToPx()) })
    sv.addView(rl); return sv
}
    // ===== 应用列表 =====
    private fun loadAppsContent() {
        if (appsRecyclerView != null) { appAdapter?.updateData(sortByUsage(filteredApps())); return }
        val ct = contentContainer ?: return; ct.removeAllViews()
        val sl = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val sb = EditText(context).apply {
            hint = "搜索应用..."; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx()); setBackgroundColor(Color.parseColor("#FF222222"))
            isFocusable = true; isFocusableInTouchMode = true; isCursorVisible = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 4.dpToPx()) }
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_UP) { requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }; false }
        }
        sl.addView(sb)
        appsRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            isNestedScrollingEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; setHasFixedSize(true)
        }
        sl.addView(appsRecyclerView); ct.addView(sl)
        if (sortedCachedApps.isEmpty() && cachedApps.isNotEmpty()) sortedCachedApps = sortApps(cachedApps)
        val appList = ArrayList<App>(sortByUsage(filteredApps()))
        appList.add(0, App("❄ 一键冻结", "freeze_button", 0))
        appList.add(1, App("⚙ 设置", "settings_button", 0))
        appAdapter = AppListAdapter(appList)
        appsRecyclerView?.layoutManager = SpannedGridLayoutManager(RecyclerView.VERTICAL, 12, 4).apply {
            spanSizeLookup = SpannedGridLayoutManager.SpanSizeLookup { SpanSize(1, 1) }
        }
        appsRecyclerView?.adapter = appAdapter
        appsRecyclerView?.alpha = 0f; appsRecyclerView?.animate()?.alpha(1f)?.setDuration(250)?.start()
        sb.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                val filtered = appList.filter { it.mPackage != "freeze_button" && it.mPackage != "settings_button" }
                    .let { list -> if (list.size > 1) list else appList }
                    .filter { it.mName.lowercase().contains(s?.toString()?.lowercase() ?: "") }
                appAdapter?.updateData(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    inner class AppListAdapter(private var apps: List<App>) : RecyclerView.Adapter<AppListAdapter.AVH>() {
    inner class AVH(val c: FrameLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(c)
    fun updateData(a: List<App>) { if (a != apps) { apps = a; notifyDataSetChanged() } }
    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(p: android.view.ViewGroup, vt: Int): AVH {
        val outer = FrameLayout(p.context).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }
        val iv = ImageView(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(36.dpToPx(), 36.dpToPx()).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tv = TextView(p.context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 2.dpToPx()
            }
            textSize = 10f; setTextColor(Color.WHITE); maxLines = 1
            setShadowLayer(2f, 0f, 1f, Color.BLACK); gravity = Gravity.CENTER
        }
        outer.addView(iv); outer.addView(tv)
        return AVH(outer, iv, tv)
    }

    override fun onBindViewHolder(h: AVH, pos: Int) {
        val a = apps[pos]
        h.label.text = a.mName

        if (a.mPackage == "freeze_button") {
            val corner = 6.dpToPx().toFloat()
            val base = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#FF222222")); setCornerRadius(corner) }
            val light = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColors(intArrayOf(Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(40, 0, 0, 0))); gradientType = GradientDrawable.LINEAR_GRADIENT; orientation = GradientDrawable.Orientation.TL_BR; setCornerRadius(corner) }
            GlassTileHelper.applyGlassShell(h.c, corner)
            h.icon.setImageResource(android.R.drawable.ic_lock_lock)
            h.label.text = "冻结"
            h.c.setOnLongClickListener { true }
            h.c.setOnClickListener { performOneKeyFreeze() }
            return
        }
        if (a.mPackage == "settings_button") {
            val corner = 6.dpToPx().toFloat()
            val base = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#FF222222")); setCornerRadius(corner) }
            val light = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColors(intArrayOf(Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(40, 0, 0, 0))); gradientType = GradientDrawable.LINEAR_GRADIENT; orientation = GradientDrawable.Orientation.TL_BR; setCornerRadius(corner) }
            GlassTileHelper.applyGlassShell(h.c, corner)
            h.icon.setImageResource(android.R.drawable.ic_menu_manage)
            h.label.text = "设置"
            h.c.setOnLongClickListener { true }
            h.c.setOnClickListener { showPanelBgDialog() }
            return
        }

        val cornerRadiusPx = 4.dpToPx().toFloat()
        val base = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.TRANSPARENT); setCornerRadius(cornerRadiusPx) }
        val shadow = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColors(intArrayOf(0x00000000.toInt(), 0x40000000.toInt())); gradientType = GradientDrawable.LINEAR_GRADIENT; orientation = GradientDrawable.Orientation.TOP_BOTTOM; setCornerRadius(cornerRadiusPx) }
        val highlight = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColors(intArrayOf(0x28FFFFFF.toInt(), 0x00000000.toInt())); gradientType = GradientDrawable.LINEAR_GRADIENT; orientation = GradientDrawable.Orientation.TOP_BOTTOM; setCornerRadius(cornerRadiusPx) }
        GlassTileHelper.applyGlassShell(h.c, cornerRadiusPx)

        val isFrozenApp = a.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
        h.c.alpha = if (isFrozenApp) 0.5f else 1f
        if (a.mPackage != null) {
            val bmp = iconLoader.getIconForPackage(context, a.mPackage)
            if (bmp != null) h.icon.setImageBitmap(Bitmap.createScaledBitmap(bmp, 36.dpToPx(), 36.dpToPx(), true))
            else h.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }
        h.c.setOnClickListener {
            if (isFrozenApp && a.mPackage != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    val sh = ShizukuHelper.getInstance()
                    if (sh.unfreezeApp(a.mPackage!!)) {
                        FreezeManager.setFrozen(context, a.mPackage!!, false)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已解冻，启动中...", Toast.LENGTH_SHORT).show()
                            refreshTilesIfNeeded()
                            try { AppManager.launchApp(a.mPackage, context) } catch (e: Exception) {}
                            hidePanel()
                        }
                    } else { withContext(Dispatchers.Main) { Toast.makeText(context, "解冻失败", Toast.LENGTH_SHORT).show() } }
                }
            } else {
                h.c.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.7f).setDuration(100).withEndAction {
                    a.mPackage?.let { coroutineScope.launch { try { AppManager.launchApp(it, context) } catch (ex: Exception) {}; hidePanel() } }
                }.start()
            }
        }
        h.c.setOnLongClickListener {
            val inFreezeList = a.mPackage?.let { FreezeManager.getList(context).contains(it) } ?: false
            showAppPopup(h.c, a, inFreezeList)
            true
        }
    }
}

    private fun showAppPopup(an: View, a: App, inFreezeList: Boolean = false) {
        currentPopup?.dismiss()
        val pv = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(8, 8, 8, 8) }
        pv.addView(tv("固定到开始屏幕") { currentPopup?.dismiss(); pinApp(a) })
        if (inFreezeList) {
            val isFrozen = a.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
            pv.addView(tv(if (isFrozen) "解冻应用" else "冻结应用") {
                currentPopup?.dismiss()
                a.mPackage?.let { pkg ->
                    coroutineScope.launch(Dispatchers.IO) {
                        val sh = ShizukuHelper.getInstance()
                        if (isFrozen) {
                            if (sh.unfreezeApp(pkg)) { FreezeManager.setFrozen(context, pkg, false); withContext(Dispatchers.Main) { refreshTilesIfNeeded(); Toast.makeText(context, "已解冻", Toast.LENGTH_SHORT).show() } }
                            else withContext(Dispatchers.Main) { Toast.makeText(context, "解冻失败", Toast.LENGTH_SHORT).show() }
                        } else {
                            if (sh.freezeApp(pkg)) { FreezeManager.setFrozen(context, pkg, true); withContext(Dispatchers.Main) { refreshTilesIfNeeded(); Toast.makeText(context, "已冻结", Toast.LENGTH_SHORT).show() } }
                            else withContext(Dispatchers.Main) { Toast.makeText(context, "冻结失败", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            })
        } else {
            pv.addView(tv("添加到冻结列表") {
                currentPopup?.dismiss()
                a.mPackage?.let { FreezeManager.addToList(context, it) }
                refreshAppsIfNeeded()
            })
        }
        val isHidden = a.mPackage?.let { FreezeManager.getHiddenList(context).contains(it) } ?: false
        pv.addView(tv(if (isHidden) "取消隐藏" else "隐藏应用") {
            currentPopup?.dismiss()
            a.mPackage?.let { FreezeManager.toggleHidden(context, it) }
            refreshAppsIfNeeded()
        })
        pv.addView(tv("应用信息") { currentPopup?.dismiss(); context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${a.mPackage}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        currentPopup = PopupWindow(pv, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); showAtLocation(an, Gravity.CENTER, 0, 0) }
    }

    private fun pinApp(a: App) {
        coroutineScope.launch(Dispatchers.IO) {
            val dao = db?.getTilesDao(); val tiles = dao?.getTilesData()?.toMutableList() ?: return@launch
            val slot = tiles.indexOfFirst { it.tileType == -1 }
            if (slot >= 0) {
                tiles[slot] = TileEntity(tiles[slot].id, slot, null, -1, 0, 0, a.mName, a.mPackage)
                dao.updateAllTiles(tiles)
                val remainingSlots = tiles.count { it.tileType == -1 }
                if (remainingSlots < 2) {
                    val newPos = tiles.size
                    dao.insertTile(TileEntity(tilePosition = newPos, tileColor = null, tileCornerRadius = -1, tileType = -1, tileSize = 0, tileLabel = null, tilePackage = null))
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "已固定: ${a.mName}", Toast.LENGTH_SHORT).show(); refreshTilesIfNeeded(); refreshAppsIfNeeded() }
            } else withContext(Dispatchers.Main) { Toast.makeText(context, "没有空余位置", Toast.LENGTH_SHORT).show() }
        }
    }

    // ===== 切换与动画 =====
    private fun switchToLevel(lv: PanelLevel) {
        if (lv == PanelLevel.APPS && currentLevel == PanelLevel.TILES) tilesRecyclerView?.animate()?.scaleX(0.9f)?.scaleY(0.9f)?.alpha(0.5f)?.setDuration(200)?.start()
        else if (lv == PanelLevel.TILES && currentLevel == PanelLevel.APPS) tilesRecyclerView?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()
        when (lv) {
            PanelLevel.TILES -> { if (tilesRecyclerView == null) loadTilesContent() else if (refreshTilesPending) { tileAdapter?.updateData(cachedTiles); refreshTilesPending = false } }
            PanelLevel.APPS -> { if (appsRecyclerView == null) loadAppsContent() }
            PanelLevel.HIDDEN -> {}
        }
        currentLevel = lv
    }

    private fun anim(tx: Int, tl: PanelLevel) {
        currentAnimator?.cancel(); switchToLevel(tl)
        val sx = panelParams?.x ?: hiddenX; val sh = tl == PanelLevel.HIDDEN
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = DecelerateInterpolator()
            addUpdateListener { a -> panelParams?.x = (sx + (tx - sx) * a.animatedFraction).toInt(); panelView?.let { try { windowManager.updateViewLayout(it, panelParams) } catch (ex: Exception) {} } }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (sh && isPanelVisible) { destroyPanel(); isPanelVisible = false; currentLevel = PanelLevel.HIDDEN; onPanelStateChangeListener?.invoke(false, PanelLevel.HIDDEN) }
                    else onPanelStateChangeListener?.invoke(true, tl)
                    currentAnimator = null
                }
            })
            start()
        }
    }

    fun showPanel() { if (!isPanelVisible) { createPanel(); windowManager.addView(panelView, panelParams); isPanelVisible = true; anim(tilesX, PanelLevel.TILES) } }
    fun hidePanel() { if (isPanelVisible) anim(hiddenX, PanelLevel.HIDDEN) }
    fun hidePanelImmediately() { if (isPanelVisible) { try { windowManager.removeView(panelView) } catch (e: Exception) {}; isPanelVisible = false } }
    fun isPanelExpanded() = currentLevel != PanelLevel.HIDDEN

    fun destroyGestureStrip() { gestureView?.let { try { windowManager.removeView(it) } catch (ex: Exception) {} }; gestureView = null; gestureParams = null }
    private fun destroyPanel() { itemTouchHelper?.attachToRecyclerView(null); itemTouchHelper = null; panelView?.let { it.setOnTouchListener(null); (it as? ViewGroup)?.removeAllViews(); try { windowManager.removeView(it) } catch (ex: Exception) {} }; panelView = null; panelParams = null; contentContainer = null; tilesRecyclerView = null; appsRecyclerView = null; tileAdapter = null; appAdapter = null }
    fun destroy() { currentAnimator?.cancel(); currentAnimator = null; hideEditPanel(); destroyPanel(); destroyGestureStrip(); isPanelVisible = false; currentLevel = PanelLevel.HIDDEN; db?.close(); db = null; coroutineScope.cancel() }
    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()
}
