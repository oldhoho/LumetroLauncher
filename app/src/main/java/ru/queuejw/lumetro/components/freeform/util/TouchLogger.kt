package ru.queuejw.lumetro.components.freeform.util

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 触摸/手势事件日志，输出到外部存储 Downloads/touch_debug.log
 */
object TouchLogger {

    private const val TAG = "TouchDebug"
    private const val FILE_NAME = "touch_debug.log"

    @Volatile
    private var enabled: Boolean = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            log("=== TouchLogger enabled ===")
        }
    }

    fun isEnabled(): Boolean = enabled

    fun log(message: String) {
        if (!enabled) return
        val time = timeFormat.format(Date())
        val line = "[$time] $message"

        Log.d(TAG, line)

        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "write log failed", e)
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