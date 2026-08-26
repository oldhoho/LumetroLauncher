package ru.queuejw.lumetro.components.freeform

import android.content.Context
import android.content.SharedPreferences

class WorkbenchSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("workbench_settings", Context.MODE_PRIVATE)

    // ========== 工作台设置 ==========
    var height: Int
        get() = prefs.getInt("workbench_height", 180)
        set(value) = prefs.edit().putInt("workbench_height", value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("workbench_enabled", true)
        set(value) = prefs.edit().putBoolean("workbench_enabled", value).apply()

    var showAppsListOnStart: Boolean
        get() = prefs.getBoolean("workbench_show_apps_on_start", false)
        set(value) = prefs.edit().putBoolean("workbench_show_apps_on_start", value).apply()

    var autoHideDelay: Int
        get() = prefs.getInt("workbench_auto_hide_delay", 0)
        set(value) = prefs.edit().putInt("workbench_auto_hide_delay", value).apply()

    // ========== 应用列表面板设置 ==========
    var appListWidthRatio: Float
        get() = prefs.getFloat("app_list_width_ratio", 0.85f)
        set(value) = prefs.edit().putFloat("app_list_width_ratio", value.coerceIn(0.5f, 1.0f)).apply()
    
    var appListHeightRatio: Float
        get() = prefs.getFloat("app_list_height_ratio", 0.9f)
        set(value) = prefs.edit().putFloat("app_list_height_ratio", value.coerceIn(0.3f, 1.0f)).apply()
    
    var appListVerticalOffset: Int
        get() = prefs.getInt("app_list_vertical_offset", 0)
        set(value) = prefs.edit().putInt("app_list_vertical_offset", value).apply()
    
    var appListCornerRadius: Int
        get() = prefs.getInt("app_list_corner_radius", 24)
        set(value) = prefs.edit().putInt("app_list_corner_radius", value.coerceIn(0, 60)).apply()
    
    var appListDimAlpha: Float
        get() = prefs.getFloat("app_list_dim_alpha", 0.8f)
        set(value) = prefs.edit().putFloat("app_list_dim_alpha", value.coerceIn(0.0f, 1.0f)).apply()

    // ========== 手势条设置（持久化） ==========
    var gestureStripWidth: Int
        get() = prefs.getInt("gesture_strip_width", 6)
        set(value) {
            prefs.edit().putInt("gesture_strip_width", value.coerceIn(2, 32)).apply()
        }

    var gestureStripHeight: Int
        get() = prefs.getInt("gesture_strip_height", 0)
        set(value) {
            prefs.edit().putInt("gesture_strip_height", value.coerceAtLeast(0)).apply()
        }

    var gestureStripOffset: Int
        get() = prefs.getInt("gesture_strip_offset", 0)
        set(value) {
            prefs.edit().putInt("gesture_strip_offset", value.coerceIn(0, 800)).apply()
        }

    var gestureStripAlpha: Float
        get() = prefs.getFloat("gesture_strip_alpha", 0.3f)
        set(value) {
            prefs.edit().putFloat("gesture_strip_alpha", value.coerceIn(0.0f, 1.0f)).apply()
        }

    fun resetToDefaults() {
        try {
            prefs.edit().clear().apply()
            height = 180
            enabled = true
            showAppsListOnStart = false
            autoHideDelay = 0
            appListWidthRatio = 0.85f
            appListHeightRatio = 0.9f
            appListVerticalOffset = 0
            appListCornerRadius = 24
            appListDimAlpha = 0.8f
            gestureStripWidth = 6
            gestureStripHeight = 0
            gestureStripOffset = 0
            gestureStripAlpha = 0.3f
        } catch (e: Exception) {
            // 忽略
        }
    }
}