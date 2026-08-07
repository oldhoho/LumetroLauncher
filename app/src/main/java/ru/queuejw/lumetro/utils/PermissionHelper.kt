package ru.queuejw.lumetro.utils

import android.Manifest
import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object PermissionHelper {

    // 需要动态请求的危险权限
    val DANGEROUS_PERMISSIONS: List<String>
        get() {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // Android 10 以下需要存储权限
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            // 电话状态权限（用于挂断电话后恢复工作台）
            list.add(Manifest.permission.READ_PHONE_STATE)
            return list
        }

    // 特殊权限列表（需要跳转系统设置）
    val SPECIAL_PERMISSIONS = listOf(
        SpecialPermission.SYSTEM_ALERT_WINDOW,
        SpecialPermission.PACKAGE_USAGE_STATS,
        SpecialPermission.MANAGE_EXTERNAL_STORAGE
    )

    enum class SpecialPermission {
        SYSTEM_ALERT_WINDOW,
        PACKAGE_USAGE_STATS,
        MANAGE_EXTERNAL_STORAGE
    }

    // ============ 危险权限 ============

    fun hasAllDangerousPermissions(context: Context): Boolean {
        return DANGEROUS_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestDangerousPermissions(activity: FragmentActivity, requestCode: Int) {
        val denied = DANGEROUS_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, denied.toTypedArray(), requestCode)
        }
    }

    // ============ 特殊权限 ============

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) return false
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - 1000 * 60,
            now
        )
        return stats != null && stats.isNotEmpty()
    }

    fun hasManageStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun hasAllSpecialPermissions(context: Context): Boolean {
        return hasOverlayPermission(context) &&
                hasUsageStatsPermission(context) &&
                hasManageStoragePermission(context)
    }

    fun requestSpecialPermission(activity: Activity, permission: SpecialPermission) {
        val intent = when (permission) {
            SpecialPermission.SYSTEM_ALERT_WINDOW -> {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            }
            SpecialPermission.PACKAGE_USAGE_STATS -> {
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            SpecialPermission.MANAGE_EXTERNAL_STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${activity.packageName}"))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${activity.packageName}"))
                }
            }
        }
        activity.startActivity(intent)
    }

    fun openAppSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    // ============ 检查是否所有权限都就绪 ============

    fun hasAllPermissions(context: Context): Boolean {
        return hasAllDangerousPermissions(context) &&
                hasAllSpecialPermissions(context)
    }
}
