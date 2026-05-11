package ru.queuejw.lumetro.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var startX = 0f
    private var startY = 0f
    private lateinit var wallpaperView: ImageView
    private var bgReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!android.os.Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:ru.queuejw.lumetro")))
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

        // 设置窗口全屏，壁纸覆盖状态栏
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        // 壁纸视图
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
        val savedBg = getSharedPreferences("settings", MODE_PRIVATE).getString("main_bg_image", "")
        if (!savedBg.isNullOrEmpty()) {
            try {
                val raw = android.util.Base64.decode(savedBg, android.util.Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bm != null) {
                    val sw = resources.displayMetrics.widthPixels
                    val sh = resources.displayMetrics.heightPixels
                    val scale = Math.max(sw.toFloat() / bm.width, sh.toFloat() / bm.height)
                    val sw2 = (bm.width * scale).toInt()
                    val sh2 = (bm.height * scale).toInt()
                    val scaled = Bitmap.createScaledBitmap(bm, sw2, sh2, true)
                    val x = (sw2 - sw) / 2
                    val y = (sh2 - sh) / 2
                    val cropped = Bitmap.createBitmap(scaled, Math.max(0, x), Math.max(0, y), sw, sh)
                    wallpaperView.setImageBitmap(cropped)
                }
            } catch (e: Exception) {
                wallpaperView.setBackgroundColor(0xFF000000.toInt())
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val diffX = event.rawX - startX
                val diffY = event.rawY - startY

                if (diffX < -120 && Math.abs(diffX) > Math.abs(diffY) * 1.5f) {
                    sendBroadcast(Intent("ru.queuejw.lumetro.SHOW_PANEL"))
                    return true
                }

                if (diffY > 180 && Math.abs(diffY) > Math.abs(diffX) * 1.5f) {
                    sendBroadcast(Intent("ru.queuejw.lumetro.EXPAND_NOTIFICATION"))
                    return true
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