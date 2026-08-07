package ru.queuejw.lumetro.utils

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WorkbenchLogger {
    private const val LOG_FILE_NAME = "workbench_log.txt"
    private const val MAX_LOG_SIZE = 1024 * 1024 // 1MB
    
    private var logFile: File? = null
    private var isEnabled = true
    
    fun init(context: Context) {
        try {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            // 如果日志文件过大，清空
            if (logFile?.exists() == true && logFile?.length() ?: 0 > MAX_LOG_SIZE) {
                logFile?.delete()
            }
            log("Logger", "=== Workbench Logger initialized ===")
        } catch (e: Exception) {
            // 初始化失败忽略
        }
    }
    
    fun log(tag: String, message: String) {
        if (!isEnabled) return
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logLine = "[$timestamp] [$tag] $message\n"
            
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.append(logLine)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            // 日志写入失败不影响主功能
        }
    }
    
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        try {
            val errorMsg = if (throwable != null) {
                "$message | Error: ${throwable.message}\n${throwable.stackTraceToString()}"
            } else {
                message
            }
            log(tag, "ERROR: $errorMsg")
        } catch (e: Exception) {
            // 忽略
        }
    }
    
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }
    
    fun getLogContent(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else "日志文件不存在"
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }
    
    fun clearLog(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
            file.createNewFile()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
