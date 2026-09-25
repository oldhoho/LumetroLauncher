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
    private val SWIPE_THRESHOLD = 80f  // 增大阈值

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    init {
        orientation = LinearLayout.VERTICAL
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = ev.y
                touchStartX = ev.x
                isSwiping = false
                hasTriggeredSwipe = false
                // 不消费 DOWN，让子View处理（长按/点击）
                super.dispatchTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - touchStartY
                val dx = ev.x - touchStartX

                if (abs(dy) > SWIPE_THRESHOLD && abs(dy) > abs(dx) * 0.5f) {
                    isSwiping = true
                }
                // 无论是否滑动，都传给子View处理
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (isSwiping && !hasTriggeredSwipe) {
                    val totalDy = ev.y - touchStartY
                    if (totalDy > SWIPE_THRESHOLD) {
                        onSwipeDown?.invoke()
                        hasTriggeredSwipe = true
                    } else if (totalDy < -SWIPE_THRESHOLD) {
                        onSwipeUp?.invoke()
                        hasTriggeredSwipe = true
                    }
                }
                isSwiping = false
                super.dispatchTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isSwiping = false
                hasTriggeredSwipe = false
                super.dispatchTouchEvent(ev)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}