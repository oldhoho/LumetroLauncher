package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
    private val onUnlockRequested: (() -> Unit)? = null
) {

    private val perfLogEnabled = false
    
    private fun perfLog(message: String) {
        if (!perfLogEnabled) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] [AppListPanel] $message"
        Log.d("AppListPanel_Perf", logMessage)
        
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "applist_performance_log.txt")
            file.appendText("$logMessage\n")
        } catch (e: Exception) {
        }
    }

    private var searchEditTextRef: WeakReference<TextView>? = null
    private var lockOverlay: LockOverlay? = null
    
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
        val rootContainer = android.widget.FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
        }
        
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
        row1.addView(createKeyButton(keyData[0][1]))
        row1.addView(createKeyButton(keyData[0][2]))
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
        row2.addView(createKeyButton(keyData[1][0]))
        row2.addView(createKeyButton(keyData[1][1]))
        row2.addView(createKeyButton(keyData[1][2]))
        row2.addView(createSpecialKey("通知", isNotificationCenter = true))
        container.addView(row2)

        // 第三行：锁定 | 7PQRS | 8TUV | 9WXYZ | 控制
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER
            setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
        }
        row3.addView(createSpecialKey("锁定", isLockScreen = true))
        row3.addView(createKeyButton(keyData[2][0]))
        row3.addView(createKeyButton(keyData[2][1]))
        row3.addView(createKeyButton(keyData[2][2]))
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

        return container
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
        }
    }

    private fun createSpecialKey(
        label: String,
        showUnfrozenApps: Boolean = false,
        showFrozenApps: Boolean = false,
        isBackspace: Boolean = false,
        isShowAll: Boolean = false,
        isClearAll: Boolean = false,
        isNotificationCenter: Boolean = false,
        isControlCenter: Boolean = false,
        isLockScreen: Boolean = false
    ): View {
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
                    isLockScreen -> toggleLockScreen()
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

    private fun toggleLockScreen() {
    if (lockOverlay?.isLocked() == true) {
        lockOverlay?.unlock()
        Toast.makeText(context, "已解锁", Toast.LENGTH_SHORT).show()
    } else {
        onHidePanel()
        onLockRequested?.invoke()
        Toast.makeText(context, "已锁定\n连续滑动500dp解锁", Toast.LENGTH_SHORT).show()
    }
}

    fun unlockLockOverlay() {
    lockOverlay?.unlock()
    // onUnlocked 回调会自动触发 onUnlockRequested
}

    private fun createDigitKey(digit: String, letters: String): View {
        return TextView(context).apply {
            text = if (letters.isEmpty()) digit else "$digit $letters"
            textSize = 14f
            setTextColor(Color.parseColor("#FFEBEBF5"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
            }
            background = createMacKeyBackground()
            setOnClickListener { handleT9Input(digit) }
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
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}