package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import ru.queuejw.lumetro.components.core.AppManager
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.utils.PinYinStringHelper
import ru.queuejw.lumetro.model.App

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

    private var recyclerView: RecyclerView? = null
    private var adapter: GroupedAppListAdapter? = null
    private var searchEditText: EditText? = null
    private var allApps = emptyList<App>()
    private var displayedGroups = emptyList<GroupedAppListAdapter.GroupItem>()
    private val iconCache = mutableMapOf<String, Bitmap>()
    private var currentPopup: PopupWindow? = null
    
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    // T9 输入状态
    private var t9Input = StringBuilder()
    private var isT9Mode = false

    // 九键映射：字母 -> 数字
    private val t9ReverseMap = mapOf(
        'A' to '2', 'B' to '2', 'C' to '2',
        'D' to '3', 'E' to '3', 'F' to '3',
        'G' to '4', 'H' to '4', 'I' to '4',
        'J' to '5', 'K' to '5', 'L' to '5',
        'M' to '6', 'N' to '6', 'O' to '6',
        'P' to '7', 'Q' to '7', 'R' to '7', 'S' to '7',
        'T' to '8', 'U' to '8', 'V' to '8',
        'W' to '9', 'X' to '9', 'Y' to '9', 'Z' to '9'
    )

    // 九键显示数据
    private data class KeyData(val label: String, val letters: String)
    
    private val keyData = listOf(
        listOf(
            KeyData("", ""),      // 左上角：未冻结
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

    fun createView(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 搜索框 + 设置按钮
        val searchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }

        val searchBar = EditText(context).apply {
            hint = "搜索应用 或 九键输入..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
            setBackgroundColor(Color.parseColor("#FF222222"))
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!isT9Mode) {
                        searchHandler.removeCallbacksAndMessages(null)
                        searchRunnable = Runnable {
                            filterApps(s?.toString() ?: "")
                        }
                        searchRunnable?.let { searchHandler.postDelayed(it, 300) }
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchLayout.addView(searchBar)
        searchEditText = searchBar

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
                onShowSettings()
            }
        }
        searchLayout.addView(settingsBtn)

        container.addView(searchLayout)

        // 应用列表
        recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
        }
        container.addView(recyclerView)

        // 底部九键键盘
        val keyboardContainer = createKeyboardView()
        container.addView(keyboardContainer)

        return container
    }

    private fun createKeyboardView(): View {
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

        // 行1: 左上角(未冻结) | 2 ABC | 3 DEF
        val row1 = createKeyRow(keyData[0], isFirstRow = true)
        container.addView(row1)

        // 行2: 4 GHI | 5 JKL | 6 MNO
        val row2 = createKeyRow(keyData[1], isFirstRow = false)
        container.addView(row2)

        // 行3: 7 PQRS | 8 TUV | 9 WXYZ
        val row3 = createKeyRow(keyData[2], isFirstRow = false)
        container.addView(row3)

        // 行4: 已冻结(左下) | 0 全部(中) | ✕ 清除(右)
        val row4 = createActionRow()
        container.addView(row4)

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
                // 左上角：未冻结
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
            setOnLongClickListener {
                Toast.makeText(context, "显示所有未冻结应用", Toast.LENGTH_SHORT).show()
                true
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

            setOnLongClickListener {
                if (key.letters.isNotEmpty()) {
                    Toast.makeText(context, key.letters, Toast.LENGTH_SHORT).show()
                    true
                } else {
                    false
                }
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

        // 左下角：已冻结
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

        // 中键：0 全部
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
                searchEditText?.setText("")
                recyclerView?.smoothScrollToPosition(0)
            }
        }
        row.addView(allBtn)

        // 右键：✕ 清除
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
                searchEditText?.setText("")
                recyclerView?.smoothScrollToPosition(0)
            }
        }
        row.addView(clearBtn)

        return row
    }

    // ============ 显示已冻结 / 未冻结应用 ============

    private fun showFrozenApps() {
        val frozenApps = allApps.filter { app ->
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
        adapter?.updateData(displayedGroups)
        recyclerView?.smoothScrollToPosition(0)
        
        Toast.makeText(context, "已显示 ${frozenApps.size} 个冻结应用", Toast.LENGTH_SHORT).show()
    }

    private fun showUnfrozenApps() {
        val unfrozenApps = allApps.filter { app ->
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
        adapter?.updateData(displayedGroups)
        recyclerView?.smoothScrollToPosition(0)
        
        Toast.makeText(context, "已显示 ${unfrozenApps.size} 个未冻结应用", Toast.LENGTH_SHORT).show()
    }

    // ============ T9 预测输入 ============

    private fun handleT9Input(digit: String) {
        t9Input.append(digit)
        isT9Mode = true
        val input = t9Input.toString()
        
        searchEditText?.setText("🔢 $input")
        searchEditText?.setSelection(searchEditText?.text?.length ?: 0)
        
        filterAppsByT9(input)
    }

    private fun encodeToT9(letters: String): String {
        return letters.map { char ->
            t9ReverseMap[char.uppercaseChar()] ?: char
        }.joinToString("")
    }

    private fun filterAppsByT9(input: String) {
        val matched = allApps.filter { app ->
            val pinyin = PinYinStringHelper.getAlpha(app.mName)
            if (pinyin.isEmpty()) return@filter false
            
            val firstLetters = pinyin.replace(" ", "")
            if (firstLetters.isEmpty()) return@filter false
            
            val encoded = encodeToT9(firstLetters)
            encoded.startsWith(input)
        }
        
        if (matched.isEmpty()) {
            Toast.makeText(context, "没有匹配的应用", Toast.LENGTH_SHORT).show()
            displayedGroups = buildGroupedData(allApps)
        } else {
            displayedGroups = buildGroupedData(matched)
        }
        adapter?.updateData(displayedGroups)
        
        if (matched.isNotEmpty()) {
            recyclerView?.post {
                recyclerView?.smoothScrollToPosition(0)
            }
        }
    }

    private fun clearT9Input() {
        t9Input.clear()
        isT9Mode = false
        searchEditText?.setText("")
        displayedGroups = buildGroupedData(allApps)
        adapter?.updateData(displayedGroups)
    }

    // ============ 原有方法 ============

    fun scrollToLetter(targetLetter: String) {
        val position = getFirstPositionOfLetter(targetLetter)
        if (position >= 0) {
            val lm = recyclerView?.layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(position, 0)
        } else {
            Toast.makeText(context, "没有 $targetLetter 开头的应用", Toast.LENGTH_SHORT).show()
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

    private fun getAppFirstLetter(app: App): String {
        val name = app.mName
        if (name.isEmpty()) return ""
        val pinyin = PinYinStringHelper.getAlpha(name)
        if (pinyin.isEmpty()) return ""
        return pinyin.first().uppercase()
    }

    private fun buildGroupedData(apps: List<App>): List<GroupedAppListAdapter.GroupItem> {
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
        
        return result
    }

    fun loadApps(apps: List<App>) {
        allApps = apps
        displayedGroups = buildGroupedData(apps)
        setupAdapter()
    }

    private fun setupAdapter() {
        adapter = GroupedAppListAdapter(
            context = context,
            items = displayedGroups,
            iconLoader = iconLoader,
            coroutineScope = coroutineScope,
            iconCache = iconCache,
            onAppClick = { app ->
                launchApp(app)
            },
            onAppLongClick = { app, view ->
                showAppPopup(app, view)
            },
            onFreezeClick = { performOneKeyFreeze() },
            onSettingsClick = { onShowSettings() }
        )
        recyclerView?.adapter = adapter
    }

    private fun filterApps(query: String) {
        if (isT9Mode) {
            clearT9Input()
        }
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { 
                it.mName.lowercase().contains(query.lowercase()) 
            }
        }
        displayedGroups = buildGroupedData(filtered)
        adapter?.updateData(displayedGroups)
    }

    private fun launchApp(app: App) {
        app.mPackage?.let { pkg ->
            if (FreezeManager.isFrozen(context, pkg)) {
                coroutineScope.launch(Dispatchers.IO) {
                    val sh = ShizukuHelper.getInstance()
                    if (sh.unfreezeApp(pkg)) {
                        FreezeManager.setFrozen(context, pkg, false)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已解冻", Toast.LENGTH_SHORT).show()
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
    }

    private fun showAppPopup(app: App, anchor: View) {
        currentPopup?.dismiss()
        val pv = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }

        pv.addView(createPopupItem("固定到开始屏幕") {
            currentPopup?.dismiss()
            onPinApp(app)
        })

        val pkg = app.mPackage ?: return
        val inFreezeList = FreezeManager.getList(context).contains(pkg)
        val isFrozen = FreezeManager.isFrozen(context, pkg)

        pv.addView(createPopupItem(if (isFrozen) "解冻应用" else "冻结应用") {
            currentPopup?.dismiss()
            coroutineScope.launch(Dispatchers.IO) {
                val sh = ShizukuHelper.getInstance()
                if (isFrozen) {
                    if (sh.unfreezeApp(pkg)) {
                        FreezeManager.setFrozen(context, pkg, false)
                        withContext(Dispatchers.Main) {
                            onRefreshTiles()
                            onRefreshApps()
                            Toast.makeText(context, "已解冻", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "解冻失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (sh.freezeApp(pkg)) {
                        FreezeManager.setFrozen(context, pkg, true)
                        withContext(Dispatchers.Main) {
                            onRefreshTiles()
                            onRefreshApps()
                            Toast.makeText(context, "已冻结", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "冻结失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        if (!inFreezeList) {
            pv.addView(createPopupItem("添加到冻结列表") {
                currentPopup?.dismiss()
                FreezeManager.addToList(context, pkg)
                Toast.makeText(context, "已添加到冻结列表", Toast.LENGTH_SHORT).show()
            })
        } else {
            pv.addView(createPopupItem("从冻结列表移除") {
                currentPopup?.dismiss()
                FreezeManager.removeFromList(context, pkg)
                Toast.makeText(context, "已从冻结列表移除", Toast.LENGTH_SHORT).show()
            })
        }

        val isHidden = FreezeManager.getHiddenList(context).contains(pkg)
        pv.addView(createPopupItem(if (isHidden) "取消隐藏" else "隐藏应用") {
            currentPopup?.dismiss()
            FreezeManager.toggleHidden(context, pkg)
            onRefreshApps()
        })

        pv.addView(createPopupItem("应用信息") {
            currentPopup?.dismiss()
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })

        currentPopup = PopupWindow(
            pv,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            showAtLocation(anchor, Gravity.CENTER, 0, 0)
        }
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
                Thread.sleep(50)
            }
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "已冻结 $count 个应用", Toast.LENGTH_SHORT).show()
            onRefreshTiles()
            onRefreshApps()
            onHidePanel()  // 添加这行
        }
    }
}

    fun refresh(apps: List<App>) {
        allApps = apps
        if (!isT9Mode) {
            displayedGroups = buildGroupedData(apps)
            adapter?.updateData(displayedGroups)
        }
    }

    fun clearSearch() {
        clearT9Input()
        searchEditText?.setText("")
        filterApps("")
    }

    fun getSearchQuery(): String = searchEditText?.text?.toString() ?: ""

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}