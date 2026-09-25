package ru.queuejw.lumetro.components.freeform.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "lumetro_crash.log"

    @Volatile
    private var installed = false

    private var appContext: Context? = null

    /**
     * 在 Application.onCreate 或任意入口调用一次
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "write crash log failed", e)
            }
            // 交给系统默认处理（避免丢崩溃弹窗）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    val sw = StringWriter()
    val pw = PrintWriter(sw)
    throwable.printStackTrace(pw)
    pw.flush()
    val stack = sw.toString()

    val sb = StringBuilder()
    sb.append("==========================================\n")
    sb.append("TIME   : $time\n")
    sb.append("THREAD : ${thread.name}\n")
    sb.append("DEVICE : ${Build.MANUFACTURER} ${Build.MODEL}\n")
    sb.append("ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
    sb.append("------------------------------------------\n")

    // ========== 插入最近的内存日志 ==========
    try {
        sb.append(LogBuffer.dump())
    } catch (e: Exception) { }
    // ========================================

    sb.append(stack)
    sb.append("\n\n")

    var wrote = false
    try {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, FILE_NAME)
        file.appendText(sb.toString())
        wrote = true
    } catch (e: Exception) {
        Log.e(TAG, "write to public Downloads failed", e)
    }

    if (!wrote) {
        try {
            val ctx = appContext
            if (ctx != null) {
                val file = File(ctx.filesDir, FILE_NAME)
                file.appendText(sb.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "write to filesDir failed", e)
        }
    }
}

    fun clear() {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
        }
    }
}