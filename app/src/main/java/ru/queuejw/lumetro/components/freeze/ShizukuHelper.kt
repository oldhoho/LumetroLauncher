package ru.queuejw.lumetro.components.freeze

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ShizukuHelper private constructor() {

    companion object {
        private const val TAG = "ShizukuHelper"
        @Volatile private var instance: ShizukuHelper? = null
        fun getInstance(): ShizukuHelper = instance ?: synchronized(this) { instance ?: ShizukuHelper().also { instance = it } }
    }

    private var context: Context? = null
    private var isAvailable = false
    private var hasPermission = false
    private var packageManagerService: Any? = null
    private var setAppEnabledSettingMethod: Method? = null
    private var getAppEnabledSettingMethod: Method? = null
    private var setPackagesSuspendedMethod: Method? = null
    private var arePackagesSuspendedMethod: Method? = null
    private var cachedUserId = 0

    fun init(ctx: Context) {
        context = ctx.applicationContext
        checkStatus()
    }

    fun checkStatus() {
        try {
            isAvailable = Shizuku.pingBinder()
            if (isAvailable) {
                hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                if (hasPermission) initReflection()
            }
        } catch (e: Exception) {
            isAvailable = false
        }
    }

    fun isReady(): Boolean = isAvailable && hasPermission

    private fun initReflection() {
        try {
            val ipmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterfaceMethod = ipmStub.getMethod("asInterface", IBinder::class.java)
            val binder = SystemServiceHelper.getSystemService("package")
            if (binder != null) {
                packageManagerService = asInterfaceMethod.invoke(null, ShizukuBinderWrapper(binder))
            }
            val ipmClass = Class.forName("android.content.pm.IPackageManager")
            setAppEnabledSettingMethod = ipmClass.getMethod("setApplicationEnabledSetting", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            getAppEnabledSettingMethod = ipmClass.getMethod("getApplicationEnabledSetting", String::class.java, Int::class.javaPrimitiveType)
            try { setPackagesSuspendedMethod = ipmClass.getMethod("setPackagesSuspended", arrayOf<String>()::class.java, Boolean::class.javaPrimitiveType) } catch (e: Exception) {}
            try { arePackagesSuspendedMethod = ipmClass.getMethod("arePackagesSuspended", arrayOf<String>()::class.java) } catch (e: Exception) {}
            cachedUserId = 0
        } catch (e: Exception) {
            Log.e(TAG, "反射初始化失败", e)
        }
    }

    fun freezeApp(pkg: String): Boolean {
        if (!isReady() || packageManagerService == null) return false
        try {
            setAppEnabledSettingMethod?.invoke(packageManagerService, pkg, 3, 0, cachedUserId, context?.packageName)
            return true
        } catch (e: Exception) {
            try {
                setPackagesSuspendedMethod?.invoke(packageManagerService, arrayOf(pkg), true)
                return true
            } catch (e2: Exception) {
                Log.e(TAG, "冻结失败: $pkg", e)
                return false
            }
        }
    }

    fun unfreezeApp(pkg: String): Boolean {
        if (!isReady() || packageManagerService == null) return false
        try {
            setAppEnabledSettingMethod?.invoke(packageManagerService, pkg, 0, 0, cachedUserId, context?.packageName)
            try { setPackagesSuspendedMethod?.invoke(packageManagerService, arrayOf(pkg), false) } catch (e: Exception) {}
            return true
        } catch (e: Exception) {
            Log.e(TAG, "解冻失败: $pkg", e)
            return false
        }
    }

    fun isAppFrozen(pkg: String): Boolean {
        if (!isReady() || packageManagerService == null) return false
        try {
            val state = getAppEnabledSettingMethod?.invoke(packageManagerService, pkg, cachedUserId) as? Int ?: 0
            if (state == 2 || state == 3) return true
            val result = arePackagesSuspendedMethod?.invoke(packageManagerService, arrayOf(pkg)) as? BooleanArray
            return result?.firstOrNull() == true
        } catch (e: Exception) {
            return false
        }
    }
}
