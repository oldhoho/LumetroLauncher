package ru.queuejw.lumetro.components.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val fileName: String = "settings"

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun reset(): Boolean {
        prefs.edit(commit = true) { clear() }
        return true
    }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("first_launch", true)
        set(value) = prefs.edit { putBoolean("first_launch", value) }

    var isRestartRequired: Boolean
        get() = prefs.getBoolean("restart_required", false)
        set(value) = prefs.edit { putBoolean("restart_required", value) }

    var appTheme: Int
        get() = prefs.getInt("app_theme", 0)
        set(value) = prefs.edit { putInt("app_theme", value) }

    var accentColorValue: String
        get() = prefs.getString("accent_color", "#FF6A00FF")!!
        set(value) = prefs.edit { putString("accent_color", value) }

    var dynamicColorEnabled: Boolean
        get() = prefs.getBoolean("dynamic_color", false)
        set(value) = prefs.edit { putBoolean("dynamic_color", value) }

    var showMoreTilesEnabled: Boolean
        get() = prefs.getBoolean("show_more_tiles", false)
        set(value) = prefs.edit { putBoolean("show_more_tiles", value) }

    var fleProgress: Int
        get() = prefs.getInt("fle_progress", 0)
        set(value) = prefs.edit { putInt("fle_progress", value) }

    var allowSaveErrorData: Boolean
        get() = prefs.getBoolean("allow_save_errors", true)
        set(value) = prefs.edit { putBoolean("allow_save_errors", value) }

    var showErrorDetailsWhenCrash: Boolean
        get() = prefs.getBoolean("show_error_details", false)
        set(value) = prefs.edit { putBoolean("show_error_details", value) }

    var allowEditModeAnimation: Boolean
        get() = prefs.getBoolean("allow_editmode_anim", true)
        set(value) = prefs.edit { putBoolean("allow_editmode_anim", value) }

    var editModeEnabled: Boolean
        get() = prefs.getBoolean("editmode_enabled", true)
        set(value) = prefs.edit { putBoolean("editmode_enabled", value) }

    var experimentsEnabled: Boolean
        get() = prefs.getBoolean("experiments", false)
        set(value) = prefs.edit { putBoolean("experiments", value) }

    var iconPackPackage: String?
        get() = prefs.getString("iconpack", null)
        set(value) = prefs.edit { putString("iconpack", value) }

    var autoPinEnabled: Boolean
        get() = prefs.getBoolean("auto_app_pin", true)
        set(value) = prefs.edit { putBoolean("auto_app_pin", value) }

    var coloredErrorScreen: Boolean
        get() = prefs.getBoolean("colored_error_screen", false)
        set(value) = prefs.edit { putBoolean("colored_error_screen", value) }

    var tileCornerRadius: Int
        get() = prefs.getInt("corner_radius", 0)
        set(value) = prefs.edit { putInt("corner_radius", value) }

    var panelBackgroundColor: String
        get() = prefs.getString("panel_bg_color", "#FF333333")!!
        set(value) = prefs.edit { putString("panel_bg_color", value) }

    var panelBackgroundAlpha: Float
        get() = prefs.getFloat("panel_bg_alpha", 1.0f)
        set(value) = prefs.edit { putFloat("panel_bg_alpha", value) }

    var panelBackgroundImage: String
        get() = prefs.getString("panel_bg_image", "")!!
        set(value) = prefs.edit { putString("panel_bg_image", value) }

    var gestureStripWidth: Int
        get() = prefs.getInt("gesture_strip_width", 6)
        set(value) = prefs.edit { putInt("gesture_strip_width", value) }

    var gestureStripHeight: Int
        get() = prefs.getInt("gesture_strip_height", 0)
        set(value) = prefs.edit { putInt("gesture_strip_height", value) }

    var gestureStripOffset: Int
        get() = prefs.getInt("gesture_strip_offset", 0)
        set(value) = prefs.edit { putInt("gesture_strip_offset", value) }

    var gestureStripAlpha: Float
        get() = prefs.getFloat("gesture_strip_alpha", 0.13f)
        set(value) = prefs.edit { putFloat("gesture_strip_alpha", value) }

    var tileAlpha: Float
        get() = prefs.getFloat("tile_alpha", 1.0f)
        set(value) = prefs.edit { putFloat("tile_alpha", value) }

    var navBarStyle: Int
        get() = prefs.getInt("nav_bar_style", 0)
        set(value) = prefs.edit { putInt("nav_bar_style", value) }
}