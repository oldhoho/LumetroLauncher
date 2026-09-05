package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

class GestureKeyboardContainer(context: Context) : LinearLayout(context) {
    private var touchStartY = 0f
    private var touchStartX = 0f
    private var isSwiping = false
    private var hasTriggeredSwipe = false
    private val SWIPE_THRESHOLD = 30f
    private val MAX_CLICK_MOVEMENT = 15f

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    init {
        orientation = LinearLayout.VERTICAL
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = ev.y
                touchStartX = ev.x
                isSwiping = false
                hasTriggeredSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - touchStartY
                val dx = ev.x - touchStartX
                
                // ========== 增大角度容忍：只要垂直分量大于水平分量的一半即可 ==========
                // 之前：abs(dy) > abs(dx) * 1.5f（约56度以上）
                // 现在：abs(dy) > abs(dx) * 0.5f（约26度以上都能触发）
                if (abs(dy) > SWIPE_THRESHOLD && abs(dy) > abs(dx) * 0.5f) {
                    isSwiping = true
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwiping) {
                    isSwiping = false
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isSwiping) {
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwiping && !hasTriggeredSwipe) {
                    val totalDy = event.y - touchStartY
                    if (totalDy > SWIPE_THRESHOLD) {
                        onSwipeDown?.invoke()
                        hasTriggeredSwipe = true
                    } else if (totalDy < -SWIPE_THRESHOLD) {
                        onSwipeUp?.invoke()
                        hasTriggeredSwipe = true
                    }
                    isSwiping = false
                    return true
                }
                isSwiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isSwiping = false
                hasTriggeredSwipe = false
            }
        }
        return super.onTouchEvent(event)
    }
}