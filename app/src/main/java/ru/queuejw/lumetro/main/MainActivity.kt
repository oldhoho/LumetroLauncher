package ru.queuejw.lumetro.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.app.WallpaperManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService

class MainActivity : AppCompatActivity() {

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var touchCount = 0
    private lateinit var wallpaperView: ImageView
    private var bgReceiver: BroadcastReceiver? = null
    private var volumePressCount = 0
    private var lastVolumePressTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!android.os.Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:ru.queuejw.lumetro")))
        }
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        if (!prefs.getBoolean("perm_asked", false)) {
            prefs.edit().putBoolean("perm_asked", true).apply()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        wallpaperView = ImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = FrameLayout(this).apply {
            fitsSystemWindows = false
            addView(wallpaperView)
        }
        setContentView(rootLayout)

        loadWallpaper()

        bgReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                loadWallpaper()
            }
        }
        registerReceiver(bgReceiver, IntentFilter("ru.queuejw.lumetro.UPDATE_MAIN_BG"), RECEIVER_EXPORTED)
    }

    private fun loadWallpaper() {
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)
            val wallpaperDrawable = wallpaperManager.drawable
            if (wallpaperDrawable != null) {
                wallpaperView.setImageDrawable(wallpaperDrawable)
            } else {
                wallpaperView.setBackgroundColor(0xFF000000.toInt())
            }
        } catch (e: Exception) {
            wallpaperView.setBackgroundColor(0xFF000000.toInt())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastVolumePressTime > 1000) {
                volumePressCount = 0
            }
            volumePressCount++
            lastVolumePressTime = currentTime
            if (volumePressCount >= 3) {
                volumePressCount = 0
                // 三次连按音量键 → 打开侧边栏
                SidebarAccessibilityService.sidebarManager?.showPanel()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isDragging = false
                touchCount = event.pointerCount
            }
            MotionEvent.ACTION_MOVE -> {
                val diffX = event.rawX - startX
                val diffY = event.rawY - startY
                if (Math.abs(diffX) > 20 || Math.abs(diffY) > 20) {
                    isDragging = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val diffX = event.rawX - startX
                val diffY = event.rawY - startY
                val absDiffX = Math.abs(diffX)
                val absDiffY = Math.abs(diffY)

                if (!isDragging) {
                    return true
                }

                val isHorizontal = absDiffX > absDiffY
                val isVertical = absDiffY > absDiffX

                // 双指手势
                if (touchCount >= 2) {
                    if (isVertical && (diffY > 80 || diffY < -80)) {
                        return true
                    }
                }

                // 单指手势
                when {
                    // 左滑 → 展开/关闭侧边栏
                    isHorizontal && diffX < -80 -> {
                        SidebarAccessibilityService.sidebarManager?.let {
                            if (it.isPanelExpanded()) {
                                it.hidePanel()
                            } else {
                                it.showPanel()
                            }
                        }
                        return true
                    }
                    // 上滑 → 打开全部应用列表（九键面板）
isVertical && diffY < -80 -> {
    SidebarAccessibilityService.sidebarManager?.showAppsPanel()
    return true
}
                    // 下滑 → 展开通知
                    isVertical && diffY > 80 -> {
                        SidebarAccessibilityService.getInstance()?.performGlobalAction(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        )
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        bgReceiver?.let { unregisterReceiver(it) }
    }

    override fun onBackPressed() {}
}