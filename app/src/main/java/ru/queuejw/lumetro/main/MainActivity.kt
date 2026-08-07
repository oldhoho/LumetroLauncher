package ru.queuejw.lumetro.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

        // 设置基础内容
        setupBaseUI()
        // 检查所有权限
        checkAllPermissions()
    }

    private fun setupBaseUI() {
        // 先设置一个黑色背景，避免白屏
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

        // 所有权限已就绪，初始化应用
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
        // 加载壁纸
        loadWallpaper()
        setupReceiver()
        // 初始化 Shizuku
        initShizuku()
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
                // Shizuku 已连接，请求权限
                requestShizukuPermission()
            } else {
                // 等待 Shizuku 连接
                Shizuku.addBinderReceivedListener {
                    requestShizukuPermission()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "请安装 Shizuku", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请授权 Shizuku", Toast.LENGTH_SHORT).show()
                // 请求 Shizuku 权限
                Shizuku.requestPermission(100)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku 权限请求失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 从权限设置返回后重新检查
        checkAllPermissions()
    }

    // ============ 手势处理 ============

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