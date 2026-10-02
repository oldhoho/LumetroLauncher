package ru.queuejw.lumetro.components.freeform.util

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 编辑模式的全屏触摸层：只负责拖动框选。
 * 生效模式不需要它。
 */
class DimEditView(context: Context) : View(context) {

    var holeRect: RectF = RectF()
        set(value) {
            field = value
        }

    var onHoleChanged: (() -> Unit)? = null

    private var dragMode = NONE
    private var startX = 0f
    private var startY = 0f
    private var startRect: RectF = RectF()

    companion object {
        const val NONE = 0
        const val MOVE = 1
        const val RESIZE_TL = 2
        const val RESIZE_TR = 3
        const val RESIZE_BL = 4
        const val RESIZE_BR = 5
        const val NEW = 6
        const val RESIZE_LEFT = 7
        const val RESIZE_RIGHT = 8
        const val RESIZE_TOP = 9
        const val RESIZE_BOTTOM = 10

        private const val HANDLE_HIT_SIZE = 80f
        private const val EDGE_HIT_SIZE = 60f
        private const val MIN_HOLE_SIZE = 150f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                startRect.set(holeRect)
                dragMode = detectDragMode(x, y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - startX
                val dy = y - startY

                when (dragMode) {
                    MOVE -> {
                        holeRect.set(startRect)
                        holeRect.offset(dx, dy)
                    }
                    RESIZE_TL -> {
                        holeRect.set(startRect)
                        holeRect.left = (startRect.left + dx).coerceAtMost(holeRect.right - MIN_HOLE_SIZE)
                        holeRect.top = (startRect.top + dy).coerceAtMost(holeRect.bottom - MIN_HOLE_SIZE)
                    }
                    RESIZE_TR -> {
                        holeRect.set(startRect)
                        holeRect.right = (startRect.right + dx).coerceAtLeast(holeRect.left + MIN_HOLE_SIZE)
                        holeRect.top = (startRect.top + dy).coerceAtMost(holeRect.bottom - MIN_HOLE_SIZE)
                    }
                    RESIZE_BL -> {
                        holeRect.set(startRect)
                        holeRect.left = (startRect.left + dx).coerceAtMost(holeRect.right - MIN_HOLE_SIZE)
                        holeRect.bottom = (startRect.bottom + dy).coerceAtLeast(holeRect.top + MIN_HOLE_SIZE)
                    }
                    RESIZE_BR -> {
                        holeRect.set(startRect)
                        holeRect.right = (startRect.right + dx).coerceAtLeast(holeRect.left + MIN_HOLE_SIZE)
                        holeRect.bottom = (startRect.bottom + dy).coerceAtLeast(holeRect.top + MIN_HOLE_SIZE)
                    }
                    RESIZE_LEFT -> {
                        holeRect.set(startRect)
                        holeRect.left = (startRect.left + dx).coerceAtMost(holeRect.right - MIN_HOLE_SIZE)
                    }
                    RESIZE_RIGHT -> {
                        holeRect.set(startRect)
                        holeRect.right = (startRect.right + dx).coerceAtLeast(holeRect.left + MIN_HOLE_SIZE)
                    }
                    RESIZE_TOP -> {
                        holeRect.set(startRect)
                        holeRect.top = (startRect.top + dy).coerceAtMost(holeRect.bottom - MIN_HOLE_SIZE)
                    }
                    RESIZE_BOTTOM -> {
                        holeRect.set(startRect)
                        holeRect.bottom = (startRect.bottom + dy).coerceAtLeast(holeRect.top + MIN_HOLE_SIZE)
                    }
                    NEW -> {
                        holeRect.set(
                            minOf(startX, x),
                            minOf(startY, y),
                            maxOf(startX, x),
                            maxOf(startY, y)
                        )
                    }
                }

                clampRect()
                clampMinSize()
                onHoleChanged?.invoke()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == NEW && (holeRect.width() < MIN_HOLE_SIZE || holeRect.height() < MIN_HOLE_SIZE)) {
                    holeRect.set(startRect)
                }
                dragMode = NONE
                clampRect()
                clampMinSize()
                onHoleChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectDragMode(x: Float, y: Float): Int {
        if (holeRect.isEmpty) return NEW

        val hs = HANDLE_HIT_SIZE
        val es = EDGE_HIT_SIZE

        val nearLeft = kotlin.math.abs(x - holeRect.left) < hs
        val nearRight = kotlin.math.abs(x - holeRect.right) < hs
        val nearTop = kotlin.math.abs(y - holeRect.top) < hs
        val nearBottom = kotlin.math.abs(y - holeRect.bottom) < hs

        if (nearLeft && nearTop) return RESIZE_TL
        if (nearRight && nearTop) return RESIZE_TR
        if (nearLeft && nearBottom) return RESIZE_BL
        if (nearRight && nearBottom) return RESIZE_BR

        val isInHorizontalRange = x in holeRect.left..holeRect.right
        val isInVerticalRange = y in holeRect.top..holeRect.bottom

        if (isInVerticalRange && kotlin.math.abs(x - holeRect.left) < es) return RESIZE_LEFT
        if (isInVerticalRange && kotlin.math.abs(x - holeRect.right) < es) return RESIZE_RIGHT
        if (isInHorizontalRange && kotlin.math.abs(y - holeRect.top) < es) return RESIZE_TOP
        if (isInHorizontalRange && kotlin.math.abs(y - holeRect.bottom) < es) return RESIZE_BOTTOM

        if (holeRect.contains(x, y)) return MOVE
        return NEW
    }

    private fun clampRect() {
        val w = width.toFloat()
        val h = height.toFloat()

        // 宽/高超屏时，先收缩到屏幕
        if (holeRect.width() > w) {
            holeRect.right = holeRect.left + w
        }
        if (holeRect.height() > h) {
            holeRect.bottom = holeRect.top + h
        }

        // 保证 4 边在屏幕内（先平移，再夹紧）
        if (holeRect.left < 0) {
            holeRect.right -= holeRect.left
            holeRect.left = 0f
        }
        if (holeRect.top < 0) {
            holeRect.bottom -= holeRect.top
            holeRect.top = 0f
        }
        if (holeRect.right > w) {
            holeRect.left -= (holeRect.right - w)
            holeRect.right = w
        }
        if (holeRect.bottom > h) {
            holeRect.top -= (holeRect.bottom - h)
            holeRect.bottom = h
        }

        // 二次保险：left/top 不为负
        if (holeRect.left < 0) holeRect.left = 0f
        if (holeRect.top < 0) holeRect.top = 0f
    }

    private fun clampMinSize() {
        if (holeRect.width() < MIN_HOLE_SIZE) {
            holeRect.right = holeRect.left + MIN_HOLE_SIZE
        }
        if (holeRect.height() < MIN_HOLE_SIZE) {
            holeRect.bottom = holeRect.top + MIN_HOLE_SIZE
        }
    }
}