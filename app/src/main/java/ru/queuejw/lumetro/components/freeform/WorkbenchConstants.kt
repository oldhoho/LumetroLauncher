package ru.queuejw.lumetro.components.freeform

object WorkbenchConstants {

    // ========== 默认值 ==========
    const val DEFAULT_HEIGHT_DP = 180
    const val DEFAULT_POSITION = "bottom"

    // ========== 最大/最小值 ==========
    const val MIN_HEIGHT_DP = 80
    const val MAX_HEIGHT_DP = 300

    // ========== 应用列表 ==========
    const val MAX_SLOTS = 7
    const val MAX_APPS = 50
    const val ITEMS_PER_PAGE = 7

    // ========== 手势 ==========
    const val GESTURE_STRIP_WIDTH_DP = 6
    const val GESTURE_STRIP_ALPHA = 0.3f
    const val SWIPE_THRESHOLD_DP = 40
    const val LONG_PRESS_DELAY_MS = 500

    // ========== 动画 ==========
    const val ANIMATION_DURATION_MS = 300
    const val AUTO_HIDE_DELAY_MS = 5000

    // ========== Preferences Keys ==========
    const val PREF_WORKBENCH = "workbench_prefs"
    const val PREF_KEY_HEIGHT = "workbench_height"
    const val PREF_KEY_ENABLED = "workbench_enabled"
    const val PREF_KEY_POSITION = "workbench_position"
    const val PREF_KEY_SHOW_APPS_ON_START = "show_apps_on_start"
    const val PREF_KEY_AUTO_HIDE_DELAY = "auto_hide_delay"
    const val PREF_KEY_BLACKLIST = "blacklist"
    const val PREF_KEY_ICON_PACK = "icon_pack_package"

    // ========== Broadcast Actions ==========
    const val ACTION_TOGGLE_WORKBENCH = "ru.queuejw.lumetro.TOGGLE_WORKBENCH"
    const val ACTION_SHOW_WORKBENCH = "ru.queuejw.lumetro.SHOW_WORKBENCH"
    const val ACTION_HIDE_WORKBENCH = "ru.queuejw.lumetro.HIDE_WORKBENCH"
    const val ACTION_UPDATE_WORKBENCH = "ru.queuejw.lumetro.UPDATE_WORKBENCH"
}