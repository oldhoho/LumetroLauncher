package ru.queuejw.lumetro.components.core

import android.content.Context
import android.view.View
import ru.queuejw.lumetro.components.freeform.WorkbenchManager

object SmartLauncher {

    fun launch(packageName: String?, context: Context, view: View? = null): Boolean {
        if (packageName == null) return false

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val freeformEnabled = prefs.getBoolean("freeform_enabled", false)

        // 如果 freeform 启用，尝试在工作台中打开
        return if (freeformEnabled && WorkbenchManager.isWorkbenchShowing()) {
            try {
                // 启动应用（通过 AppManager）
                AppManager.launchApp(packageName, context)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                AppManager.launchApp(packageName, context)
            }
        } else {
            AppManager.launchApp(packageName, context)
        }
    }
}
