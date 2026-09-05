package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.sqrt

class LockOverlay(private val context: Context) {
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isLocked = false
    
    private var totalDistance = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val UNLOCK_DISTANCE_DP = 500f
    
    var onUnlocked: (() -> Unit)? = null
    
    fun lock() {
        if (isLocked) return
        
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        
        val view = View(context).apply {
            setBackgroundColor(Color.argb(128, 255, 0, 0))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            
            setOnTouchListener { _, event ->
                handleTouchEvent(event)
                true
            }
        }
        
        val extraHeightBottom = (100 * density).toInt()
        val screenHeight = context.resources.displayMetrics.heightPixels
        val totalHeight = screenHeight + extraHeightBottom
        
        // ========== 使用 TYPE_SYSTEM_ALERT ==========
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            totalHeight,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
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
            wm.addView(view, params)
            overlayView = view
            windowManager = wm
            isLocked = true
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "无法创建锁定图层: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                totalDistance = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                totalDistance += sqrt(dx * dx + dy * dy)
                lastX = event.x
                lastY = event.y
                
                val unlockThreshold = UNLOCK_DISTANCE_DP * context.resources.displayMetrics.density
                if (totalDistance > unlockThreshold) {
                    unlock()
                }
            }
            MotionEvent.ACTION_UP -> {
                val unlockThreshold = UNLOCK_DISTANCE_DP * context.resources.displayMetrics.density
                if (totalDistance > unlockThreshold) {
                    unlock()
                }
            }
        }
    }
    
    fun unlock() {
        if (!isLocked) return
        
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (e: Exception) {
        }
        
        overlayView = null
        windowManager = null
        isLocked = false
        
        onUnlocked?.invoke()
    }
    
    fun isLocked(): Boolean = isLocked
}