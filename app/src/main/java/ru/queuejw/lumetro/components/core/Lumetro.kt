package ru.queuejw.lumetro.components.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.material.color.DynamicColors
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.prefs.Prefs

class Lumetro : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.getInstance().init(this)
        if (rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        sendBroadcast(Intent("ru.queuejw.lumetro.HIDE_PANEL"))
            rikka.shizuku.Shizuku.requestPermission(0)
        }
        Log.d("Lumetro", "Application onCreate")
        
        try {
            val prefs = Prefs(this)
            if (prefs.dynamicColorEnabled) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
            Log.d("Lumetro", "Application initialized successfully")
        } catch (e: Exception) {
            Log.e("Lumetro", "Failed to initialize", e)
        }
    }

    companion object {
        var isOtherAppOpened = false
        var viewPagerUserInputEnabled = false
    }
}