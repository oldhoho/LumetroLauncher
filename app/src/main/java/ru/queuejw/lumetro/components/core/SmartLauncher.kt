package ru.queuejw.lumetro.components.core

import android.content.Context
import android.view.View
import ru.queuejw.lumetro.components.freeform.FreeformLauncher

object SmartLauncher {

    fun launch(packageName: String?, context: Context, view: View? = null): Boolean {
        if (packageName == null) return false

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val freeformEnabled = prefs.getBoolean("freeform_enabled", false)

        return if (freeformEnabled && FreeformLauncher.isFreeformSupported(context)) {
            try {
                FreeformLauncher.launchInFreeform(context, packageName, 0, 0, 0, 0, view)
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
