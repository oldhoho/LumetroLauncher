package ru.queuejw.lumetro.components.ui.keycap

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import ru.queuejw.lumetro.R

/**
 * 拟物机械键盘键帽背景：底座(厚度) + 顶面渐变 + 顶部高光 + 金属边框贴图 + 描边；
 * 按下态顶面下沉，制造实体按键的按压感。可选 [tint] 叠加一层半透明色用于状态高亮。
 */
object KeycapHelper {

    fun create(
        context: Context,
        cornerDp: Float = 10f,
        depthDp: Float = 3f,
        tint: Int? = null
    ): Drawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), keycap(context, cornerDp, depthDp, tint, true))
            addState(intArrayOf(), keycap(context, cornerDp, depthDp, tint, false))
        }
    }

    private fun keycap(
        context: Context,
        cornerDp: Float,
        depthDp: Float,
        tint: Int?,
        pressed: Boolean
    ): LayerDrawable {
        val radius = dp(context, cornerDp).toFloat()
        val depth = dp(context, depthDp)

        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0xFF0A0A0A.toInt())
        }

        val face = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = if (pressed) intArrayOf(0xFF1C1C1C.toInt(), 0xFF151515.toInt())
            else intArrayOf(0xFF3E3E3E.toInt(), 0xFF262626.toInt())
            setStroke(dp(context, 1f), 0x66000000)
        }

        val highlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(if (pressed) 0x10FFFFFF else 0x24FFFFFF, 0x00FFFFFF)
        }

        val content = mutableListOf<Drawable>(base, face, highlight)
        if (tint != null) {
            content.add(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(tint)
                }
            )
        }

        // 金属边框贴图作为最顶层（中间透明，露出键帽面）
        ContextCompat.getDrawable(context, R.drawable.icon_frame)?.let { content.add(it) }

        val faceTop = if (pressed) depth else 0
        val faceBottom = if (pressed) 0 else depth
        val layers = content.toTypedArray()

        return LayerDrawable(layers).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, 0, faceTop, 0, faceBottom)
            setLayerInset(2, 0, faceTop, 0, faceBottom)
            if (tint != null) setLayerInset(3, 0, faceTop, 0, faceBottom)
        }
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
}
