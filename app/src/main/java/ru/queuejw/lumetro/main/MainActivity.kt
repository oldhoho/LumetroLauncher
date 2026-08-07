package ru.queuejw.lumetro.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.WallpaperManager
import android.app.usage.UsageStatsManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
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

        setupBaseUI()
        checkAllPermissions()
    }

    private fun setupBaseUI() {
        wallpaperView = ImageView(this).apply {
            setBackgroundColor(0xFF1A1A1A.toInt())
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

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun checkAllPermissions() {
        // 1. 管理文件权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Toast.makeText(this, "需要管理所有文件权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${packageName}"))
                startActivity(intent)
                return
            }
        }

        // 2. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${packageName}")
            )
            startActivity(intent)
            return
        }

        // 3. 使用情况访问权限
        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "需要使用情况访问权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        // 4. 无障碍服务
        if (!SidebarAccessibilityService.isServiceEnabled(this)) {
            Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        initApp()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) return false
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - 1000 * 60,
            now
        )
        return stats != null && stats.isNotEmpty()
    }

    private fun initApp() {
        // 延迟加载壁纸
        Handler(Looper.getMainLooper()).postDelayed({
            loadWallpaper()
        }, 300)

        setupReceiver()

        // 延迟初始化 Shizuku
        Handler(Looper.getMainLooper()).postDelayed({
            initShizuku()
        }, 500)

        // 延迟检查无障碍
        Handler(Looper.getMainLooper()).postDelayed({
            if (!SidebarAccessibilityService.isServiceEnabled(this)) {
                Toast.makeText(this, "请开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, 1000)
    }

    private fun loadWallpaper() {
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)
            val wallpaperDrawable = wallpaperManager.drawable
            if (wallpaperDrawable != null) {
                wallpaperView.setImageDrawable(wallpaperDrawable)
            } else {
                wallpaperView.setBackgroundColor(0xFF1A1A1A.toInt())
                // 重试一次
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val retryDrawable = WallpaperManager.getInstance(this).drawable
                        if (retryDrawable != null) {
                            wallpaperView.setImageDrawable(retryDrawable)
                        }
                    } catch (e: Exception) { }
                }, 500)
            }
        } catch (e: Exception) {
            wallpaperView.setBackgroundColor(0xFF1A1A1A.toInt())
        }
    }

    private fun setupReceiver() {
        bgReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                loadWallpaper()
            }
        }
        registerReceiver(bgReceiver, IntentFilter("ru.queuejw.lumetro.UPDATE_MAIN_BG"), RECEIVER_EXPORTED)
    }

    private fun initShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                requestShizukuPermission()
            } else {
                Shizuku.addBinderReceivedListener {
                    requestShizukuPermission()
                }
            }
        } catch (e: Exception) {
            // 静默处理
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Shizuku.requestPermission(100)
                    } catch (e: Exception) { }
                }, 500)
            }
        } catch (e: Exception) { }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Handler(Looper.getMainLooper()).postDelayed({
            checkAllPermissions()
        }, 300)
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

                if (touchCount >= 2) {
                    if (isVertical && (diffY > 80 || diffY < -80)) {
                        return true
                    }
                }

                when {
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
                    isVertical && diffY < -80 -> {
                        SidebarAccessibilityService.sidebarManager?.showAppsPanel()
                        return true
                    }
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