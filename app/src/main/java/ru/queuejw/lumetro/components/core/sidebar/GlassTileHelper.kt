package ru.queuejw.lumetro.components.core.sidebar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View

object GlassTileHelper {
    private const val BLUR_RADIUS = 60f
    private const val TINT = 0x1AFFFFFF
    private const val BORDER = 0x40FFFFFF
    private const val GLOW = 0x10FFFFFF

    fun applyToTile(view: View, bitmap: Bitmap) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(copy).drawColor(TINT)
        view.background = BitmapDrawable(view.resources, copy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP))
        }
        applyGlassBorder(view, 12f)
    }

    fun applyGlassShell(view: View, cornerRadius: Float) {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setRenderEffect(null)
        applyGlassBorder(view, cornerRadius)
    }

    private fun applyGlassBorder(view: View, radius: Float) {
        val border = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(1, BORDER.toInt())
            setCornerRadius(radius)
        }
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(3, GLOW.toInt())
            setCornerRadius(radius)
        }
        val highlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(radius)
            colors = intArrayOf(0x18FFFFFF.toInt(), 0x00FFFFFF)
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        view.foreground = LayerDrawable(arrayOf(glow, highlight, border))
    }
}
