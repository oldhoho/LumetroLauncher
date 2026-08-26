package ru.queuejw.lumetro.components.freeform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration

class WorkbenchGestureHelper(private val context: Context) {

    companion object {
        private const val LONG_PRESS_TIMEOUT = 500L
        private const val TAP_TIMEOUT = 300L
        private const val SWIPE_MIN_DISTANCE = 60f
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isLongPressTriggered = false
    private var isSwiping = false
    private var longPressRunnable: Runnable? = null

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private var lastTapTime = 0L
    private var tapCount = 0

    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = System.currentTimeMillis()
                isLongPressTriggered = false
                isSwiping = false

                longPressRunnable = Runnable {
                    if (!isSwiping) {
                        isLongPressTriggered = true
                        onLongPress?.invoke()
                    }
                }
                handler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)

                if (absDx > touchSlop || absDy > touchSlop) {
                    handler.removeCallbacks(longPressRunnable!!)
                    isSwiping = true

                    if (absDx > absDy && absDx > SWIPE_MIN_DISTANCE) {
                        if (dx > 0) {
                            onSwipeRight?.invoke()
                        } else {
                            onSwipeLeft?.invoke()
                        }
                        return true
                    } else if (absDy > absDx && absDy > SWIPE_MIN_DISTANCE) {
                        if (dy > 0) {
                            onSwipeDown?.invoke()
                        } else {
                            onSwipeUp?.invoke()
                        }
                        return true
                    }
                }
                return false
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable!!)
                longPressRunnable = null

                if (isSwiping || isLongPressTriggered) {
                    resetState()
                    return true
                }

                val elapsed = System.currentTimeMillis() - downTime
                if (elapsed < TAP_TIMEOUT) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 400) {
                        tapCount++
                        if (tapCount >= 2) {
                            onDoubleTap?.invoke()
                            tapCount = 0
                        }
                    } else {
                        tapCount = 1
                    }
                    lastTapTime = now
                    onTap?.invoke()
                    return true
                }

                resetState()
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable!!)
                longPressRunnable = null
                resetState()
                return false
            }
        }
        return false
    }

    private fun resetState() {
        isLongPressTriggered = false
        isSwiping = false
        longPressRunnable = null
    }

    fun cancel() {
        handler.removeCallbacks(longPressRunnable!!)
        longPressRunnable = null
        resetState()
    }
}