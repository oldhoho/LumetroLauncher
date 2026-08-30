package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView

class GridItemDecoration(
    context: Context,
    private val gridHeightsDp: IntArray,
    private val minGridHeightDp: Int = 20,
    private val showGrid: Boolean = true
) : RecyclerView.ItemDecoration() {
    
    private val density = context.resources.displayMetrics.density
    private val gridRects = mutableListOf<RectF>()
    
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.RED
        isAntiAlias = true
    }
    
    private val gridFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#11FF0000")
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 20f
        isAntiAlias = true
    }
    
    private fun Int.dpToPx(): Int = (this * density).toInt()
    
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        
        if (!showGrid) return
        
        calculateGridRects(parent)
        
        for ((index, rect) in gridRects.withIndex()) {
            c.drawRect(rect, gridFillPaint)
            c.drawRect(rect, gridPaint)
            
            // 只在较大格子上显示编号
            if (rect.height() > minGridHeightDp.dpToPx()) {
                val label = "${gridHeightsDp.getOrElse(index) { minGridHeightDp }}dp"
                c.drawText(
                    label,
                    rect.left + 4.dpToPx(),
                    rect.centerY(),
                    textPaint
                )
            }
        }
    }
    
    private fun calculateGridRects(parent: RecyclerView) {
        gridRects.clear()
        
        val width = parent.width
        val height = parent.height
        if (width == 0 || height == 0) return
        
        var currentBottom = height.toFloat()
        var index = 0
        
        // 从底部向上排列格子
        while (currentBottom > 0) {
            val gridHeightDp = if (index < gridHeightsDp.size) {
                gridHeightsDp[index]
            } else {
                minGridHeightDp  // 剩余空间都用最小格子
            }
            
            val gridHeight = gridHeightDp.dpToPx().toFloat()
            val top = (currentBottom - gridHeight).coerceAtLeast(0f)
            val rect = RectF(0f, top, width.toFloat(), currentBottom)
            gridRects.add(rect)
            currentBottom = top
            index++
        }
    }
}