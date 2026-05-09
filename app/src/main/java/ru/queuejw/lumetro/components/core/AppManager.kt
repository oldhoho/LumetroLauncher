package ru.queuejw.lumetro.components.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ru.queuejw.lumetro.components.adapters.viewtypes.AppViewTypes
import ru.queuejw.lumetro.components.core.Lumetro.Companion.isOtherAppOpened
import ru.queuejw.lumetro.model.Alphabet
import ru.queuejw.lumetro.model.App
import java.text.Collator
import java.util.Locale

class AppManager() {

    private val collator = Collator.getInstance(Locale.CHINESE)

    fun getInstalledApps(
        context: Context,
        getOnlyApps: Boolean = false
    ): MutableList<App> {
        val packageManager = context.packageManager
        val tempList = ArrayList<App>()
        val intent = Intent(Intent.ACTION_MAIN, null).also {
            it.addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val intents = packageManager.queryIntentActivities(intent, 0).also {
            it.sortBy { item -> item.loadLabel(packageManager).toString() }
        }
        for (i in intents) {
            val item = App(
                i.loadLabel(packageManager).toString(),
                i.activityInfo.packageName,
                0
            )
            if (item.mPackage == context.packageName) continue
            tempList.add(item)
        }
        if (getOnlyApps) return tempList

        // 分离英文和中文，各自排序
        val english = mutableListOf<App>()
        val chinese = mutableListOf<App>()
        for (app in tempList) {
            val first = app.mName.firstOrNull()
            if (first in 'A'..'Z' || first in 'a'..'z') english.add(app)
            else chinese.add(app)
        }

        english.sortWith(compareBy(collator) { it.mName })
        chinese.sortWith(compareBy(collator) { it.mName })

        val result = mutableListOf<App>()

        // 英文字母分组
        val englishGrouped = english.groupBy { it.mName.first().uppercase() }.toSortedMap()
        englishGrouped.forEach { (letter, apps) ->
            result.add(App(letter, null, AppViewTypes.TYPE_HEADER.type))
            result.addAll(apps)
        }

        // 中文归为一组
        if (chinese.isNotEmpty()) {
            result.add(App("#", null, AppViewTypes.TYPE_HEADER.type))
            result.addAll(chinese)
        }

        return result
    }

    fun getAlphabet(apps: MutableList<App>): MutableList<Alphabet> {
        val result = mutableListOf<Alphabet>()
        apps.forEachIndexed { index, item ->
            if (item.viewType == AppViewTypes.TYPE_HEADER.type) {
                result.add(Alphabet(item.mName, index))
            }
        }
        return result
    }

    companion object {
        fun launchApp(mPackage: String?, context: Context): Boolean {
            if (mPackage != null) {
                try {
                    isOtherAppOpened = true
                    context.startActivity(context.packageManager.getLaunchIntentForPackage(mPackage))
                    return true
                } catch (e: PackageManager.NameNotFoundException) {
                    e.printStackTrace()
                    return false
                } catch (e: NullPointerException) {
                    e.printStackTrace()
                    return false
                }
            } else {
                return false
            }
        }
    }
}