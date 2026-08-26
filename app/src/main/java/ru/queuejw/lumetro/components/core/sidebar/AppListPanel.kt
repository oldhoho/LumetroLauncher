package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import ru.queuejw.lumetro.components.core.AppManager
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.freeform.WorkbenchManager
import ru.queuejw.lumetro.components.freeform.WorkbenchSettingsActivity
import ru.queuejw.lumetro.components.utils.PinYinStringHelper
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
    private val onRefreshApps: () -> Unit
) {

    // ========== 性能日志 ==========
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
            // 忽略
        }
    }

    // ========== 使用 WeakReference 防止内存泄漏 ==========
    private var recyclerViewRef: WeakReference<RecyclerView>? = null
    private var adapterRef: WeakReference<GroupedAppListAdapter>? = null
    private var searchEditTextRef: WeakReference<EditText>? = null
    private var currentPopupRef: WeakReference<PopupWindow>? = null
    
    // ========== 核心数据 ==========
    private var allApps = emptyList<App>()
    private var displayedGroups = emptyList<GroupedAppListAdapter.GroupItem>()
    private val iconCache = mutableMapOf<String, Bitmap>()
    
    // ========== 协程任务管理 ==========
    private var loadIconJob: Job? = null
    private var loadDataJob: Job? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private var t9Input = StringBuilder()
    private var isT9Mode = false

    // ========== 九键映射 ==========
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

    // ========== 搜索匹配结果 ==========
    data class SearchResult(
        val app: App,
        val score: Int,
        val matchType: String
    )

    // ========== 拼音缓存（使用 LRU 缓存） ==========
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

    // ========== 搜索索引（性能优化） ==========
    private val firstLetterIndex = mutableMapOf<String, MutableList<App>>()
    private val fullPinyinIndex = mutableMapOf<String, MutableList<App>>()
    private val namePrefixIndex = mutableMapOf<String, MutableList<App>>()
    
    private val MIN_SEARCH_PREFIX_LENGTH = 2

    // ========== 分组数据缓存 ==========
    private val groupedCache = mutableMapOf<Int, List<GroupedAppListAdapter.GroupItem>>()

    // ========== 点击历史 ==========
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

    private var enableDebugLog = false

    // ========== 拼音工具 ==========
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
            val pinyin = PinyinHelper.toHanyuPinyinStringArray(char, pinyinOutputFormat)
            if (pinyin != null && pinyin.isNotEmpty()) {
                result.add(pinyin[0])
            } else {
                result.add(char.toString())
            }
        }
        return result
    }

    private fun encodeToT9(text: String): String {
        return text.map { char ->
            t9ReverseMap[char] ?: char
        }.joinToString("")
    }

    // ========== 提取名称中的英文部分 ==========
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
        if (current.isNotEmpty()) {
            parts.add(current.toString())
        }
        return parts
    }

    // ========== 拼音缓存 ==========
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

    // ========== 构建搜索索引 ==========
    private fun buildSearchIndex(apps: List<App>) {
        val startTime = System.currentTimeMillis()
        firstLetterIndex.clear()
        fullPinyinIndex.clear()
        namePrefixIndex.clear()
        
        for (app in apps) {
            val cache = getPinyinCache(app)
            
            val firstEncoded = cache.firstEncoded
            firstLetterIndex.getOrPut(firstEncoded) { mutableListOf() }.add(app)
            
            val fullEncoded = cache.fullEncoded
            fullPinyinIndex.getOrPut(fullEncoded) { mutableListOf() }.add(app)
            
            val name = app.mName
            if (name.isNotEmpty()) {
                for (i in MIN_SEARCH_PREFIX_LENGTH..name.length) {
                    val prefix = name.substring(0, i)
                    namePrefixIndex.getOrPut(prefix) { mutableListOf() }.add(app)
                }
            }
        }
        perfLog("buildSearchIndex completed for ${apps.size} apps in ${System.currentTimeMillis() - startTime}ms")
    }

    // ========== 点击历史 ==========

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
                if (count > 0) {
                    clickHistory[it] = count
                }
            }
        }
    }

    private fun writeLog(message: String) {
        if (!enableDebugLog) return
        Log.d("T9Search", message)
    }

    // ========== 获取可见应用列表（过滤隐藏应用） ==========
    private fun getVisibleApps(): List<App> {
        val hidden = FreezeManager.getHiddenList(context).toSet()
        return allApps.filter { it.mPackage !in hidden }
    }

    // ========== 智能九键搜索（优化版） ==========

    private fun filterAppsByT9(input: String) {
        val startTime = System.currentTimeMillis()
        perfLog("filterAppsByT9 START: input='$input'")
        
        if (input.isEmpty()) {
            val visibleApps = getVisibleApps()
            displayedGroups = buildGroupedData(visibleApps)
            adapterRef?.get()?.updateData(displayedGroups)
            perfLog("filterAppsByT9 END (empty): ${System.currentTimeMillis() - startTime}ms")
            return
        }

        // ========== 获取可见应用 ==========
        val visibleApps = getVisibleApps()
        
        if (visibleApps.isEmpty()) {
            perfLog("filterAppsByT9: allApps is empty, loading apps...")
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val manager = WorkbenchManager.getInstance()
                    val apps = manager?.getCachedApps() ?: emptyList()
                    withContext(Dispatchers.Main) {
                        if (apps.isNotEmpty()) {
                            perfLog("filterAppsByT9: loaded ${apps.size} apps from manager")
                            allApps = apps
                            val visible = getVisibleApps()
                            pinyinCache.clear()
                            for (app in visible) {
                                getPinyinCache(app)
                            }
                            buildSearchIndex(visible)
                            filterAppsByT9(input)
                        } else {
                            perfLog("filterAppsByT9: no apps available")
                            Toast.makeText(context, "无法加载应用列表", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    perfLog("filterAppsByT9: load apps failed - ${e.message}")
                }
            }
            return
        }

        // ========== 关键修复：如果索引为空但 visibleApps 有数据，重建索引 ==========
        if (firstLetterIndex.isEmpty() && visibleApps.isNotEmpty()) {
            perfLog("filterAppsByT9: index is empty but visibleApps has ${visibleApps.size} apps, rebuilding index...")
            pinyinCache.clear()
            for (app in visibleApps) {
                getPinyinCache(app)
            }
            buildSearchIndex(visibleApps)
            perfLog("filterAppsByT9: index rebuilt")
        }

        if (clickHistory.isEmpty()) {
            loadClickHistory()
        }

        // ========== 多模式搜索 ==========
        var candidateTime = System.currentTimeMillis()
        val candidates = mutableSetOf<App>()
        
        // 模式1: 首字母编码匹配
        firstLetterIndex[input]?.let { candidates.addAll(it) }
        
        // 模式2: 完整拼音包含匹配
        for (entry in fullPinyinIndex.entries) {
            val key = entry.key
            val apps = entry.value
            if (key.contains(input)) {
                candidates.addAll(apps)
            }
        }
        
        // 模式3: 名称前缀匹配
        if (input.length >= MIN_SEARCH_PREFIX_LENGTH) {
            namePrefixIndex[input]?.let { candidates.addAll(it) }
        }
        
        // ========== 模式4: 英文单词匹配（处理混合名称） ==========
        for (app in visibleApps) {
            val name = app.mName
            val englishParts = extractEnglishParts(name)
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
        
        // ========== 模式5: 完整拼音包含匹配（兜底） ==========
        for (app in visibleApps) {
            val cache = getPinyinCache(app)
            if (cache.fullEncoded.contains(input)) {
                candidates.add(app)
            }
        }
        
        // ========== 模式6: 如果候选太少，直接遍历所有应用（兜底扫描） ==========
        if (candidates.size < 5) {
            perfLog("filterAppsByT9: candidates too few (${candidates.size}), direct scanning...")
            var scanCount = 0
            for (app in visibleApps) {
                val cache = getPinyinCache(app)
                if (cache.firstEncoded.contains(input)) {
                    candidates.add(app)
                    scanCount++
                    continue
                }
                if (cache.fullEncoded.contains(input)) {
                    candidates.add(app)
                    scanCount++
                    continue
                }
                if (app.mName.contains(input)) {
                    candidates.add(app)
                    scanCount++
                }
            }
            perfLog("filterAppsByT9: direct scan found $scanCount more candidates")
        }
        
        perfLog("filterAppsByT9: candidates ${candidates.size} in ${System.currentTimeMillis() - candidateTime}ms")

        // ========== 评分排序 ==========
        var scoreTime = System.currentTimeMillis()
        val results = candidates.mapNotNull { app ->
            val cache = getPinyinCache(app)
            val name = app.mName

            var score = 0
            var matchType = ""

            // L1: 首字母编码完全匹配
            if (cache.firstEncoded == input) {
                score = 1000
                matchType = "首字母完全匹配"
            }
            // L2: 首字母编码包含
            else if (cache.firstEncoded.contains(input)) {
                val index = cache.firstEncoded.indexOf(input)
                score = 800 + maxOf(50 - (index * 5), 0)
                matchType = "首字母包含"
            }
            // L3: 英文单词匹配
            else {
                val englishParts = extractEnglishParts(name)
                var matchedEnglish = false
                for (part in englishParts) {
                    if (part.length >= 2) {
                        val encoded = encodeToT9(part.lowercase())
                        if (encoded == input) {
                            score = 700
                            matchType = "英文完全匹配: $part"
                            matchedEnglish = true
                            break
                        } else if (encoded.contains(input)) {
                            score = 600 + (input.length * 5)
                            matchType = "英文包含匹配: $part"
                            matchedEnglish = true
                            break
                        }
                    }
                }
                if (!matchedEnglish) {
                    // L4: 任意字首字母匹配
                    var matchedChars = 0
                    for (charPinyin in cache.pinyinChars) {
                        val charFirst = charPinyin.firstOrNull()?.toString() ?: ""
                        if (encodeToT9(charFirst) == input) {
                            matchedChars++
                        }
                    }
                    if (matchedChars > 0) {
                        score = 500 + (matchedChars * 20)
                        matchType = "任意字首字母(${matchedChars}字)"
                    }
                    // L5: 完整拼音前缀
                    else if (cache.fullEncoded.startsWith(input)) {
                        score = 300 + (input.length * 3)
                        matchType = "完整拼音前缀"
                    }
                    // L6: 完整拼音包含
                    else if (cache.fullEncoded.contains(input)) {
                        val index = cache.fullEncoded.indexOf(input)
                        score = 200 + maxOf(100 - (index * 2), 0)
                        matchType = "完整拼音包含"
                    }
                    // L7: 中文匹配
                    else if (name.contains(input)) {
                        val index = name.indexOf(input)
                        score = 150 + maxOf(80 - (index * 3), 0)
                        matchType = "中文匹配"
                    }
                    // L8: 逐字组合匹配
                    else {
                        val inputChars = input.toList()
                        var matchCount = 0
                        for ((index, char) in inputChars.withIndex()) {
                            if (index < cache.pinyinChars.size) {
                                val charPinyin = cache.pinyinChars[index]
                                val charFirst = charPinyin.firstOrNull()?.toString() ?: ""
                                if (encodeToT9(charFirst) == char.toString()) {
                                    matchCount++
                                }
                            }
                        }
                        if (matchCount >= 2) {
                            score = 100 + (matchCount * 15)
                            matchType = "逐字匹配(${matchCount}/${inputChars.size})"
                        }
                    }
                }
            }

            if (score > 0) {
                val appPackage = app.mPackage ?: ""
                val clickCount = getClickCount(appPackage)
                val finalScore = score + (clickCount * 5)
                SearchResult(app, finalScore, "$matchType + 点击${clickCount}次")
            } else {
                null
            }
        }
        perfLog("filterAppsByT9: scoring ${results.size} results in ${System.currentTimeMillis() - scoreTime}ms")

        var sortTime = System.currentTimeMillis()
        val matchedApps = results
            .sortedByDescending { it.score }
            .take(50)
            .map { it.app }
        perfLog("filterAppsByT9: sort in ${System.currentTimeMillis() - sortTime}ms")

        // ========== 构建搜索结果 ==========
        displayedGroups = if (matchedApps.isEmpty()) {
            buildGroupedData(visibleApps)
        } else {
            buildSearchResults(matchedApps)
        }
        
        var updateTime = System.currentTimeMillis()
        adapterRef?.get()?.updateData(displayedGroups)
        perfLog("filterAppsByT9: updateData in ${System.currentTimeMillis() - updateTime}ms")

        // ========== 滚动到底部 ==========
if (matchedApps.isNotEmpty()) {
    recyclerViewRef?.get()?.postDelayed({
        val recyclerView = recyclerViewRef?.get()
        val adapter = adapterRef?.get()
        if (recyclerView != null && adapter != null) {
            val itemCount = adapter.itemCount
            if (itemCount > 0) {
                recyclerView.scrollToPosition(itemCount - 1)
            }
        }
    }, 50)
}
        
        perfLog("filterAppsByT9 END: total ${System.currentTimeMillis() - startTime}ms, matched ${matchedApps.size}")
    }

    // ========== 搜索模式结果构建 ==========
private fun buildSearchResults(apps: List<App>): List<GroupedAppListAdapter.GroupItem> {
    val result = mutableListOf<GroupedAppListAdapter.GroupItem>()
    if (apps.isEmpty()) return result
    
    // ========== 反转数据：最高分的在底部（列表末尾） ==========
    val reversedApps = apps.reversed()
    
    result.add(GroupedAppListAdapter.GroupItem.Header("🔍 搜索结果"))
    for (app in reversedApps) {
        result.add(GroupedAppListAdapter.GroupItem.AppItem(app))
    }
    return result
}

    // ========== View 创建 ==========

    fun createView(): View {
        perfLog("createView START")
        val startTime = System.currentTimeMillis()
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ========== 搜索栏 ==========
        val searchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }

        val searchBar = EditText(context).apply {
            hint = "九键输入搜索..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
            setBackgroundColor(Color.parseColor("#FF222222"))
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            isClickable = false
            isLongClickable = false
            setKeyListener(null)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnLongClickListener {
                clearT9Input()
                filterApps("")
                true
            }
        }
        searchLayout.addView(searchBar)
        searchEditTextRef = WeakReference(searchBar)

        val settingsBtn = TextView(context).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                48.dpToPx(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#FF222222"))
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
        searchLayout.addView(settingsBtn)

        container.addView(searchLayout)

        // ========== RecyclerView ==========
val recyclerView = RecyclerView(context).apply {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f
    )
    layoutManager = LinearLayoutManager(context).apply {
        stackFromEnd = true
        reverseLayout = false
    }
    isNestedScrollingEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    setHasFixedSize(true)
}
container.addView(recyclerView)
recyclerViewRef = WeakReference(recyclerView)

        // ========== 键盘 ==========
        val keyboardContainer = createKeyboardView()
        container.addView(keyboardContainer)

        perfLog("createView END: ${System.currentTimeMillis() - startTime}ms")
        return container
    }

    // ========== 键盘 ==========

    private fun createKeyboardView(): View {
        val startTime = System.currentTimeMillis()
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                200.dpToPx()
            )
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 8.dpToPx())
            gravity = Gravity.CENTER
        }

        val row1 = createKeyRow(keyData[0], isFirstRow = true)
        container.addView(row1)

        val row2 = createKeyRow(keyData[1], isFirstRow = false)
        container.addView(row2)

        val row3 = createKeyRow(keyData[2], isFirstRow = false)
        container.addView(row3)

        val row4 = createActionRow()
        container.addView(row4)

        perfLog("createKeyboardView: ${System.currentTimeMillis() - startTime}ms")
        return container
    }

    private fun createKeyRow(keys: List<KeyData>, isFirstRow: Boolean): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        row.gravity = Gravity.CENTER
        row.setPadding(0, 2.dpToPx(), 0, 2.dpToPx())

        for (key in keys) {
            val keyView = if (isFirstRow && key.label == "") {
                createUnfrozenButton()
            } else {
                createKeyButton(key)
            }
            row.addView(keyView)
        }

        return row
    }

    private fun createUnfrozenButton(): View {
        return TextView(context).apply {
            text = "未冻结"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            setOnClickListener {
                showUnfrozenApps()
            }
        }
    }

    private fun createKeyButton(key: KeyData): View {
        val displayText = "${key.label} ${key.letters}"

        return TextView(context).apply {
            text = displayText
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            setBackgroundColor(Color.parseColor("#44FFFFFF"))

            setOnClickListener {
                handleT9Input(key.label)
            }
        }
    }

    private fun createActionRow(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        row.gravity = Gravity.CENTER
        row.setPadding(0, 4.dpToPx(), 0, 0)

        val frozenBtn = TextView(context).apply {
            text = "已冻结"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            setBackgroundColor(Color.parseColor("#33AADDFF"))
            setOnClickListener {
                showFrozenApps()
            }
            setOnLongClickListener {
                performOneKeyFreeze()
                true
            }
        }
        row.addView(frozenBtn)

        val allBtn = TextView(context).apply {
            text = "0 全部"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.2f
            )
            setBackgroundColor(Color.parseColor("#55FFFFFF"))
            setOnClickListener {
                clearT9Input()
                filterApps("")
                searchEditTextRef?.get()?.setText("")
                recyclerViewRef?.get()?.smoothScrollToPosition(0)
            }
        }
        row.addView(allBtn)

        val clearBtn = TextView(context).apply {
            text = "✕ 清除"
            textSize = 14f
            setTextColor(Color.parseColor("#88FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setOnClickListener {
                clearT9Input()
                filterApps("")
                searchEditTextRef?.get()?.setText("")
                recyclerViewRef?.get()?.smoothScrollToPosition(0)
            }
        }
        row.addView(clearBtn)

        return row
    }

    // ========== T9 输入处理 ==========

    private fun handleT9Input(digit: String) {
        t9Input.append(digit)
        isT9Mode = true
        val input = t9Input.toString()

        searchEditTextRef?.get()?.apply {
            setText("🔢 $input")
            setSelection(text?.length ?: 0)
        }

        filterAppsByT9(input)
    }

    fun clearT9Input() {
        t9Input.clear()
        isT9Mode = false
        searchEditTextRef?.get()?.setText("")
        val visibleApps = getVisibleApps()
        displayedGroups = buildGroupedData(visibleApps)
        adapterRef?.get()?.updateData(displayedGroups)
    }

    // ========== 过滤 ==========

    private fun filterApps(query: String) {
        if (isT9Mode) {
            clearT9Input()
        }
        val visibleApps = getVisibleApps()
        val filtered = if (query.isEmpty()) {
            visibleApps
        } else {
            visibleApps.filter {
                it.mName.lowercase().contains(query.lowercase())
            }
        }
        displayedGroups = buildGroupedData(filtered)
        adapterRef?.get()?.updateData(displayedGroups)
    }

    // ========== 分组数据 ==========

    private fun buildGroupedData(apps: List<App>): List<GroupedAppListAdapter.GroupItem> {
        val startTime = System.currentTimeMillis()
        val result = mutableListOf<GroupedAppListAdapter.GroupItem>()

        val grouped = mutableMapOf<String, MutableList<App>>()
        for (app in apps) {
            val letter = getAppFirstLetter(app)
            if (letter.isNotEmpty()) {
                grouped.getOrPut(letter) { mutableListOf() }.add(app)
            }
        }

        val sortedKeys = grouped.keys.sorted()
        for (letter in sortedKeys) {
            result.add(GroupedAppListAdapter.GroupItem.Header(letter))
            val appsInGroup = grouped[letter] ?: emptyList()
            val sortedApps = appsInGroup.sortedBy { it.mName }
            for (app in sortedApps) {
                result.add(GroupedAppListAdapter.GroupItem.AppItem(app))
            }
        }

        if (apps.size > 20) {
            perfLog("buildGroupedData: ${apps.size} apps in ${System.currentTimeMillis() - startTime}ms")
        }
        return result
    }

    private fun getAppFirstLetter(app: App): String {
        val name = app.mName
        if (name.isEmpty()) return ""
        val pinyin = PinYinStringHelper.getAlpha(name)
        if (pinyin.isEmpty()) return ""
        return pinyin.first().uppercase()
    }

    // ========== 显示冻结/未冻结应用 ==========

    private fun showFrozenApps() {
        val startTime = System.currentTimeMillis()
        perfLog("showFrozenApps START")
        
        val visibleApps = getVisibleApps()
        val frozenApps = visibleApps.filter { app ->
            app.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
        }

        if (frozenApps.isEmpty()) {
            Toast.makeText(context, "没有已冻结的应用", Toast.LENGTH_SHORT).show()
            return
        }

        val items = mutableListOf<GroupedAppListAdapter.GroupItem>()
        items.add(GroupedAppListAdapter.GroupItem.Header("❄️ 已冻结应用"))

        val sorted = frozenApps.sortedBy { it.mName }
        for (app in sorted) {
            items.add(GroupedAppListAdapter.GroupItem.AppItem(app))
        }

        displayedGroups = items
        adapterRef?.get()?.updateData(displayedGroups)
        recyclerViewRef?.get()?.smoothScrollToPosition(0)
        
        perfLog("showFrozenApps END: ${frozenApps.size} apps in ${System.currentTimeMillis() - startTime}ms")
    }

    private fun showUnfrozenApps() {
        val startTime = System.currentTimeMillis()
        perfLog("showUnfrozenApps START")
        
        val visibleApps = getVisibleApps()
        val unfrozenApps = visibleApps.filter { app ->
            app.mPackage?.let { !FreezeManager.isFrozen(context, it) } ?: true
        }

        if (unfrozenApps.isEmpty()) {
            Toast.makeText(context, "没有未冻结的应用", Toast.LENGTH_SHORT).show()
            return
        }

        val items = mutableListOf<GroupedAppListAdapter.GroupItem>()
        items.add(GroupedAppListAdapter.GroupItem.Header("📱 未冻结应用"))

        val sorted = unfrozenApps.sortedBy { it.mName }
        for (app in sorted) {
            items.add(GroupedAppListAdapter.GroupItem.AppItem(app))
        }

        displayedGroups = items
        adapterRef?.get()?.updateData(displayedGroups)
        recyclerViewRef?.get()?.smoothScrollToPosition(0)
        
        perfLog("showUnfrozenApps END: ${unfrozenApps.size} apps in ${System.currentTimeMillis() - startTime}ms")
    }

    // ========== 一键冻结 ==========
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
            // ========== 冻结完成后关闭搜索面板 ==========
            onHidePanel()
        }
    }
}

    // ========== 加载数据 ==========

    fun loadApps(apps: List<App>) {
        perfLog("loadApps START: ${apps.size} apps")
        val startTime = System.currentTimeMillis()
        
        allApps = apps
        pinyinCache.clear()
        
        val visibleApps = getVisibleApps()
        for (app in visibleApps) {
            getPinyinCache(app)
        }
        buildSearchIndex(visibleApps)
        perfLog("loadApps: index built for ${visibleApps.size} apps in ${System.currentTimeMillis() - startTime}ms")
        
        displayedGroups = buildGroupedData(visibleApps)
        setupAdapter()
        loadClickHistory()
        perfLog("loadApps END: total ${System.currentTimeMillis() - startTime}ms")
    }

    // ========== 快速加载模式 ==========
    fun loadAppsFast(apps: List<App>) {
        perfLog("loadAppsFast START: ${apps.size} apps")
        val startTime = System.currentTimeMillis()
        
        allApps = apps
        
        val visibleApps = getVisibleApps()
        pinyinCache.clear()
        for (app in visibleApps) {
            getPinyinCache(app)
        }
        buildSearchIndex(visibleApps)
        perfLog("loadAppsFast: index built for ${visibleApps.size} apps in ${System.currentTimeMillis() - startTime}ms")
        
        displayedGroups = buildGroupedDataFromCache(visibleApps)
        setupAdapter()
        loadClickHistory()
        perfLog("loadAppsFast: UI ready in ${System.currentTimeMillis() - startTime}ms")
    }

    private fun buildGroupedDataFromCache(apps: List<App>): List<GroupedAppListAdapter.GroupItem> {
        val cacheKey = apps.hashCode()
        return groupedCache.getOrPut(cacheKey) {
            buildGroupedData(apps)
        }
    }

    private fun setupAdapter() {
        val startTime = System.currentTimeMillis()
        val recyclerView = recyclerViewRef?.get()
        if (recyclerView == null) {
            Log.e("AppListPanel", "RecyclerView is null, cannot setup adapter")
            return
        }
        
        val oldAdapter = adapterRef?.get()
        oldAdapter?.let {
            try {
                it.javaClass.getMethod("clearReferences").invoke(it)
            } catch (e: Exception) {
                // 忽略
            }
        }
        
        val adapter = GroupedAppListAdapter(
            context = context,
            items = displayedGroups,
            iconLoader = iconLoader,
            coroutineScope = coroutineScope,
            iconCache = iconCache,
            onAppClick = { app ->
                app.mPackage?.let { pkg -> recordAppClick(pkg) }
                launchApp(app)
            },
            onAppLongClick = { app, view ->
                showAppPopup(app, view)
            },
            onFreezeClick = { performOneKeyFreeze() },
            onSettingsClick = {
                onShowSettings()
            }
        )
        adapterRef = WeakReference(adapter)
        recyclerView.adapter = adapter
        perfLog("setupAdapter: ${System.currentTimeMillis() - startTime}ms")
    }

    fun refresh(apps: List<App>) {
        perfLog("refresh START: ${apps.size} apps")
        val startTime = System.currentTimeMillis()
        
        if (apps.isEmpty()) {
            if (allApps.isNotEmpty()) {
                perfLog("refresh: apps is empty but allApps has ${allApps.size} apps, keeping existing data")
                val visibleApps = getVisibleApps()
                displayedGroups = buildGroupedData(visibleApps)
                adapterRef?.get()?.updateData(displayedGroups)
                return
            }
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val manager = WorkbenchManager.getInstance()
                    val cachedApps = manager?.getCachedApps() ?: emptyList()
                    if (cachedApps.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            perfLog("refresh: loaded ${cachedApps.size} apps from manager")
                            allApps = cachedApps
                            val visibleApps = getVisibleApps()
                            pinyinCache.clear()
                            for (app in visibleApps) {
                                getPinyinCache(app)
                            }
                            buildSearchIndex(visibleApps)
                            displayedGroups = buildGroupedData(visibleApps)
                            adapterRef?.get()?.updateData(displayedGroups)
                            loadClickHistory()
                            perfLog("refresh: completed with ${visibleApps.size} visible apps")
                        }
                    }
                } catch (e: Exception) {
                    perfLog("refresh: failed to load apps - ${e.message}")
                }
            }
            return
        }
        
        if (allApps.size == apps.size && allApps.hashCode() == apps.hashCode()) {
            perfLog("refresh: no changes, skip")
            return
        }
        
        allApps = apps
        pinyinCache.clear()
        
        val visibleApps = getVisibleApps()
        for (app in visibleApps) {
            getPinyinCache(app)
        }
        buildSearchIndex(visibleApps)
        
        if (!isT9Mode) {
            displayedGroups = buildGroupedData(visibleApps)
            adapterRef?.get()?.updateData(displayedGroups)
        }
        perfLog("refresh END: ${System.currentTimeMillis() - startTime}ms")
    }

    fun clearSearch() {
        clearT9Input()
        searchEditTextRef?.get()?.setText("")
        filterApps("")
    }

    fun getSearchQuery(): String = searchEditTextRef?.get()?.text?.toString() ?: ""

    // ========== 启动应用 ==========

    private fun launchApp(app: App) {
        val pkg = app.mPackage
        if (pkg.isNullOrEmpty()) {
            Toast.makeText(context, "无法启动：包名为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (FreezeManager.isFrozen(context, pkg)) {
            coroutineScope.launch(Dispatchers.IO) {
                val sh = ShizukuHelper.getInstance()
                if (sh.unfreezeApp(pkg)) {
                    FreezeManager.setFrozen(context, pkg, false)
                    withContext(Dispatchers.Main) {
                        onRefreshTiles()
                        AppManager.launchApp(pkg, context)
                        onHidePanel()
                    }
                }
            }
        } else {
            AppManager.launchApp(pkg, context)
            onHidePanel()
        }
    }

    // ========== 应用长按弹出菜单 ==========

    private fun showAppPopup(app: App, anchor: View) {
        dismissPopup()
        
        val pv = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }

        pv.addView(createPopupItem("固定到开始屏幕") {
            dismissPopup()
            onPinApp(app)
        })

        val pkg = app.mPackage
        if (pkg.isNullOrEmpty()) {
            Toast.makeText(context, "包名为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        val inFreezeList = FreezeManager.getList(context).contains(pkg)
        val isFrozen = FreezeManager.isFrozen(context, pkg)

        pv.addView(createPopupItem(if (isFrozen) "解冻应用" else "冻结应用") {
            dismissPopup()
            coroutineScope.launch(Dispatchers.IO) {
                val sh = ShizukuHelper.getInstance()
                if (isFrozen) {
                    if (sh.unfreezeApp(pkg)) {
                        FreezeManager.setFrozen(context, pkg, false)
                        withContext(Dispatchers.Main) {
                            onRefreshTiles()
                            onRefreshApps()
                        }
                    }
                } else {
                    if (sh.freezeApp(pkg)) {
                        FreezeManager.setFrozen(context, pkg, true)
                        withContext(Dispatchers.Main) {
                            onRefreshTiles()
                            onRefreshApps()
                        }
                    }
                }
            }
        })

        if (!inFreezeList) {
            pv.addView(createPopupItem("添加到冻结列表") {
                dismissPopup()
                FreezeManager.addToList(context, pkg)
            })
        } else {
            pv.addView(createPopupItem("从冻结列表移除") {
                dismissPopup()
                FreezeManager.removeFromList(context, pkg)
            })
        }

        val isHidden = FreezeManager.getHiddenList(context).contains(pkg)
        pv.addView(createPopupItem(if (isHidden) "取消隐藏" else "隐藏应用") {
            dismissPopup()
            FreezeManager.toggleHidden(context, pkg)
            onRefreshApps()
        })

        pv.addView(createPopupItem("应用信息") {
            dismissPopup()
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })

        val popup = PopupWindow(
            pv,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            showAtLocation(anchor, Gravity.CENTER, 0, 0)
        }
        currentPopupRef = WeakReference(popup)
    }

    private fun createPopupItem(text: String, click: () -> Unit): View {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener { click() }
        }
    }

    private fun dismissPopup() {
        currentPopupRef?.get()?.dismiss()
        currentPopupRef = null
    }

    // ========== 滚动到指定字母 ==========

    fun scrollToLetter(targetLetter: String) {
        val position = getFirstPositionOfLetter(targetLetter)
        if (position >= 0) {
            val lm = recyclerViewRef?.get()?.layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun getFirstPositionOfLetter(targetLetter: String): Int {
        val items = displayedGroups
        for ((index, item) in items.withIndex()) {
            when (item) {
                is GroupedAppListAdapter.GroupItem.Header -> {
                    if (item.letter == targetLetter) {
                        return index
                    }
                }
                is GroupedAppListAdapter.GroupItem.AppItem -> {
                    val firstChar = getAppFirstLetter(item.app)
                    if (firstChar == targetLetter) {
                        return index
                    }
                    if (firstChar > targetLetter) {
                        return index
                    }
                }
                else -> {}
            }
        }
        return -1
    }

    // ========== 清理资源 ==========
    fun clearResources() {
        perfLog("clearResources")
        searchHandler.removeCallbacksAndMessages(null)
        searchRunnable = null
        dismissPopup()
        
        loadIconJob?.cancel()
        loadIconJob = null
        loadDataJob?.cancel()
        loadDataJob = null
        
        val adapter = adapterRef?.get()
        if (adapter != null) {
            try {
                adapter.javaClass.getMethod("clearReferences").invoke(adapter)
            } catch (e: Exception) {
                // 忽略
            }
        }
        adapterRef = null
        
        recyclerViewRef?.get()?.adapter = null
        recyclerViewRef = null
        
        pinyinCache.clear()
        firstLetterIndex.clear()
        fullPinyinIndex.clear()
        namePrefixIndex.clear()
        groupedCache.clear()
        iconCache.clear()
        clickHistory.clear()
        t9Input.clear()
    }

    // ========== 扩展函数 ==========

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}