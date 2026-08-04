package ru.queuejw.lumetro.components.freeform

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import ru.queuejw.lumetro.components.freeform.util.U
import ru.queuejw.lumetro.components.freeform.util.ApplicationType
import ru.queuejw.lumetro.components.freeform.helper.FreeformHackHelper

object FreeformLauncher {

    private var isFreeformActive = false

    /**
     * 以小窗模式启动应用
     * @param context Context
     * @param packageName 应用包名
     * @param left 窗口左侧位置
     * @param top 窗口顶部位置
     * @param right 窗口右侧位置
     * @param bottom 窗口底部位置
     * @param view 动画锚点（可选）
     */
    fun launchInFreeform(
        context: Context,
        packageName: String,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        view: View? = null
    ): Boolean {
        try {
            // 确保自由窗口已激活
            if (!FreeformHackHelper.getInstance().isFreeformHackActive()) {
                activateFreeformMode(context)
                Thread.sleep(200)
            }

            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return false

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val options = if (right > 0 && bottom > 0) {
                U.getActivityOptionsBundle(
                    context,
                    ApplicationType.APP_PORTRAIT,
                    view,
                    left, top, right, bottom
                )
            } else {
                U.getActivityOptionsBundle(
                    context,
                    ApplicationType.APP_PORTRAIT,
                    view
                )
            }

            if (options != null) {
                context.startActivity(launchIntent, options)
            } else {
                context.startActivity(launchIntent)
            }

            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 在屏幕中央以小窗启动应用（默认大小：屏幕宽度的60%）
     */
    fun launchInCenter(context: Context, packageName: String): Boolean {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val windowWidth = (screenWidth * 0.6f).toInt()
        val windowHeight = (screenHeight * 0.7f).toInt()
        val left = (screenWidth - windowWidth) / 2
        val top = (screenHeight - windowHeight) / 2
        val right = left + windowWidth
        val bottom = top + windowHeight

        return launchInFreeform(context, packageName, left, top, right, bottom)
    }

    /**
     * 在屏幕左侧以小窗启动应用（工作台风格）
     */
    fun launchInLeft(context: Context, packageName: String): Boolean {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val margin = (screenWidth * 0.02f).toInt()
        val windowWidth = (screenWidth * 0.76f).toInt()
        val windowHeight = (screenHeight * 0.82f).toInt()
        val left = margin
        val top = margin * 2
        val right = margin + windowWidth
        val bottom = margin * 2 + windowHeight

        return launchInFreeform(context, packageName, left, top, right, bottom)
    }

    private fun activateFreeformMode(context: Context) {
        if (U.canDrawOverlays(context) && U.hasFreeformSupport(context)) {
            U.startFreeformHack(context, true)
            isFreeformActive = true
        }
    }

    fun isFreeformSupported(context: Context): Boolean {
        return U.canEnableFreeform(context) && U.hasFreeformSupport(context)
    }

    fun isFreeformActive(): Boolean = isFreeformActive
}
