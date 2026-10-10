package ru.queuejw.lumetro.components.core.icons

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.SparseArrayCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Locale
import java.util.Random

class IconPackManager(val context: Context) {

    inner class IconPack {
        var packageName: String? = null
        var name: String? = null
        private var mLoaded = false
        private val mPackagesDrawables = HashMap<String?, String?>()
        private val mBackImages: SparseArrayCompat<Bitmap> = SparseArrayCompat()
        private var mMaskImage: Bitmap? = null
        private var mFrontImage: Bitmap? = null
        private var mFactor = 1.0f

        private var iconPackRes: Resources? = null

        @SuppressLint("DiscouragedApi")
        private fun load() {
            runCatching {
                val parser: XmlPullParser? = run {
                    iconPackRes = context.packageManager.getResourcesForApplication(packageName!!)
                    val appFilterId = iconPackRes!!.getIdentifier("appfilter", "xml", packageName)
                    if (appFilterId > 0) {
                        iconPackRes!!.getXml(appFilterId)
                    } else {
                        runCatching {
                            val appFilterStream = iconPackRes!!.assets.open("appfilter.xml")
                            val factory = XmlPullParserFactory.newInstance()
                            factory.isNamespaceAware = true
                            val p = factory.newPullParser()
                            p.setInput(appFilterStream, "utf-8")
                            p
                        }.getOrNull()
                    }
                }
                if (parser != null) {
                    val xpp = parser
                    var eventType = xpp.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            when (xpp.name) {
                                "iconback" -> {
                                    for (i in 0 until xpp.attributeCount) {
                                        if (xpp.getAttributeName(i).startsWith("img")) {
                                            val drawableName = xpp.getAttributeValue(i)
                                            val iconback = loadBitmap(drawableName)
                                            if (iconback != null) mBackImages.append(
                                                iconback.hashCode(),
                                                iconback
                                            )
                                        }
                                    }
                                }

                                "iconmask" -> {
                                    if (xpp.attributeCount > 0 && xpp.getAttributeName(0) == "img1") {
                                        val drawableName = xpp.getAttributeValue(0)
                                        mMaskImage = loadBitmap(drawableName)
                                    }
                                }

                                "iconupon" -> {
                                    if (xpp.attributeCount > 0 && xpp.getAttributeName(0) == "img1") {
                                        val drawableName = xpp.getAttributeValue(0)
                                        mFrontImage = loadBitmap(drawableName)
                                    }
                                }

                                "scale" -> {
                                    if (xpp.attributeCount > 0 && xpp.getAttributeName(0) == "factor") {
                                        mFactor = xpp.getAttributeValue(0).toFloat()
                                    }
                                }

                                "item" -> {
                                    var componentName: String? = null
                                    var drawableName: String? = null
                                    for (i in 0 until xpp.attributeCount) {
                                        if (xpp.getAttributeName(i) == "component") {
                                            componentName = xpp.getAttributeValue(i)
                                        } else if (xpp.getAttributeName(i) == "drawable") {
                                            drawableName = xpp.getAttributeValue(i)
                                        }
                                    }
                                    if (!mPackagesDrawables.containsKey(componentName)) {
                                        mPackagesDrawables[componentName] = drawableName
                                    }
                                }
                            }
                        }
                        eventType = xpp.next()
                    }
                }
                mLoaded = true
            }.getOrElse {
                it.printStackTrace()
            }
        }

        @SuppressLint("DiscouragedApi")
        private fun loadBitmap(drawableName: String): Bitmap? {
            val id = iconPackRes!!.getIdentifier(drawableName, "drawable", packageName)
            if (id > 0) {
                val bitmap = ResourcesCompat.getDrawable(iconPackRes!!, id, null)
                if (bitmap is BitmapDrawable) return bitmap.bitmap
            }
            return null
        }

        @SuppressLint("DiscouragedApi")
        fun loadDrawable(drawableName: String): Drawable? {
            val id = iconPackRes!!.getIdentifier(drawableName, "drawable", packageName)
            return if (id > 0) ResourcesCompat.getDrawable(iconPackRes!!, id, null) else null
        }

        /** 按包名兜底匹配：找 appfilter.xml 里所有以 ComponentInfo{包名/ 开头的 key */
        private fun fallbackDrawableByPackage(appPackageName: String): Drawable? {
            val pkgPrefix = "ComponentInfo{$appPackageName/"
            for ((key, value) in mPackagesDrawables) {
                if (key != null && key.startsWith(pkgPrefix)) {
                    val d = loadDrawable(value ?: continue)
                    if (d != null) return d
                }
            }
            return null
        }

        /** 按包名兜底匹配：返回 Bitmap */
        private fun fallbackBitmapByPackage(appPackageName: String): Bitmap? {
            val pkgPrefix = "ComponentInfo{$appPackageName/"
            for ((key, value) in mPackagesDrawables) {
                if (key != null && key.startsWith(pkgPrefix)) {
                    val bmp = loadBitmap(value ?: continue)
                    if (bmp != null) return bmp
                }
            }
            return null
        }

        @SuppressLint("DiscouragedApi")
        fun getDrawableIconForPackage(
            appPackageName: String?,
            defaultDrawable: Drawable?
        ): Drawable? {
            if (!mLoaded) load()
            if (appPackageName == null) return defaultDrawable

            val launchIntent = context.packageManager.getLaunchIntentForPackage(appPackageName)
            val componentName: String?

            if (launchIntent != null) {
                componentName = launchIntent.component?.toString()
            } else {
                // 冻结/停用应用：launchIntent == null，直接按包名兜底
                val fallback = fallbackDrawableByPackage(appPackageName)
                if (fallback != null) return fallback
                return defaultDrawable
            }

            // 1. 精确匹配 ComponentInfo
            val drawable = mPackagesDrawables[componentName]
            if (drawable != null) {
                return loadDrawable(drawable)
            }

            // 2. 用 component 文件名找（原逻辑）
            if (componentName != null) {
                val start = componentName.indexOf("{") + 1
                val end = componentName.indexOf("}", start)
                if (end > start) {
                    val candidate = componentName.substring(start, end)
                        .lowercase(Locale.getDefault())
                        .replace(".", "_").replace("/", "_")
                    if (iconPackRes!!.getIdentifier(candidate, "drawable", packageName) > 0) {
                        return loadDrawable(candidate)
                    }
                }
            }

            // 3. 按包名兜底匹配（vivo 壳 Activity 场景）
            val fallback = fallbackDrawableByPackage(appPackageName)
            if (fallback != null) return fallback

            return defaultDrawable
        }

        @SuppressLint("DiscouragedApi")
        @Suppress("unused")
        fun getIconForPackage(appPackageName: String?, defaultBitmap: Bitmap): Bitmap? {
            if (!mLoaded) load()
            if (appPackageName == null) return generateBitmap(defaultBitmap)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(appPackageName)
            val componentName: String?

            if (launchIntent != null) {
                componentName = launchIntent.component?.toString()
            } else {
                val fallback = fallbackBitmapByPackage(appPackageName)
                if (fallback != null) return fallback
                return generateBitmap(defaultBitmap)
            }

            // 1. 精确匹配
            val drawable = mPackagesDrawables[componentName]
            if (drawable != null) {
                val bmp = loadBitmap(drawable)
                return bmp ?: generateBitmap(defaultBitmap)
            }

            // 2. 用 component 文件名找
            if (componentName != null) {
                val start = componentName.indexOf("{") + 1
                val end = componentName.indexOf("}", start)
                if (end > start) {
                    val candidate = componentName.substring(start, end)
                        .lowercase(Locale.getDefault())
                        .replace(".", "_").replace("/", "_")
                    if (iconPackRes!!.getIdentifier(candidate, "drawable", packageName) > 0) {
                        return loadBitmap(candidate) ?: generateBitmap(defaultBitmap)
                    }
                }
            }

            // 3. 按包名兜底匹配
            val fallback = fallbackBitmapByPackage(appPackageName)
            if (fallback != null) return fallback

            return generateBitmap(defaultBitmap)
        }

        private fun generateBitmap(defaultBitmap: Bitmap): Bitmap {
            if (mBackImages.size() == 0) return defaultBitmap
            val r = Random()
            val backImageInd = r.nextInt(mBackImages.size())
            val backImage = mBackImages[backImageInd]
            val w = backImage!!.width
            val h = backImage.height

            val result = createBitmap(w, h)
            val mCanvas = Canvas(result)

            mCanvas.drawBitmap(backImage, 0f, 0f, null)

            val scaledBitmap: Bitmap =
                if (defaultBitmap.width > w || defaultBitmap.height > h) {
                    defaultBitmap.scale((w * mFactor).toInt(), (h * mFactor).toInt(), false)
                } else {
                    Bitmap.createBitmap(defaultBitmap)
                }
            val mutableMask = createBitmap(w, h)
            val maskCanvas = Canvas(mutableMask)
            if (mMaskImage != null) {
                maskCanvas.drawBitmap(mMaskImage!!, 0f, 0f, Paint())

                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                mCanvas.apply {
                    drawBitmap(
                        scaledBitmap,
                        (w - scaledBitmap.width).toFloat() / 2,
                        (h - scaledBitmap.height).toFloat() / 2,
                        null
                    )
                    drawBitmap(mutableMask, 0f, 0f, paint)
                }
                paint.xfermode = null
            } else {
                maskCanvas.drawBitmap(backImage, 0f, 0f, Paint())

                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                mCanvas.apply {
                    drawBitmap(
                        scaledBitmap,
                        (w - scaledBitmap.width).toFloat() / 2,
                        (h - scaledBitmap.height).toFloat() / 2,
                        null
                    )
                    drawBitmap(mutableMask, 0f, 0f, paint)
                }
                paint.xfermode = null
            }
            if (mFrontImage != null) mCanvas.drawBitmap(mFrontImage!!, 0f, 0f, null)
            return result
        }

        override fun equals(other: Any?): Boolean {
            return if (other is IconPack) other.packageName == packageName else false
        }

        override fun hashCode(): Int {
            var result = packageName?.hashCode() ?: 0
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + mLoaded.hashCode()
            result = 31 * result + mPackagesDrawables.hashCode()
            result = 31 * result + mBackImages.hashCode()
            result = 31 * result + (mMaskImage?.hashCode() ?: 0)
            result = 31 * result + (mFrontImage?.hashCode() ?: 0)
            result = 31 * result + mFactor.hashCode()
            result = 31 * result + (iconPackRes?.hashCode() ?: 0)
            return result
        }
    }

    private var iconPacks: ArrayList<IconPack>? = null

    fun getAvailableIconPacks(forceReload: Boolean): ArrayList<IconPack> {
        if (iconPacks == null || forceReload) {
            iconPacks = ArrayList()

            val pm = context.packageManager
            val adwLauncherThemes = pm.queryIntentActivities(
                Intent("org.adw.launcher.THEMES"),
                PackageManager.GET_META_DATA
            )
            val goLauncherThemes = pm.queryIntentActivities(
                Intent("com.gau.go.launcherex.theme"),
                PackageManager.GET_META_DATA
            )

            val resolveInfo: MutableList<ResolveInfo> = ArrayList(adwLauncherThemes)
            resolveInfo.addAll(goLauncherThemes)
            for (ri in resolveInfo) {
                val ip = IconPack()
                ip.packageName = ri.activityInfo.packageName
                var ai: ApplicationInfo
                runCatching {
                    ai = pm.getApplicationInfo(ip.packageName!!, PackageManager.GET_META_DATA)
                    ip.name = pm.getApplicationLabel(ai).toString()
                    if (!iconPacks!!.contains(ip)) iconPacks!!.add(ip)
                }
            }
        }
        return iconPacks!!
    }

    fun getIconPackWithName(packageName: String?): IconPack? {
        val targetPack = IconPack().apply {
            this.packageName = packageName
        }
        if (iconPacks == null) iconPacks = getAvailableIconPacks(true)
        if (iconPacks!!.contains(targetPack)) {
            val ai: ApplicationInfo
            runCatching {
                val pm = context.packageManager
                ai = pm.getApplicationInfo(targetPack.packageName!!, PackageManager.GET_META_DATA)
                targetPack.name = pm.getApplicationLabel(ai).toString()
                return targetPack
            }
        }
        return null
    }
}