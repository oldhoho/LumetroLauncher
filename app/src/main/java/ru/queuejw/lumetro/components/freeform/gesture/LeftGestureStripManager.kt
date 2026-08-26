package ru.queuejw.lumetro.components.freeform.gesture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LeftGestureStripManager private constructor(
    private val context: Context,
    private val accessibilityService: AccessibilityService
) {

    companion object {
        private const val TAG = "LeftGestureStripManager"
        @Volatile
        private var instance: LeftGestureStripManager? = null

        fun getInstance(context: Context, service: AccessibilityService): LeftGestureStripManager {
            return instance ?: synchronized(this) {
                instance ?: LeftGestureStripManager(context.applicationContext, service).also {
                    instance = it
                }
            }
        }

        fun destroyInstance() {
            synchronized(this) {
                instance?.destroyAll()
                instance = null
            }
        }

        private fun writeLog(message: String) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "gesture_strip_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                file.appendText("[$timestamp] $message\n")
            } catch (e: Exception) {}
        }
    }

    private val windowManager = accessibilityService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val gestureViews = mutableSetOf<WeakReference<View>>()

    private var isShowing = false

    var stripWidth: Int = 6.dpToPx()
    var stripHeight: Int = 0
    var stripOffset: Int = 0
    var stripAlpha: Float = 0.3f

    // ========== 手势状态 ==========
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var gestureCompleted = false
    private var isTimeout = false

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val TIMEOUT_MS = 500L
    // ================================

    var onSwipeRight: (() -> Unit)? = null

    private fun getWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
    }

    fun show() {
        writeLog("=== show() called ===")
        writeLog("stripWidth: $stripWidth, stripHeight: $stripHeight, stripOffset: $stripOffset, stripAlpha: $stripAlpha")

        destroyAll()

        val h = if (stripHeight > 0) stripHeight else WindowManager.LayoutParams.MATCH_PARENT
        writeLog("Calculated height: $h")

        val params = WindowManager.LayoutParams(
            stripWidth,
            h,
            getWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
            x = 0
            y = stripOffset
        }

        val view = View(context).apply {
            setBackgroundColor(((stripAlpha * 255).toInt() shl 24) or 0xFFFFFF)
            setOnTouchListener { _, event ->
                handleTouch(event)
            }
            isFocusable = false
            isClickable = false
            isLongClickable = false
            setWillNotDraw(true)
        }

        try {
            windowManager.addView(view, params)
            gestureViews.add(WeakReference(view))
            isShowing = true
            writeLog("✅ addView SUCCESS, total views: ${gestureViews.size}")
        } catch (e: Exception) {
            writeLog("❌ addView FAILED: ${e.message}")
            e.printStackTrace()
            isShowing = false
        }
        writeLog("=== show() finished ===")
    }

    fun hide() {
        if (!isShowing) return
        destroyAll()
    }

    fun toggle() {
        if (isShowing) {
            hide()
        } else {
            show()
        }
    }

    fun isShowing(): Boolean = isShowing

    fun destroyAll() {
        writeLog("destroyAll() called, views: ${gestureViews.size}")
        handler.removeCallbacksAndMessages(null)
        val iterator = gestureViews.iterator()
        while (iterator.hasNext()) {
            val viewRef = iterator.next()
            val view = viewRef.get()
            if (view != null) {
                try {
                    windowManager.removeView(view)
                    writeLog("removeView SUCCESS")
                } catch (e: Exception) {
                    writeLog("removeView FAILED: ${e.message}")
                }
            }
            iterator.remove()
        }
        isShowing = false
        writeLog("destroyAll() finished, remaining: ${gestureViews.size}")
    }

    fun destroy() {
        destroyAll()
    }

    fun updateConfig(width: Int, height: Int, offset: Int, alpha: Float) {
        writeLog("=== updateConfig() called ===")
        writeLog("width=$width, height=$height, offset=$offset, alpha=$alpha")

        val density = context.resources.displayMetrics.density
        stripWidth = (width * density).toInt()
        stripHeight = if (height > 0) (height * density).toInt() else 0
        stripOffset = (offset * density).toInt()
        stripAlpha = alpha

        writeLog("Converted: stripWidth=$stripWidth, stripHeight=$stripHeight, stripOffset=$stripOffset, stripAlpha=$stripAlpha")

        destroyAll()
        show()
        writeLog("=== updateConfig() finished ===")
    }

    // ========== 触摸处理（正常版本，仅右滑触发返回） ==========
    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                downTime = System.currentTimeMillis()
                isDragging = false
                gestureCompleted = false
                isTimeout = false

                timeoutRunnable = Runnable {
                    if (!gestureCompleted) {
                        isTimeout = true
                        gestureCompleted = true
                        writeLog("TIMEOUT: gesture cancelled")
                    }
                }
                handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureCompleted || isTimeout) return false

                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)

                if (!isDragging && (absDx > touchSlop || absDy > touchSlop)) {
                    isDragging = true
                }

                return isDragging
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable = null

                writeLog("ACTION_UP: isDragging=$isDragging, gestureCompleted=$gestureCompleted, isTimeout=$isTimeout")

                if (gestureCompleted || isTimeout) {
                    resetState()
                    return true
                }

                if (isDragging) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val absDx = Math.abs(dx)
                    val absDy = Math.abs(dy)

                    if (absDx > absDy && absDx > 40.dpToPx()) {
                        gestureCompleted = true
                        if (dx > 0) {
                            writeLog("SWIPE RIGHT detected")
                            try { onSwipeRight?.invoke() } catch (ex: Exception) {
                                writeLog("onSwipeRight callback error: ${ex.message}")
                            }
                        }
                        resetState()
                        return true
                    }
                }

                // ========== 点击不做任何操作 ==========
                resetState()
                return true
            }
        }
        return false
    }

    private fun resetState() {
        isDragging = false
        gestureCompleted = false
        isTimeout = false
        timeoutRunnable = null
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}