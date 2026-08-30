package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.model.App
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class GridAppListPanel(
    private val context: Context,
    private val iconLoader: IconLoader,
    private val coroutineScope: CoroutineScope,
    private val iconCache: MutableMap<String, Bitmap>,
    private val onAppClick: (App) -> Unit,
    private val onAppLongClick: (App, View) -> Unit
) {
    
    companion object {
        private const val CHAMBER_COUNT = 6
        private const val CHAMBER_RADIUS_DP = 120
        private const val CHAMBER_ICON_SIZE_DP = 45
        private const val CENTER_ICON_SIZE_DP = 80
        
        var SHOW_GRID_BORDERS = false
    }
    
    private val density = context.resources.displayMetrics.density
    
    private val chambers = mutableListOf<Chamber>()
    private var centerIconViewRef: ImageView? = null
    private var centerLabelViewRef: TextView? = null
    
    private var appsByIndex = emptyList<App>()
    private var currentIndex = 0
    
    private var rootContainer: FrameLayout? = null
    private var centerContainer: LinearLayout? = null
    private var touchLayer: View? = null
    
    private var lastTouchY = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var flingVelocity = 0f
    private var isFlinging = false
    
    private val velocityTracker = VelocityTracker.obtain()
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    
    data class Chamber(
        val container: FrameLayout,
        val icon: ImageView,
        val label: TextView,
        val chamberIndex: Int
    )
    
    private fun Int.dpToPx(): Int = (this * density).toInt()
    
    fun createView(): View {
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
        }
        
        rootContainer = container
        
        // 触摸层最先添加
        touchLayer = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        container.addView(touchLayer)
        
        // 等待容器有尺寸后创建
        container.post {
            createChambers(container)
            createCenterArea(container)
            updateChamberContent()
        }
        
        return container
    }
    
    private fun handleTouch(event: MotionEvent): Boolean {
        velocityTracker.addMovement(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                lastTouchY = event.y
                isDragging = false
                isFlinging = false
                flingVelocity = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                val totalDy = event.y - touchStartY
                
                if (abs(totalDy) > touchSlop) {
                    isDragging = true
                }
                
                lastTouchY = event.y
                
                // 滑动切换图标
                val indexDelta = (dy / 50f).roundToInt()
                if (indexDelta != 0) {
                    currentIndex -= indexDelta  // 向上滑显示下一个
                    updateChamberContent()
                }
                
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    handleClick(event.y)
                } else {
                    velocityTracker.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
                    flingVelocity = velocityTracker.yVelocity
                    
                    if (abs(flingVelocity) > minimumFlingVelocity) {
                        isFlinging = true
                        startFlingSwitch()
                    }
                }
                isDragging = false
                velocityTracker.clear()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isFlinging = false
                velocityTracker.clear()
                return true
            }
        }
        return false
    }
    
    private fun startFlingSwitch() {
        touchLayer?.post(object : Runnable {
            override fun run() {
                if (!isFlinging) return
                
                flingVelocity *= 0.92f
                if (abs(flingVelocity) < 5f) {
                    isFlinging = false
                    return
                }
                
                val indexDelta = (flingVelocity * 0.016f / 50f).roundToInt()
                if (indexDelta != 0) {
                    currentIndex += indexDelta
                    updateChamberContent()
                }
                
                touchLayer?.postDelayed(this, 16)
            }
        })
    }
    
    private fun handleClick(touchY: Float) {
        val container = rootContainer ?: return
        
        // 检查中心区域
        val centerView = centerContainer
        if (centerView != null) {
            val centerTop = centerView.y
            val centerBottom = centerView.y + centerView.height
            
            if (touchY >= centerTop && touchY <= centerBottom) {
                // 点击中心：启动当前应用
                val actualIndex = getCircularIndex(currentIndex)
                if (appsByIndex.isNotEmpty()) {
                    onAppClick(appsByIndex[actualIndex])
                }
                return
            }
        }
        
        // 检查子弹槽
        for (chamber in chambers) {
            val chamberView = chamber.container
            val top = chamberView.y
            val bottom = chamberView.y + chamberView.height
            
            if (touchY >= top && touchY <= bottom) {
                // 点击子弹槽：切换当前命中
                currentIndex = getCircularIndex(currentIndex + chamber.chamberIndex)
                updateChamberContent()
                return
            }
        }
    }
    
    private fun createChambers(container: FrameLayout) {
        // 清除旧子弹槽
        for (chamber in chambers) {
            container.removeView(chamber.container)
        }
        chambers.clear()
        
        val containerWidth = container.width
        val containerHeight = container.height
        if (containerWidth == 0 || containerHeight == 0) return
        
        val touchLayerIndex = container.indexOfChild(touchLayer)
        val centerX = containerWidth / 2f
        val centerY = containerHeight / 2f - 50.dpToPx()
        val radius = CHAMBER_RADIUS_DP.dpToPx().toFloat()
        
        for (i in 0 until CHAMBER_COUNT) {
            val angle = (i.toFloat() / CHAMBER_COUNT) * 2f * Math.PI - Math.PI / 2
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()
            
            val iconSize = CHAMBER_ICON_SIZE_DP.dpToPx()
            val chamber = createChamber(iconSize, i)
            
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (x - iconSize / 2f).toInt()
                topMargin = (y - iconSize / 2f).toInt()
            }
            
            // 添加到触摸层下面
            container.addView(chamber.container, touchLayerIndex, params)
            chambers.add(chamber)
        }
    }
    
    private fun createChamber(iconSizePx: Int, chamberIndex: Int): Chamber {
        val chamberView = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
        }
        
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        
        val label = TextView(context).apply {
            textSize = 9f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        
        contentLayout.addView(icon)
        contentLayout.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 2.dpToPx()
        })
        
        chamberView.addView(contentLayout)
        return Chamber(chamberView, icon, label, chamberIndex)
    }
    
    private fun createCenterArea(container: FrameLayout) {
        // 清除旧中心
        centerContainer?.let { container.removeView(it) }
        
        val centerSize = CENTER_ICON_SIZE_DP.dpToPx()
        
        val centerIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(centerSize, centerSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        
        val centerLabel = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        
        val centerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        
        centerLayout.addView(centerIcon)
        centerLayout.addView(centerLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 4.dpToPx()
        })
        
        centerContainer = centerLayout
        centerIconViewRef = centerIcon
        centerLabelViewRef = centerLabel
        
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        val touchLayerIndex = container.indexOfChild(touchLayer)
        container.addView(centerLayout, touchLayerIndex, params)
    }
    
    private fun updateChamberContent() {
        if (appsByIndex.isEmpty() || chambers.isEmpty()) return
        
        // 更新子弹槽
        for (chamber in chambers) {
            val appIndex = getCircularIndex(currentIndex + chamber.chamberIndex)
            val app = appsByIndex[appIndex]
            
            chamber.label.text = app.mName
            loadIcon(chamber.icon, app)
            
            val isFrozen = app.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
            chamber.icon.alpha = if (isFrozen) 0.4f else 1.0f
            chamber.label.alpha = if (isFrozen) 0.4f else 1.0f
        }
        
        // 更新中心
        val centerApp = appsByIndex[getCircularIndex(currentIndex)]
        centerIconViewRef?.let { loadIcon(it, centerApp) }
        centerLabelViewRef?.text = centerApp.mName
        
        val isCenterFrozen = centerApp.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
        centerIconViewRef?.alpha = if (isCenterFrozen) 0.4f else 1.0f
        centerLabelViewRef?.alpha = if (isCenterFrozen) 0.4f else 1.0f
    }
    
    private fun getCircularIndex(index: Int): Int {
        if (appsByIndex.isEmpty()) return 0
        return ((index % appsByIndex.size) + appsByIndex.size) % appsByIndex.size
    }
    
    private fun loadIcon(imageView: ImageView, app: App) {
        val pkg = app.mPackage ?: return
        
        val cached = iconCache[pkg]
        if (cached != null) {
            imageView.setImageBitmap(cached)
        } else {
            imageView.setImageResource(android.R.drawable.sym_def_app_icon)
            coroutineScope.launch(Dispatchers.IO) {
                val bmp = iconLoader.getIconForPackage(context, pkg)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        val maxSize = CENTER_ICON_SIZE_DP.dpToPx()
                        val scaled = Bitmap.createScaledBitmap(bmp, maxSize, maxSize, true)
                        iconCache[pkg] = scaled
                        imageView.setImageBitmap(scaled)
                    }
                }
            }
        }
    }
    
    fun setApps(apps: List<App>) {
        appsByIndex = apps
        currentIndex = 0
        updateChamberContent()
    }
    
    fun scrollToPosition(position: Int) {
        currentIndex = position
        updateChamberContent()
    }
}