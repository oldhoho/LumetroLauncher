package ru.queuejw.lumetro.components.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.material.color.DynamicColors
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.prefs.Prefs
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Lumetro : Application() {

    companion object {
        var isOtherAppOpened = false
        var viewPagerUserInputEnabled = false
        private lateinit var logDir: File

        fun getLogDir(): File = logDir

        fun logToFile(tag: String, msg: String) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val line = "${sdf.format(Date())} $tag: $msg\n"
                File(logDir, "lumetro.log").appendText(line)
            } catch (_: Exception) {}
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化日志目录
        logDir = File(getExternalFilesDir(null), "logs").also { it.mkdirs() }
        logToFile("Lumetro", "========== App started ==========")

        // 全局崩溃日志
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            logToFile("CRASH", "Thread: ${thread.name}\n${sw}")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        ShizukuHelper.getInstance().init(this)
        if (rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendBroadcast(Intent("ru.queuejw.lumetro.HIDE_PANEL"))
            rikka.shizuku.Shizuku.requestPermission(0)
        }

        try {
            val prefs = Prefs(this)
            if (prefs.dynamicColorEnabled) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
            logToFile("Lumetro", "Initialized successfully")
        } catch (e: Exception) {
            logToFile("Lumetro", "Failed to initialize: ${e.message}")
        }
    }
}
