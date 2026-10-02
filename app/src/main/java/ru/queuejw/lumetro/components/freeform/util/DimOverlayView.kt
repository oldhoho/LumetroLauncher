package ru.queuejw.lumetro.components.freeform.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 暗幕图层：只负责画，不接收触摸。
 * - 半透明黑盖住全屏
 * - 中间挖一个矩形洞（用户指定区域）
 * - 编辑模式下画边框 + 四角 + 四边中点
 */
class DimOverlayView(context: Context) : View(context) {

    var dimAlpha: Int = 200
    var editAlpha: Int = 100

    var holeRect: RectF = RectF()

    var editing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onHoleChanged: (() -> Unit)? = null

    private val paintDim = Paint().apply { color = Color.argb(dimAlpha, 0, 0, 0) }
    private val paintBorderActive = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#FF4488FF")
        isAntiAlias = true
    }
    private val paintHandle = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4488FF")
        isAntiAlias = true
    }

    fun ensureHoleInitialized(width: Int, height: Int) {
        if (holeRect.isEmpty) {
            holeRect.set(
                width * 0.1f,
                height * 0.25f,
                width * 0.9f,
                height * 0.75f
            )
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentAlpha = if (editing) editAlpha else dimAlpha
        paintDim.color = Color.argb(currentAlpha, 0, 0, 0)

        val w = width.toFloat()
        val h = height.toFloat()

        if (holeRect.isEmpty) {
            canvas.drawRect(0f, 0f, w, h, paintDim)
        } else {
            canvas.drawRect(0f, 0f, w, holeRect.top, paintDim)
            canvas.drawRect(0f, holeRect.bottom, w, h, paintDim)
            canvas.drawRect(0f, holeRect.top, holeRect.left, holeRect.bottom, paintDim)
            canvas.drawRect(holeRect.right, holeRect.top, w, holeRect.bottom, paintDim)
        }

        if (editing && !holeRect.isEmpty) {
            canvas.drawRect(holeRect, paintBorderActive)

            val hs = 12f
            canvas.drawCircle(holeRect.left, holeRect.top, hs, paintHandle)
            canvas.drawCircle(holeRect.right, holeRect.top, hs, paintHandle)
            canvas.drawCircle(holeRect.left, holeRect.bottom, hs, paintHandle)
            canvas.drawCircle(holeRect.right, holeRect.bottom, hs, paintHandle)

            val edgeLen = 40f
            val cx = (holeRect.left + holeRect.right) / 2
            val cy = (holeRect.top + holeRect.bottom) / 2
            canvas.drawLine(cx - edgeLen / 2, holeRect.top, cx + edgeLen / 2, holeRect.top, paintBorderActive)
            canvas.drawLine(cx - edgeLen / 2, holeRect.bottom, cx + edgeLen / 2, holeRect.bottom, paintBorderActive)
            canvas.drawLine(holeRect.left, cy - edgeLen / 2, holeRect.left, cy + edgeLen / 2, paintBorderActive)
            canvas.drawLine(holeRect.right, cy - edgeLen / 2, holeRect.right, cy + edgeLen / 2, paintBorderActive)
        }
    }

    /** 外部更新 holeRect 后调用 */
    fun refresh() {
        invalidate()
        onHoleChanged?.invoke()
    }
}