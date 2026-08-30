package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class ScaleLayoutManager(
    context: Context,
    orientation: Int = VERTICAL,
    reverseLayout: Boolean = false
) : LinearLayoutManager(context, orientation, reverseLayout) {

    companion object {
        val GRID_HEIGHTS_DP = intArrayOf(80, 70, 60, 50, 40, 30, 20)
        val ICON_SIZES_DP = intArrayOf(75, 65, 55, 45, 35, 25, 20)
        val MIN_GRID_HEIGHT_DP = 20
        val MIN_ICON_SIZE_DP = 20
        
        private const val HEADER_HEIGHT_DP = 28
    }

    private val density = context.resources.displayMetrics.density
    private var isScaleEnabled = true
    private var recyclerView: RecyclerView? = null
    private var isLayouting = false
    
    // ========== 固定格子区域 ==========
    private val gridRects = mutableListOf<RectF>()
    
    private fun Int.dpToPx(): Int = (this * density).toInt()
    private fun Float.dpToPx(): Int = (this * density).toInt()

    fun setScaleEnabled(enabled: Boolean) {
        if (isScaleEnabled != enabled) {
            isScaleEnabled = enabled
            recyclerView?.post { applyScaleToChildren() }
        }
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        view.post { 
            calculateGridRects()
            applyScaleToChildren()
        }
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        recyclerView = null
        gridRects.clear()
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        isLayouting = true
        super.onLayoutChildren(recycler, state)
        isLayouting = false
        recyclerView?.post { 
            calculateGridRects()
            applyScaleToChildren()
        }
    }
    
    // ========== 计算固定格子的位置 ==========
    private fun calculateGridRects() {
        val rv = recyclerView ?: return
        val width = rv.width
        val height = rv.height
        if (width == 0 || height == 0) return
        
        gridRects.clear()
        
        var currentBottom = height.toFloat()
        var index = 0
        
        // 从底部向上排列格子，直到顶部
        while (currentBottom > 0) {
            val gridHeightDp = if (index < GRID_HEIGHTS_DP.size) {
                GRID_HEIGHTS_DP[index]
            } else {
                MIN_GRID_HEIGHT_DP  // 剩余空间都用最小格子
            }
            
            val gridHeight = gridHeightDp.dpToPx().toFloat()
            val top = (currentBottom - gridHeight).coerceAtLeast(0f)
            val rect = RectF(0f, top, width.toFloat(), currentBottom)
            gridRects.add(rect)
            currentBottom = top
            index++
        }
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        val scrolled = super.scrollVerticallyBy(dy, recycler, state)
        if (scrolled != 0 && !isLayouting) {
            applyScaleToChildren()
        }
        return scrolled
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (!isLayouting) {
            applyScaleToChildren()
        }
    }

    private fun applyScaleToChildren() {
        if (isLayouting) return
        
        val childCount = childCount
        if (childCount == 0 || !isScaleEnabled) return

        val recyclerHeight = recyclerView?.height ?: 0
        if (recyclerHeight == 0 || gridRects.isEmpty()) return

        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            
            val viewHolder = recyclerView?.findViewHolderForAdapterPosition(position)
            if (viewHolder is GroupedAppListAdapter.HeaderViewHolder) {
                child.scaleX = 1f
                child.scaleY = 1f
                child.alpha = 1f
                child.translationY = 0f
                child.translationZ = 0f
                continue
            }

            // ========== 找到当前 item 应该属于哪个格子 ==========
            val itemCenterY = child.top + child.height / 2f
            
            var bestGridIndex = 0
            var bestDistance = Float.MAX_VALUE
            
            for (gridIndex in gridRects.indices) {
                val gridCenterY = gridRects[gridIndex].centerY()
                val distance = abs(itemCenterY - gridCenterY)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestGridIndex = gridIndex
                }
            }
            
            // ========== 获取该格子的参数 ==========
            val gridRect = gridRects[bestGridIndex]
            val gridHeight = gridRect.height().toInt()
            
            val iconSizeDp = if (bestGridIndex < ICON_SIZES_DP.size) {
                ICON_SIZES_DP[bestGridIndex]
            } else {
                MIN_ICON_SIZE_DP
            }
            val iconSize = iconSizeDp.dpToPx().toFloat()
            
            // ========== 设置 child 高度等于格子高度（嵌入格子） ==========
            child.layoutParams.height = gridHeight
            
            // ========== 计算缩放 ==========
            val maxIconSize = ICON_SIZES_DP[0].dpToPx().toFloat()
            val scale = iconSize / maxIconSize
            
            // ========== 应用缩放（居中） ==========
            child.pivotX = child.width / 2f
            child.pivotY = child.height / 2f  // 居中缩放
            child.scaleX = scale
            child.scaleY = scale
            
            // ========== 透明度 ==========
            val alpha = 0.5f + 0.5f * (iconSize - MIN_ICON_SIZE_DP.dpToPx()) / 
                       (ICON_SIZES_DP[0].dpToPx() - MIN_ICON_SIZE_DP.dpToPx())
            child.alpha = alpha.coerceIn(0.5f, 1f)
            
            // ========== Z轴 ==========
            child.translationZ = (scale - 0.26f) * 20f
            
            child.translationX = 0f
            child.translationY = 0f
        }
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State,
        position: Int
    ) {
        val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {
            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_START
            }
            
            override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                return 2f / displayMetrics.density
            }
            
            override fun calculateTimeForScrolling(dx: Int): Int {
                return 200
            }
            
            override fun calculateTimeForDeceleration(dx: Int): Int {
                return 50
            }
        }
        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }

    fun scrollToPositionImmediately(position: Int) {
        recyclerView?.post {
            scrollToPositionWithOffset(position, 0)
            applyScaleToChildren()
        }
    }

    fun scrollToTopFast() {
        recyclerView?.post {
            val smoothScroller = object : LinearSmoothScroller(recyclerView!!.context) {
                override fun getVerticalSnapPreference(): Int {
                    return SNAP_TO_START
                }
                
                override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                    return 2f / displayMetrics.density
                }
                
                override fun calculateTimeForScrolling(dx: Int): Int {
                    return 150
                }
                
                override fun calculateTimeForDeceleration(dx: Int): Int {
                    return 30
                }
            }
            smoothScroller.targetPosition = 0
            startSmoothScroll(smoothScroller)
        }
    }

    fun resetScale() {
        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            child.scaleX = 1f
            child.scaleY = 1f
            child.alpha = 1f
            child.translationX = 0f
            child.translationY = 0f
            child.translationZ = 0f
            child.pivotX = 0f
            child.pivotY = 0f
        }
    }
}