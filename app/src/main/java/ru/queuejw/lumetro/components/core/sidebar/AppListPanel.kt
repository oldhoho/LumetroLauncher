package ru.queuejw.lumetro.components.core.sidebar

import ru.queuejw.lumetro.components.freeform.util.LogBuffer
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.freeform.WorkbenchManager
import ru.queuejw.lumetro.components.freeform.WorkbenchSettingsActivity
import ru.queuejw.lumetro.model.App
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppListPanel(
    private val context: Context,
    private val iconLoader: IconLoader,
    private val coroutineScope: CoroutineScope,
    private val onHidePanel: () -> Unit,
    private val onRefreshTiles: () -> Unit,
    private val onShowSettings: () -> Unit,
    private val onShowFreezeDialog: () -> Unit,
    private val onPinApp: (App) -> Unit,
    private val onRefreshApps: () -> Unit,
    private val onAppsChanged: (List<App>) -> Unit,
    private val onPageChangeRequested: ((Int) -> Unit)? = null,
    private val onOpenNotificationCenter: (() -> Unit)? = null,
    private val onOpenControlCenter: (() -> Unit)? = null,
    private val onLockRequested: (() -> Unit)? = null,
    private val onUnlockRequested: (() -> Unit)? = null,
    private val onKeyLongPress: ((String) -> Unit)? = null,
    private val overlayContext: Context? = null,
    private val onHideWorkbench: (() -> Unit)? = null
) {

    private val perfLogEnabled = false

    private val keyBindings = KeyboardKeyBindings.getInstance(context)

    private val effectiveOverlayContext: Context = overlayContext ?: context

    private val windowManager: WindowManager =
        effectiveOverlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun perfLog(message: String) {
    if (!perfLogEnabled) return
    // 只写内存缓冲，不写磁盘
    LogBuffer.add("AppListPanel", message)
    Log.d("AppListPanel_Perf", message)
}

    private var searchEditTextRef: WeakReference<TextView>? = null
    private var lockOverlay: LockOverlay? = null

    private var bindingsDialogView: View? = null
    private var bindingsDialogParams: WindowManager.LayoutParams? = null
    private var bindingsContentHolder: FrameLayout? = null
    private var bindingsListSnapshot: Map<String, KeyboardKeyBindings.Binding> = emptyMap()

    private var keyboardContainerRef: WeakReference<ViewGroup>? = null
    private var keyboardRootRef: WeakReference<ViewGroup>? = null

    private var allApps = emptyList<App>()
    private val iconCache = mutableMapOf<String, Bitmap>()

    private var loadIconJob: Job? = null
    private var loadDataJob: Job? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var t9Input = StringBuilder()
    private var isT9Mode = false

    private val clearHandler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    private val t9ReverseMap = mapOf(
        'A' to '2', 'B' to '2', 'C' to '2',
        'D' to '3', 'E' to '3', 'F' to '3',
        'G' to '4', 'H' to '4', 'I' to '4',
        'J' to '5', 'K' to '5', 'L' to '5',
        'M' to '6', 'N' to '6', 'O' to '6',
        'P' to '7', 'Q' to '7', 'R' to '7', 'S' to '7',
        'T' to '8', 'U' to '8', 'V' to '8',
        'W' to '9', 'X' to '9', 'Y' to '9', 'Z' to '9',
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    private val polyphoneMap = mapOf(
        "行" to "hang", "重" to "chong", "长" to "chang",
        "朝" to "chao", "会" to "hui", "都" to "dou",
        "间" to "jian", "调" to "diao", "弹" to "dan",
        "藏" to "cang", "单" to "dan", "区" to "qu",
        "仇" to "chou", "查" to "cha", "解" to "jie",
        "朴" to "piao", "翟" to "zhai", "曾" to "zeng",
        "万" to "wan", "尉" to "wei", "华" to "hua",
        "燕" to "yan", "冼" to "xian", "乐" to "le",
        "校" to "xiao", "便" to "bian", "省" to "sheng",
        "传" to "chuan", "还" to "hai", "空" to "kong",
        "落" to "luo", "蔓" to "man", "蒙" to "meng",
        "难" to "nan", "宁" to "ning", "片" to "pian",
        "强" to "qiang", "悄" to "qiao", "曲" to "qu",
        "少" to "shao", "舍" to "she", "什" to "shen",
        "似" to "si", "为" to "wei", "系" to "xi",
        "兴" to "xing", "要" to "yao", "应" to "ying",
        "正" to "zheng", "种" to "zhong", "转" to "zhuan",
        "地" to "di", "得" to "de", "的" to "de",
        "了" to "le", "着" to "zhe", "中" to "zhong",
        "国" to "guo", "银" to "yin", "发" to "fa",
        "干" to "gan", "给" to "gei", "更" to "geng",
        "好" to "hao", "和" to "he", "将" to "jiang",
        "觉" to "jue", "看" to "kan", "可" to "ke",
        "没" to "mei", "那" to "na", "哪" to "na",
        "期" to "qi", "亲" to "qin", "去" to "qu",
        "上" to "shang", "说" to "shuo", "同" to "tong",
        "下" to "xia", "先" to "xian", "小" to "xiao",
        "些" to "xie", "心" to "xin", "新" to "xin",
        "样" to "yang", "一" to "yi", "已" to "yi",
        "以" to "yi", "意" to "yi", "用" to "yong",
        "有" to "you", "又" to "you", "在" to "zai",
        "再" to "zai", "早" to "zao", "怎" to "zen",
        "这" to "zhe", "只" to "zhi", "知" to "zhi",
        "走" to "zou"
    )

    data class SearchResult(
        val app: App,
        val score: Int,
        val matchType: String
    )

    private data class PinyinCache(
        val fullPinyin: String,
        val pinyinChars: List<String>,
        val firstLetters: String,
        val fullEncoded: String,
        val firstEncoded: String
    )

    private val MAX_CACHE_SIZE = 500
    private val pinyinCache = object : LinkedHashMap<String, PinyinCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PinyinCache>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val firstLetterIndex = mutableMapOf<String, MutableList<App>>()
    private val fullPinyinIndex = mutableMapOf<String, MutableList<App>>()
    private val namePrefixIndex = mutableMapOf<String, MutableList<App>>()

    private val MIN_SEARCH_PREFIX_LENGTH = 2

    private val clickHistory = mutableMapOf<String, Int>()
    private val clickPreferences by lazy {
        context.getSharedPreferences("app_click_history", Context.MODE_PRIVATE)
    }

    private data class KeyData(val label: String, val letters: String)

    private val keyData = listOf(
        listOf(
            KeyData("", ""),
            KeyData("2", "ABC"),
            KeyData("3", "DEF")
        ),
        listOf(
            KeyData("4", "GHI"),
            KeyData("5", "JKL"),
            KeyData("6", "MNO")
        ),
        listOf(
            KeyData("7", "PQRS"),
            KeyData("8", "TUV"),
            KeyData("9", "WXYZ")
        )
    )

    private val pinyinOutputFormat by lazy {
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
            vCharType = HanyuPinyinVCharType.WITH_V
        }
    }

    private fun getPinyinChars(text: String): List<String> {
        val result = mutableListOf<String>()
        for (char in text) {
            val charString = char.toString()
            val mappedPinyin = polyphoneMap[charString]
            if (mappedPinyin != null) {
                result.add(mappedPinyin)
                continue
            }
            val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(char, pinyinOutputFormat)
            if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                result.add(pinyinArray[0])
            } else {
                result.add(charString)
            }
        }
        return result
    }

    private fun encodeToT9(text: String): String {
        return text.map { char ->
            t9ReverseMap[char] ?: char
        }.joinToString("")
    }

    private fun extractEnglishParts(text: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        for (char in text) {
            if (char.isLetter() && char.code <= 127) {
                current.append(char)
            } else {
                if (current.isNotEmpty()) {
                    parts.add(current.toString())
                    current.clear()
                }
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    private fun getPinyinCache(app: App): PinyinCache {
        val name = app.mName
        return pinyinCache[name] ?: run {
            val pinyinChars = getPinyinChars(name)
            val fullPinyin = pinyinChars.joinToString("")
            val firstLetters = pinyinChars.joinToString("") { charPinyin ->
                charPinyin.firstOrNull()?.toString() ?: ""
            }
            val fullEncoded = encodeToT9(fullPinyin)
            val firstEncoded = encodeToT9(firstLetters)
            val cache = PinyinCache(fullPinyin, pinyinChars, firstLetters, fullEncoded, firstEncoded)
            pinyinCache[name] = cache
            cache
        }
    }

    private fun buildSearchIndex(apps: List<App>) {
        firstLetterIndex.clear()
        fullPinyinIndex.clear()
        namePrefixIndex.clear()
        for (app in apps) {
            val cache = getPinyinCache(app)
            firstLetterIndex.getOrPut(cache.firstEncoded) { mutableListOf() }.add(app)
            fullPinyinIndex.getOrPut(cache.fullEncoded) { mutableListOf() }.add(app)
            val name = app.mName
            if (name.isNotEmpty()) {
                for (i in MIN_SEARCH_PREFIX_LENGTH..name.length) {
                    val prefix = name.substring(0, i)
                    namePrefixIndex.getOrPut(prefix) { mutableListOf() }.add(app)
                }
            }
        }
    }

    fun recordAppClick(packageName: String) {
        val current = clickHistory[packageName] ?: 0
        clickHistory[packageName] = current + 1
        clickPreferences.edit().putInt(packageName, current + 1).apply()
    }

    private fun getClickCount(packageName: String): Int {
        return clickHistory[packageName] ?: clickPreferences.getInt(packageName, 0)
    }

    private fun loadClickHistory() {
        val visibleApps = getVisibleApps()
        visibleApps.forEach { app ->
            app.mPackage?.let {
                val count = clickPreferences.getInt(it, 0)
                if (count > 0) clickHistory[it] = count
            }
        }
    }

    private fun getVisibleApps(): List<App> {
        val hidden = FreezeManager.getHiddenList(context).toSet()
        return allApps.filter { it.mPackage !in hidden }
    }

    private fun filterAppsByT9(input: String) {
        if (input.isEmpty()) {
            val visibleApps = getVisibleApps()
            onAppsChanged(visibleApps.sortedBy { it.mName })
            return
        }

        val visibleApps = getVisibleApps()

        if (visibleApps.isEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val manager = WorkbenchManager.getInstance()
                    val apps = manager?.getCachedApps() ?: emptyList()
                    withContext(Dispatchers.Main) {
                        if (apps.isNotEmpty()) {
                            allApps = apps
                            val visible = getVisibleApps()
                            pinyinCache.clear()
                            for (app in visible) getPinyinCache(app)
                            buildSearchIndex(visible)
                            filterAppsByT9(input)
                        } else {
                            Toast.makeText(context, "无法加载应用列表", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                }
            }
            return
        }

        if (firstLetterIndex.isEmpty() && visibleApps.isNotEmpty()) {
            pinyinCache.clear()
            for (app in visibleApps) getPinyinCache(app)
            buildSearchIndex(visibleApps)
        }

        if (clickHistory.isEmpty()) loadClickHistory()

        val candidates = mutableSetOf<App>()
        firstLetterIndex[input]?.let { candidates.addAll(it) }
        for (entry in fullPinyinIndex.entries) {
            if (entry.key.contains(input)) candidates.addAll(entry.value)
        }
        if (input.length >= MIN_SEARCH_PREFIX_LENGTH) {
            namePrefixIndex[input]?.let { candidates.addAll(it) }
        }
        for (app in visibleApps) {
            val englishParts = extractEnglishParts(app.mName)
            for (part in englishParts) {
                if (part.length >= 2) {
                    val encoded = encodeToT9(part.lowercase())
                    if (encoded.contains(input) || encoded == input) {
                        candidates.add(app)
                        break
                    }
                }
            }
        }
        for (app in visibleApps) {
            val cache = getPinyinCache(app)
            if (cache.fullEncoded.contains(input)) candidates.add(app)
        }
        if (candidates.size < 5) {
            for (app in visibleApps) {
                val cache = getPinyinCache(app)
                if (cache.firstEncoded.contains(input) ||
                    cache.fullEncoded.contains(input) ||
                    app.mName.contains(input)) {
                    candidates.add(app)
                }
            }
        }

        val results = candidates.mapNotNull { app ->
            val cache = getPinyinCache(app)
            val name = app.mName
            var score = 0

            if (cache.firstEncoded == input) {
                score = 1000
            } else if (cache.firstEncoded.contains(input)) {
                val index = cache.firstEncoded.indexOf(input)
                score = 800 + maxOf(50 - (index * 5), 0)
            } else {
                val englishParts = extractEnglishParts(name)
                var matchedEnglish = false
                for (part in englishParts) {
                    if (part.length >= 2) {
                        val encoded = encodeToT9(part.lowercase())
                        if (encoded == input) {
                            score = 700
                            matchedEnglish = true
                            break
                        } else if (encoded.contains(input)) {
                            score = 600 + (input.length * 5)
                            matchedEnglish = true
                            break
                        }
                    }
                }
                if (!matchedEnglish) {
                    if (cache.fullEncoded.startsWith(input)) score = 300
                    else if (cache.fullEncoded.contains(input)) score = 200
                    else if (name.contains(input)) score = 150
                }
            }

            if (score > 0) {
                val clickCount = getClickCount(app.mPackage ?: "")
                SearchResult(app, score + clickCount * 5, "")
            } else null
        }

        val matchedApps = results.sortedByDescending { it.score }.take(50).map { it.app }

        if (matchedApps.isEmpty()) {
            onAppsChanged(visibleApps.sortedBy { it.mName })
        } else {
            onAppsChanged(matchedApps)
        }
    }

    fun createView(): View {
        val rootContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            isClickable = true
            isFocusable = true
        }

        keyboardRootRef = WeakReference(rootContainer)

        val keyboardContainer = createKeyboardView()
        rootContainer.addView(keyboardContainer)

        return rootContainer
    }

    private fun createKeyboardView(): View {
    val container = GestureKeyboardContainer(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            200.dpToPx()
        )
        setBackgroundColor(Color.parseColor("#FF1A1A1A"))
        setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 8.dpToPx())
        gravity = Gravity.CENTER

        onSwipeUp = { onPageChangeRequested?.invoke(1) }
        onSwipeDown = { onPageChangeRequested?.invoke(-1) }
    }

    // 第一行：搜索显示 | 1 | 2ABC | 3DEF | 退格
    val row1 = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        gravity = Gravity.CENTER
        setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
    }
    row1.addView(createSearchDisplayKey())
    row1.addView(createDigitKey("1", ""))
    row1.addView(createDigitKey("2", "ABC"))
    row1.addView(createDigitKey("3", "DEF"))
    row1.addView(createSpecialKey("退格", isBackspace = true))
    container.addView(row1)

    // 第二行：设置 | 4GHI | 5JKL | 6MNO | 通知
    val row2 = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        gravity = Gravity.CENTER
        setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
    }
    row2.addView(createSettingsKey())
    row2.addView(createDigitKey("4", "GHI"))
    row2.addView(createDigitKey("5", "JKL"))
    row2.addView(createDigitKey("6", "MNO"))
    row2.addView(createSpecialKey("通知", isNotificationCenter = true))
    container.addView(row2)

    // 第三行：隐藏 | 7PQRS | 8TUV | 9WXYZ | 控制
    val row3 = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        gravity = Gravity.CENTER
        setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
    }
    row3.addView(createSpecialKey("隐藏", isHideKey = true))
    row3.addView(createDigitKey("7", "PQRS"))
    row3.addView(createDigitKey("8", "TUV"))
    row3.addView(createDigitKey("9", "WXYZ"))
    row3.addView(createSpecialKey("控制", isControlCenter = true))
    container.addView(row3)

    // 第四行：全部 | 未冻结 | 0 | 已冻结 | 清空
    val row4 = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        gravity = Gravity.CENTER
        setPadding(0, 4.dpToPx(), 0, 0)
    }
    row4.addView(createSpecialKey("全部", isShowAll = true))
    row4.addView(createSpecialKey("未冻结", showUnfrozenApps = true))
    row4.addView(createDigitKey("0", ""))
    row4.addView(createSpecialKey("已冻结", showFrozenApps = true))
    row4.addView(createSpecialKey("清空", isClearAll = true))
    container.addView(row4)

    keyboardContainerRef = WeakReference(container)
    return container
}

    private fun refreshKeyboardBindings() {
        try {
            t9Input.clear()
            isT9Mode = false

            val root = keyboardRootRef?.get() ?: return
            val oldKeyboard = keyboardContainerRef?.get()

            if (oldKeyboard != null) {
                (oldKeyboard.parent as? ViewGroup)?.removeView(oldKeyboard)
            }

            val newKeyboard = createKeyboardView()
            root.addView(newKeyboard)

        } catch (e: Exception) {
            Log.e("AppListPanel", "refreshKeyboardBindings failed", e)
        }
    }

    private fun createSearchDisplayKey(): View {
        return TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
            }
            background = createMacKeyBackground()
            setOnLongClickListener { clearT9Input(); true }
            searchEditTextRef = WeakReference(this)
        }
    }

    private fun createSettingsKey(): View {
        return TextView(context).apply {
            text = "⚙"
            textSize = 14f
            setTextColor(Color.parseColor("#FFEBEBF5"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
            }
            background = createMacKeyBackground()

            setOnClickListener {
                try {
                    onShowSettings()
                } catch (e: Exception) {
                    try {
                        val intent = Intent(context, WorkbenchSettingsActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e2: Exception) {
                        Toast.makeText(context, "无法打开设置", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            setOnLongClickListener {
                showManageBindingsDialog()
                true
            }
        }
    }

    // ============================================================
    // ========== 绑定管理浮层 ==========
    // ============================================================

    private fun showManageBindingsDialog() {
        try {
            dismissBindingsDialog()

            val bindings = keyBindings.getAllBindings()
            bindingsListSnapshot = bindings

            val density = context.resources.displayMetrics.density

            val rootLayout = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
                isClickable = true
                setOnClickListener { dismissBindingsDialog() }
            }

            val contentHolder = FrameLayout(context).apply {
                val lp = FrameLayout.LayoutParams(
                    (320 * density).toInt(),
                    (480 * density).toInt()
                )
                lp.gravity = Gravity.CENTER
                layoutParams = lp
                isClickable = true
                setOnClickListener { }
            }
            bindingsContentHolder = contentHolder

            rootLayout.addView(contentHolder)

            showBindingsListView(contentHolder, bindings)

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }

            bindingsDialogParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(rootLayout, bindingsDialogParams)
            bindingsDialogView = rootLayout

        } catch (e: Exception) {
            Log.e("AppListPanel", "showManageBindingsDialog failed", e)
            Toast.makeText(context, "打开绑定管理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBindingsListView(
        holder: FrameLayout,
        bindings: Map<String, KeyboardKeyBindings.Binding>
    ) {
        holder.removeAllViews()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "数字键绑定管理"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8.dpToPx())
        }
        contentLayout.addView(titleView)

        val hintView = TextView(context).apply {
            text = if (bindings.isEmpty()) "还没有绑定，点击下方「添加」开始"
                   else "点击任意项可编辑或清除"
            textSize = 12f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12.dpToPx())
        }
        contentLayout.addView(hintView)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (bindings.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = "（空）"
                textSize = 13f
                setTextColor(Color.parseColor("#66FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, 24.dpToPx(), 0, 24.dpToPx())
            }
            listContainer.addView(emptyView)
        } else {
            val sorted = bindings.entries.sortedBy { it.key }
            for ((digit, binding) in sorted) {
                val typeLabel = when (binding.type) {
                    KeyboardKeyBindings.BindingType.APP -> "应用"
                    KeyboardKeyBindings.BindingType.SHORTCUT -> "快捷方式"
                    KeyboardKeyBindings.BindingType.CUSTOM -> "自定义"
                    else -> ""
                }
                val displayText = "$digit  →  ${binding.label}    [$typeLabel]"

                val itemView = TextView(context).apply {
                    text = displayText
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                    setBackgroundColor(Color.parseColor("#FF2A2A2A"))
                    isClickable = true
                    isFocusable = true

                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 6.dpToPx()
                    layoutParams = lp

                    setOnClickListener {
                        showBindingActionMenu(holder, digit, binding)
                    }
                }
                listContainer.addView(itemView)
            }
        }

        scrollView.addView(listContainer)
        contentLayout.addView(scrollView)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12.dpToPx()
            layoutParams = lp
            gravity = Gravity.CENTER
        }

        val addCustomBtn = TextView(context).apply {
            text = "➕ 添加"
            textSize = 13f
            setTextColor(Color.parseColor("#FF88CCFF"))
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                showDigitPickerForCustom(holder)
            }
        }
        buttonRow.addView(addCustomBtn)

        val clearAllBtn = TextView(context).apply {
            text = "清除全部"
            textSize = 13f
            setTextColor(Color.parseColor("#FFFF6666"))
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                showClearAllConfirmView(holder)
            }
        }
        buttonRow.addView(clearAllBtn)

        val closeBtn = TextView(context).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                dismissBindingsDialog()
            }
        }
        buttonRow.addView(closeBtn)

        contentLayout.addView(buttonRow)

        holder.addView(contentLayout)
    }

    private fun showBindingActionMenu(
        holder: FrameLayout,
        digit: String,
        binding: KeyboardKeyBindings.Binding
    ) {
        holder.removeAllViews()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "$digit 键绑定"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8.dpToPx())
        }
        contentLayout.addView(titleView)

        val typeLabel = when (binding.type) {
            KeyboardKeyBindings.BindingType.APP -> "应用"
            KeyboardKeyBindings.BindingType.SHORTCUT -> "快捷方式"
            KeyboardKeyBindings.BindingType.CUSTOM -> "自定义"
            else -> ""
        }

        val detailView = TextView(context).apply {
            text = "${binding.label}\n[$typeLabel]"
            textSize = 13f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20.dpToPx())
        }
        contentLayout.addView(detailView)

        if (binding.type == KeyboardKeyBindings.BindingType.CUSTOM) {
            val editBtn = TextView(context).apply {
                text = "✏ 编辑"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                setBackgroundColor(Color.parseColor("#FF2A6A9A"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dpToPx()
                }
                setOnClickListener {
                    val custom = KeyboardKeyBindings.CustomIntent.fromJson(binding.value)
                    showCustomEditView(holder, digit, custom, binding.label)
                }
            }
            contentLayout.addView(editBtn)
        }

        val clearBtn = TextView(context).apply {
            text = "🗑 清除绑定"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FFCC3333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dpToPx()
            }
            setOnClickListener {
                showClearConfirmView(holder, digit, binding)
            }
        }
        contentLayout.addView(clearBtn)

        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                showBindingsListView(holder, bindingsListSnapshot)
            }
        }
        contentLayout.addView(cancelBtn)

        holder.addView(contentLayout)
    }

    private fun showDigitPickerForCustom(holder: FrameLayout) {
        holder.removeAllViews()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "选择要绑定的数字键"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16.dpToPx())
        }
        contentLayout.addView(titleView)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (d in 0..9) {
            val digitStr = d.toString()
            val existing = keyBindings.getBinding(digitStr)
            val statusText = if (existing.type != KeyboardKeyBindings.BindingType.NONE) {
                "（已绑定：${existing.label}）"
            } else ""

            val itemView = TextView(context).apply {
                text = "$digitStr 键  $statusText"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                setBackgroundColor(Color.parseColor("#FF2A2A2A"))
                isClickable = true

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 6.dpToPx()
                layoutParams = lp

                setOnClickListener {
                    showCustomEditView(holder, digitStr, KeyboardKeyBindings.CustomIntent(), "")
                }
            }
            listContainer.addView(itemView)
        }

        scrollView.addView(listContainer)
        contentLayout.addView(scrollView)

        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dpToPx()
            }
            setOnClickListener {
                showBindingsListView(holder, bindingsListSnapshot)
            }
        }
        contentLayout.addView(cancelBtn)

        holder.addView(contentLayout)
    }

    private fun showCustomEditView(
        holder: FrameLayout,
        digit: String,
        existing: KeyboardKeyBindings.CustomIntent,
        existingLabel: String
    ) {
        holder.removeAllViews()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "编辑 $digit 键绑定"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16.dpToPx())
        }
        contentLayout.addView(titleView)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        val formContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 显示名称
        formContainer.addView(TextView(context).apply {
            text = "显示名称（必填）"
            textSize = 12f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
        })

        val nameInput = EditText(context).apply {
            hint = "例如：打开网页"
            setText(existingLabel)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
        }
        formContainer.addView(nameInput)

        // Action
        formContainer.addView(TextView(context).apply {
            text = "Intent Action（可选）"
            textSize = 12f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 12.dpToPx(), 0, 4.dpToPx())
        })

        val actionInput = EditText(context).apply {
            hint = "android.intent.action.VIEW"
            setText(existing.action)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
        }
        formContainer.addView(actionInput)

        // Data
        formContainer.addView(TextView(context).apply {
            text = "Data URI（可选）"
            textSize = 12f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 12.dpToPx(), 0, 4.dpToPx())
        })

        val dataInput = EditText(context).apply {
            hint = "https://www.example.com"
            setText(existing.data)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
        }
        formContainer.addView(dataInput)

        // Package
        formContainer.addView(TextView(context).apply {
            text = "Package（可选）"
            textSize = 12f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 12.dpToPx(), 0, 4.dpToPx())
        })

        val pkgInput = EditText(context).apply {
            hint = "com.example.app"
            setText(existing.packageName)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
        }
        formContainer.addView(pkgInput)

        // Category
        formContainer.addView(TextView(context).apply {
            text = "Category（可选）"
            textSize = 12f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 12.dpToPx(), 0, 4.dpToPx())
        })

        val catInput = EditText(context).apply {
            hint = "android.intent.category.LAUNCHER"
            setText(existing.category)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
        }
        formContainer.addView(catInput)

        // 快速模板标题
        formContainer.addView(TextView(context).apply {
            text = "快速模板"
            textSize = 12f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setPadding(0, 16.dpToPx(), 0, 4.dpToPx())
        })

        data class Template(
            val name: String,
            val action: String,
            val data: String,
            val category: String
        )

        val templates = listOf(
            Template("打开网页", "android.intent.action.VIEW", "https://www.baidu.com", ""),
            Template("拨打电话", "android.intent.action.DIAL", "tel:10086", ""),
            Template("发送短信", "android.intent.action.SENDTO", "smsto:10086", ""),
            Template("打开地图", "android.intent.action.VIEW", "geo:0,0?q=北京", ""),
            Template("打开相机", "android.media.action.IMAGE_CAPTURE", "", ""),
            Template("拍视频", "android.media.action.VIDEO_CAPTURE", "", ""),
            Template("打开录音机", "android.provider.MediaStore.RECORD_SOUND", "", ""),
            Template("打开拨号界面", "android.intent.action.DIAL", "", ""),
            Template("打开联系人", "android.intent.action.MAIN", "", "android.intent.category.APP_CONTACTS"),
            Template("打开邮件", "android.intent.action.MAIN", "", "android.intent.category.APP_EMAIL"),
            Template("导航到地点", "android.intent.action.VIEW", "geo:0,0?q=天安门", ""),
            Template("支付宝扫一扫", "android.intent.action.VIEW", "alipays://platformapi/startapp?appId=10000007", ""),
            Template("支付宝付款码", "android.intent.action.VIEW", "alipays://platformapi/startapp?appId=20000056", ""),
            Template("打开设置", "android.settings.SETTINGS", "", ""),
            Template("打开 WiFi", "android.settings.WIFI_SETTINGS", "", ""),
            Template("打开蓝牙", "android.settings.BLUETOOTH_SETTINGS", "", "")
        )

        val rowSize = (templates.size + 1) / 2

        for (rowIndex in 0 until 2) {
            val startIdx = rowIndex * rowSize
            val endIdx = minOf(startIdx + rowSize, templates.size)
            if (startIdx >= templates.size) break

            val hScroll = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4.dpToPx()
                }
            }

            val hRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            for (i in startIdx until endIdx) {
                val tpl = templates[i]
                val tBtn = TextView(context).apply {
                    text = tpl.name
                    textSize = 12f
                    setTextColor(Color.parseColor("#FF88CCFF"))
                    gravity = Gravity.CENTER
                    setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
                    setBackgroundColor(Color.parseColor("#FF222222"))

                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.rightMargin = 6.dpToPx()
                    layoutParams = lp

                    setOnClickListener {
                        nameInput.setText(tpl.name)
                        actionInput.setText(tpl.action)
                        dataInput.setText(tpl.data)
                        catInput.setText(tpl.category)
                    }
                }
                hRow.addView(tBtn)
            }

            hScroll.addView(hRow)
            formContainer.addView(hScroll)
        }

        scrollView.addView(formContainer)
        contentLayout.addView(scrollView)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12.dpToPx()
            layoutParams = lp
            gravity = Gravity.CENTER
        }

        val saveBtn = TextView(context).apply {
            text = "保存绑定"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A8A4A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                val action = actionInput.text.toString().trim()
                val data = dataInput.text.toString().trim()
                val pkg = pkgInput.text.toString().trim()
                val cat = catInput.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(context, "请填写显示名称", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (action.isEmpty() && data.isEmpty() && pkg.isEmpty()) {
                    Toast.makeText(context, "至少填写 Action / Data / Package 之一", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val custom = KeyboardKeyBindings.CustomIntent(
                    action = action,
                    data = data,
                    packageName = pkg,
                    category = cat
                )

                keyBindings.setBinding(
                    digit,
                    KeyboardKeyBindings.Binding(
                        type = KeyboardKeyBindings.BindingType.CUSTOM,
                        value = custom.toJson(),
                        label = name
                    )
                )

                Toast.makeText(context, "$digit 键已绑定：$name", Toast.LENGTH_SHORT).show()

                val updated = keyBindings.getAllBindings()
                bindingsListSnapshot = updated
                showBindingsListView(holder, updated)
            }
        }
        buttonRow.addView(saveBtn)

        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                showBindingsListView(holder, bindingsListSnapshot)
            }
        }
        buttonRow.addView(cancelBtn)

        contentLayout.addView(buttonRow)

        holder.addView(contentLayout)
    }

    private fun showClearConfirmView(
        holder: FrameLayout,
        digit: String,
        binding: KeyboardKeyBindings.Binding
    ) {
        holder.removeAllViews()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "清除绑定"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12.dpToPx())
        }
        contentLayout.addView(titleView)

        val msgView = TextView(context).apply {
            text = "确定要清除 $digit 键的绑定\n（${binding.label}）吗？"
            textSize = 14f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20.dpToPx())
        }
        contentLayout.addView(msgView)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val confirmBtn = TextView(context).apply {
            text = "清除"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FFCC3333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                try {
                    keyBindings.clearBinding(digit)
                    Toast.makeText(context, "$digit 键绑定已清除", Toast.LENGTH_SHORT).show()

                    val remaining = keyBindings.getAllBindings()
                    bindingsListSnapshot = remaining
                    if (remaining.isEmpty()) {
                        dismissBindingsDialog()
                    } else {
                        showBindingsListView(holder, remaining)
                    }
                } catch (e: Exception) {
                    Log.e("AppListPanel", "clearBinding failed", e)
                }
            }
        }
        buttonRow.addView(confirmBtn)

        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                showBindingActionMenu(holder, digit, binding)
            }
        }
        buttonRow.addView(cancelBtn)

        contentLayout.addView(buttonRow)

        holder.addView(contentLayout)
    }

    private fun showClearAllConfirmView(holder: FrameLayout) {
        holder.removeAllViews()

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "清除全部绑定"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12.dpToPx())
        }
        contentLayout.addView(titleView)

        val msgView = TextView(context).apply {
            text = "确定要清除所有数字键的绑定吗？"
            textSize = 14f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20.dpToPx())
        }
        contentLayout.addView(msgView)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val confirmBtn = TextView(context).apply {
            text = "全部清除"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FFCC3333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                try {
                    for (digit in 0..9) {
                        keyBindings.clearBinding(digit.toString())
                    }
                    Toast.makeText(context, "已清除全部绑定", Toast.LENGTH_SHORT).show()
                    dismissBindingsDialog()
                } catch (e: Exception) {
                    Log.e("AppListPanel", "clearAll failed", e)
                }
            }
        }
        buttonRow.addView(confirmBtn)

        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(Color.parseColor("#FF2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                showBindingsListView(holder, bindingsListSnapshot)
            }
        }
        buttonRow.addView(cancelBtn)

        contentLayout.addView(buttonRow)

        holder.addView(contentLayout)
    }

    private fun dismissBindingsDialog() {
        try {
            bindingsDialogView?.let { view ->
                try { windowManager.removeView(view) } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Log.e("AppListPanel", "dismissBindingsDialog failed", e)
        }
        bindingsDialogView = null
        bindingsDialogParams = null
        bindingsContentHolder = null

        refreshKeyboardBindings()
    }

    // ============================================================

    private fun createSpecialKey(
    label: String,
    showUnfrozenApps: Boolean = false,
    showFrozenApps: Boolean = false,
    isBackspace: Boolean = false,
    isShowAll: Boolean = false,
    isClearAll: Boolean = false,
    isNotificationCenter: Boolean = false,
    isControlCenter: Boolean = false,
    isHideKey: Boolean = false
): View {

    // ========== "隐藏"键：主文字"隐藏" + 下方小字"锁定" ==========
    if (isHideKey) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
            }
            background = createMacKeyBackground()
            isClickable = true
            isLongClickable = true

            // 短按：隐藏工作台面板
            setOnClickListener {
                onHidePanel()
                onHideWorkbench?.invoke()
            }

            // 长按：隐藏 + 显示锁定图层
            setOnLongClickListener {
                onHidePanel()
                onLockRequested?.invoke()
                Toast.makeText(context, "已隐藏并锁定", Toast.LENGTH_SHORT).show()
                true
            }
        }

        val mainText = TextView(context).apply {
            text = "隐藏"
            textSize = 11f
            setTextColor(Color.parseColor("#FFEBEBF5"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxLines = 1
            includeFontPadding = false
        }
        container.addView(mainText)

        val subText = TextView(context).apply {
            text = "锁定"
            textSize = 8f
            setTextColor(Color.parseColor("#88FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 1.dpToPx()
            }
            maxLines = 1
            includeFontPadding = false
        }
        container.addView(subText)

        return container
    }

    // ========== 其他键（原有逻辑） ==========
    return TextView(context).apply {
        text = label
        textSize = 11f
        setTextColor(Color.parseColor("#FFEBEBF5"))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
            setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
        }
        background = createMacKeyBackground()

        setOnClickListener {
            when {
                showUnfrozenApps -> showUnfrozenApps()
                showFrozenApps -> showFrozenApps()
                isBackspace -> handleBackspace()
                isShowAll -> { clearT9Input() }
                isClearAll -> { clearT9Input() }
                isNotificationCenter -> onOpenNotificationCenter?.invoke()
                isControlCenter -> onOpenControlCenter?.invoke()
            }
        }

        if (showFrozenApps) {
            setOnLongClickListener { performOneKeyFreeze(); true }
        }

        if (isBackspace) {
            setOnLongClickListener { startContinuousClear(); true }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopContinuousClear(); false }
                    else -> false
                }
            }
        }
    }
}

    

    fun unlockLockOverlay() {
        lockOverlay?.unlock()
        onUnlockRequested?.invoke()
    }

    private fun createDigitKey(digit: String, letters: String): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
            }
            background = createMacKeyBackground()
            isClickable = true
            isLongClickable = true

            setOnClickListener {
                handleT9Input(digit)
            }

            setOnLongClickListener {
                val currentBinding = keyBindings.getBinding(digit)
                if (currentBinding.type != KeyboardKeyBindings.BindingType.NONE) {
                    launchBinding(currentBinding)
                } else {
                    onKeyLongPress?.invoke(digit)
                    Toast.makeText(context, "invoke 完成", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        val mainText = TextView(context).apply {
            text = if (letters.isEmpty()) digit else "$digit $letters"
            textSize = 14f
            setTextColor(Color.parseColor("#FFEBEBF5"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxLines = 1
            includeFontPadding = false
        }
        container.addView(mainText)

        val binding = keyBindings.getBinding(digit)
        if (binding.type != KeyboardKeyBindings.BindingType.NONE) {
            val labelText = TextView(context).apply {
                text = binding.label
                textSize = 8f
                setTextColor(Color.parseColor("#88FFFFFF"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 2.dpToPx()
                }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            container.addView(labelText)
        }

        return container
    }

    private fun launchBinding(binding: KeyboardKeyBindings.Binding) {
        when (binding.type) {
            KeyboardKeyBindings.BindingType.APP -> {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage(binding.value)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        onHidePanel()
                    } else {
                        Toast.makeText(context, "应用未安装", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            KeyboardKeyBindings.BindingType.SHORTCUT -> {
                try {
                    val parts = binding.value.split("|")
                    val action = parts[0]
                    val intent = Intent(action)

                    if (parts.size > 1) {
                        val extra = parts[1]
                        if (extra.startsWith("http")) {
                            intent.data = android.net.Uri.parse(extra)
                        } else {
                            intent.addCategory(extra)
                        }
                    }

                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    onHidePanel()
                } catch (e: Exception) {
                    Toast.makeText(context, "启动快捷方式失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            KeyboardKeyBindings.BindingType.CUSTOM -> {
                try {
                    val custom = KeyboardKeyBindings.CustomIntent.fromJson(binding.value)
                    val intent = Intent()

                    if (custom.action.isNotEmpty()) {
                        intent.action = custom.action
                    }
                    if (custom.data.isNotEmpty()) {
                        intent.data = android.net.Uri.parse(custom.data)
                    }
                    if (custom.packageName.isNotEmpty()) {
                        intent.setPackage(custom.packageName)
                    }
                    if (custom.category.isNotEmpty()) {
                        intent.addCategory(custom.category)
                    }

                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    if (intent.action == null && intent.data == null && custom.packageName.isEmpty()) {
                        Toast.makeText(context, "自定义 Intent 缺少 action/data/package", Toast.LENGTH_SHORT).show()
                        return
                    }

                    context.startActivity(intent)
                    onHidePanel()
                } catch (e: Exception) {
                    Toast.makeText(context, "启动自定义 Intent 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            else -> {}
        }
    }

    private fun createKeyButton(key: KeyData): View {
        return TextView(context).apply {
            text = "${key.label} ${key.letters}"
            textSize = 14f
            setTextColor(Color.parseColor("#FFEBEBF5"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
            }
            background = createMacKeyBackground()
            setOnClickListener { handleT9Input(key.label) }
        }
    }

    private fun createMacKeyBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8.dpToPx().toFloat()
            colors = intArrayOf(
                Color.parseColor("#FF2A2A2A"),
                Color.parseColor("#FF1A1A1A")
            )
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(1, Color.parseColor("#FF333333"))
        }
    }

    private fun handleT9Input(digit: String) {
        t9Input.append(digit)
        isT9Mode = true
        val input = t9Input.toString()
        searchEditTextRef?.get()?.text = input
        filterAppsByT9(input)
    }

    private fun handleBackspace() {
        if (t9Input.isNotEmpty()) {
            t9Input.deleteCharAt(t9Input.length - 1)
            val input = t9Input.toString()
            searchEditTextRef?.get()?.text = input
            if (input.isEmpty()) {
                isT9Mode = false
                val visibleApps = getVisibleApps()
                onAppsChanged(visibleApps.sortedBy { it.mName })
            } else {
                filterAppsByT9(input)
            }
        }
    }

    private fun startContinuousClear() {
        stopContinuousClear()
        val runnable = object : Runnable {
            override fun run() {
                if (t9Input.isNotEmpty()) {
                    t9Input.clear()
                    isT9Mode = false
                    searchEditTextRef?.get()?.text = ""
                    val visibleApps = getVisibleApps()
                    onAppsChanged(visibleApps.sortedBy { it.mName })
                }
                stopContinuousClear()
            }
        }
        clearRunnable = runnable
        clearHandler.postDelayed(runnable, 300)
    }

    private fun stopContinuousClear() {
        clearRunnable?.let { runnable ->
            clearHandler.removeCallbacks(runnable)
        }
        clearRunnable = null
    }

    fun clearT9Input() {
        t9Input.clear()
        isT9Mode = false
        searchEditTextRef?.get()?.text = ""
        val visibleApps = getVisibleApps()
        onAppsChanged(visibleApps.sortedBy { it.mName })
    }

    fun clearSearch() {
        clearT9Input()
        searchEditTextRef?.get()?.text = ""
    }

    private fun showFrozenApps() {
        val visibleApps = getVisibleApps()
        val frozenApps = visibleApps.filter { app ->
            app.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
        }
        if (frozenApps.isEmpty()) {
            Toast.makeText(context, "没有已冻结的应用", Toast.LENGTH_SHORT).show()
            return
        }
        onAppsChanged(frozenApps.sortedBy { it.mName })
    }

    private fun showUnfrozenApps() {
        val visibleApps = getVisibleApps()
        val unfrozenApps = visibleApps.filter { app ->
            app.mPackage?.let { !FreezeManager.isFrozen(context, it) } ?: true
        }
        if (unfrozenApps.isEmpty()) {
            Toast.makeText(context, "没有未冻结的应用", Toast.LENGTH_SHORT).show()
            return
        }
        onAppsChanged(unfrozenApps.sortedBy { it.mName })
    }

    private fun performOneKeyFreeze() {
        val sh = ShizukuHelper.getInstance()
        ShizukuHelper.getInstance().checkStatus()
        if (!sh.isReady()) {
            Toast.makeText(context, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val list = FreezeManager.getList(context)
        if (list.isEmpty()) {
            Toast.makeText(context, "冻结列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            var count = 0
            for (pkg in list) {
                if (FreezeManager.isFrozen(context, pkg)) continue
                iconLoader.getIconForPackage(context, pkg)
                if (sh.freezeApp(pkg)) {
                    FreezeManager.setFrozen(context, pkg, true)
                    count++
                    delay(50)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已冻结 $count 个应用", Toast.LENGTH_SHORT).show()
                onRefreshTiles()
                onRefreshApps()
            }
        }
    }

    fun loadApps(apps: List<App>) {
        allApps = apps
        pinyinCache.clear()
        val visibleApps = getVisibleApps()
        for (app in visibleApps) getPinyinCache(app)
        buildSearchIndex(visibleApps)
        loadClickHistory()
        onAppsChanged(visibleApps.sortedBy { it.mName })
    }

    fun refresh(apps: List<App>) {
        if (apps.isEmpty()) {
            if (allApps.isNotEmpty()) {
                val visibleApps = getVisibleApps()
                onAppsChanged(visibleApps.sortedBy { it.mName })
                return
            }
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val manager = WorkbenchManager.getInstance()
                    val cachedApps = manager?.getCachedApps() ?: emptyList()
                    if (cachedApps.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            allApps = cachedApps
                            val visibleApps = getVisibleApps()
                            pinyinCache.clear()
                            for (app in visibleApps) getPinyinCache(app)
                            buildSearchIndex(visibleApps)
                            onAppsChanged(visibleApps.sortedBy { it.mName })
                            loadClickHistory()
                        }
                    }
                } catch (e: Exception) {
                }
            }
            return
        }

        allApps = apps
        pinyinCache.clear()
        val visibleApps = getVisibleApps()
        for (app in visibleApps) getPinyinCache(app)
        buildSearchIndex(visibleApps)

        if (!isT9Mode) {
            onAppsChanged(visibleApps.sortedBy { it.mName })
        }
    }

    fun clearResources() {
        searchHandler.removeCallbacksAndMessages(null)
        searchRunnable = null
        loadIconJob?.cancel()
        loadDataJob?.cancel()
        stopContinuousClear()
        lockOverlay?.unlock()
        lockOverlay = null
        pinyinCache.clear()
        firstLetterIndex.clear()
        fullPinyinIndex.clear()
        namePrefixIndex.clear()
        iconCache.clear()
        clickHistory.clear()
        t9Input.clear()
        dismissBindingsDialog()
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}