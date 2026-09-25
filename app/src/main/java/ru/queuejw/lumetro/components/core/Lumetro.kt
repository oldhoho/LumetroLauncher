package ru.queuejw.lumetro.components.core

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import ru.queuejw.lumetro.components.freeform.util.CrashLogger

class Lumetro : Application() {

    companion object {
        private const val TAG = "Lumetro"
        lateinit var instance: Lumetro
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ========== 安装崩溃日志捕获 ==========
        try {
            CrashLogger.install(this)
        } catch (e: Exception) {
            Log.e(TAG, "CrashLogger install failed", e)
        }
        // =====================================

        try {
            // 检查 Shizuku 是否可用
            if (Shizuku.pingBinder()) {
                checkShizukuPermission()
            } else {
                // 等待 Shizuku 连接
                Shizuku.addBinderReceivedListener { 
                    checkShizukuPermission()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku init error", e)
        }
    }

    private fun checkShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Shizuku permission granted")
            } else {
                Log.d(TAG, "Shizuku permission not granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku permission check error", e)
        }
    }
}