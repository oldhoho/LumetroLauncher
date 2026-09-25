package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import ru.queuejw.lumetro.components.core.icons.IconLoader

/**
 * 【已废弃】磁贴面板功能已移除。
 * 保留空壳类以兼容旧的调用点，避免大批量删除引用。
 * 所有公开方法都是空实现，运行时不会创建任何 View。
 */
class SidebarManager(private val context: Context) {

    private val TAG = "SidebarManager"

    // ========== 保留的公开字段（外部可能读） ==========
    var iconLoader: IconLoader = IconLoader(false, null)

    enum class PanelLevel {
        HIDDEN, NORMAL, EXPANDED
    }

    // ========== 公开方法：全部空实现 ==========

    fun setOnPanelStateChangeListener(l: (Boolean, PanelLevel) -> Unit) {
        // 已废弃
    }

    fun configureTouchPassthrough() {
        // 已废弃
    }

    fun createGestureStrip() {
        // 已废弃
    }

    fun handleGesture(e: MotionEvent): Boolean {
        return false
    }

    fun createPanel() {
        // 已废弃
    }

    fun updateData(t: List<Any>) {
        // 已废弃
    }

    fun reloadIconPack() {
        // 已废弃
    }

    fun showPanel() {
        // 已废弃
    }

    fun showAppsPanel() {
        // 已废弃
    }

    fun selectLetter(letter: String?) {
        // 已废弃
    }

    fun hidePanel() {
        // 已废弃
    }

    fun hidePanelImmediately() {
        // 已废弃
    }

    fun isPanelExpanded(): Boolean = false

    fun getTiles(): List<Any> = emptyList()

    fun destroyGestureStrip() {
        // 已废弃
    }

    fun destroy() {
        // 已废弃
    }
}