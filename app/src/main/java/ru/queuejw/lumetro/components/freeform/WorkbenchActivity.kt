package ru.queuejw.lumetro.components.freeform

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.freeform.util.U
import ru.queuejw.lumetro.components.freeform.util.ApplicationType
import ru.queuejw.lumetro.components.freeform.helper.FreeformHackHelper

class WorkbenchActivity : Activity() {

    private lateinit var appListView: RecyclerView
    private lateinit var appAdapter: WorkbenchAppAdapter
    private lateinit var closeButton: ImageView
    private val appList = mutableListOf<AppItem>()

    private var windowLeft = 0
    private var windowTop = 0
    private var windowRight = 0
    private var windowBottom = 0
    private var isFirstLaunch = true

    private val FIXED_APPS = listOf(
        "com.android.settings",
        "com.android.chrome",
        "com.android.calculator2",
        "com.android.deskclock",
        "com.android.mms",
        "com.android.contacts",
        "com.android.camera",
        "com.android.filemanager",
        "com.android.vending",
        "com.google.android.youtube"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_workbench)

        calculateWindowBounds()

        appListView = findViewById(R.id.workbench_app_list)
        closeButton = findViewById(R.id.workbench_close)

        setupAppList()

        closeButton.setOnClickListener {
            exitWorkbench()
        }

        if (isFirstLaunch) {
            isFirstLaunch = false
            activateFreeformMode()
        }
    }

    private fun calculateWindowBounds() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 窗口位置：让标题栏超出屏幕顶部（-40dp），同时窗口高度增加相应值
        val titleBarHeight = 80 // dp，标题栏大约高度
        val titleBarPx = (titleBarHeight * resources.displayMetrics.density).toInt()

        windowLeft = 0
        windowTop = -titleBarPx  // 向上偏移，隐藏标题栏
        windowRight = (screenWidth * 0.86f).toInt()
        windowBottom = screenHeight + titleBarPx  // 向下延伸，保持内容完整

        val prefs = getSharedPreferences("workbench", MODE_PRIVATE)
        prefs.edit().apply {
            putInt("window_left", windowLeft)
            putInt("window_top", windowTop)
            putInt("window_right", windowRight)
            putInt("window_bottom", windowBottom)
            putBoolean("is_active", true)
            apply()
        }
    }

    private fun setupAppList() {
        val pm = packageManager
        appList.clear()

        for (pkgName in FIXED_APPS) {
            try {
                val appInfo = pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                appList.add(AppItem(label, pkgName, icon))
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
        }

        appAdapter = WorkbenchAppAdapter(appList) { packageName ->
            launchAppInWorkbench(packageName)
        }

        appListView.layoutManager = LinearLayoutManager(this)
        appListView.adapter = appAdapter
    }

    private fun activateFreeformMode() {
        if (!U.canDrawOverlays(this) || !U.hasFreeformSupport(this)) {
            Toast.makeText(this, "设备不支持自由窗口模式", Toast.LENGTH_SHORT).show()
            return
        }

        if (FreeformHackHelper.getInstance().isFreeformHackActive()) {
            Toast.makeText(this, "工作台已就绪 (${appList.size}个应用)", Toast.LENGTH_SHORT).show()
            return
        }

        FreeformHackHelper.getInstance().reset()
        U.stopFreeformHack(this)

        Handler(Looper.getMainLooper()).postDelayed({
            U.startFreeformHack(this, true)
            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(this, "工作台已就绪 (${appList.size}个应用)", Toast.LENGTH_SHORT).show()
            }, 150)
        }, 150)
    }

    private fun launchAppInWorkbench(packageName: String) {
        try {
            if (!FreeformHackHelper.getInstance().isFreeformHackActive()) {
                activateFreeformMode()
                Thread.sleep(300)
            }

            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: throw Exception("无法获取启动Intent")

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val options = U.getActivityOptionsBundle(
                this,
                ApplicationType.APP_PORTRAIT,
                null,
                windowLeft,
                windowTop,
                windowRight,
                windowBottom
            )

            if (options != null) {
                startActivity(launchIntent, options)
            } else {
                startActivity(launchIntent)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun exitWorkbench() {
        val prefs = getSharedPreferences("workbench", MODE_PRIVATE)
        prefs.edit().putBoolean("is_active", false).apply()

        sendBroadcast(Intent("ru.queuejw.lumetro.FINISH_ALL_FREEFORM"))
        U.stopFreeformHack(this)
        FreeformHackHelper.getInstance().reset()

        overridePendingTransition(0, 0)

        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 300)
    }

    override fun onBackPressed() {
        exitWorkbench()
    }

    override fun onDestroy() {
        if (!isFinishing) {
            U.stopFreeformHack(this)
            FreeformHackHelper.getInstance().reset()
        }
        super.onDestroy()
    }

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: Drawable
    )
}
