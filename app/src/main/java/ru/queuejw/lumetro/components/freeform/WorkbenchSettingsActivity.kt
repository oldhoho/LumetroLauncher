package ru.queuejw.lumetro.components.freeform

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService
import ru.queuejw.lumetro.components.freeform.gesture.LeftGestureStripManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkbenchSettingsActivity : AppCompatActivity() {

    private lateinit var settings: WorkbenchSettings
    private lateinit var gestureManager: LeftGestureStripManager
    private var workbenchManager: WorkbenchManager? = null

    // ========== 工作台设置控件 ==========
    private lateinit var switchEnabled: Switch
    private lateinit var seekbarHeight: SeekBar
    private lateinit var tvHeightValue: TextView

    // ========== 手势条设置控件 ==========
    private lateinit var seekbarGestureWidth: SeekBar
    private lateinit var seekbarGestureHeight: SeekBar
    private lateinit var seekbarGestureOffset: SeekBar
    private lateinit var seekbarGestureAlpha: SeekBar
    private lateinit var tvGestureWidthValue: TextView
    private lateinit var tvGestureHeightValue: TextView
    private lateinit var tvGestureOffsetValue: TextView
    private lateinit var tvGestureAlphaValue: TextView

    // ========== 应用列表设置控件 ==========
    private lateinit var seekbarAppListWidth: SeekBar
    private lateinit var seekbarAppListHeight: SeekBar
    private lateinit var seekbarAppListOffset: SeekBar
    private lateinit var seekbarAppListCorner: SeekBar
    private lateinit var seekbarAppListDim: SeekBar
    private lateinit var tvAppListWidthValue: TextView
    private lateinit var tvAppListHeightValue: TextView
    private lateinit var tvAppListOffsetValue: TextView
    private lateinit var tvAppListCornerValue: TextView
    private lateinit var tvAppListDimValue: TextView

    // ========== 磁贴面板开关 ==========
    private lateinit var switchTilesPanel: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workbench_settings)

        Log.d("Settings", "=== onCreate ===")

        settings = WorkbenchSettings(this)

        val service = SidebarAccessibilityService.getInstance()
        Log.d("Settings", "service = $service")
        if (service == null) {
            Toast.makeText(this, "AccessibilityService 未启动，无法调整手势条", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        gestureManager = LeftGestureStripManager.getInstance(this, service)

        workbenchManager = WorkbenchManager.getInstance()
        Log.d("Settings", "workbenchManager = $workbenchManager")
        if (workbenchManager == null) {
            Toast.makeText(this, "WorkbenchManager 未初始化，部分功能不可用", Toast.LENGTH_LONG).show()
        }

        initViews()
        setupListeners()
        loadSettings()
        
        // ========== 应用持久化的手势条设置 ==========
        applySavedGestureSettings()
    }

    private fun initViews() {
        // ========== 工作台设置 ==========
        switchEnabled = findViewById(R.id.switch_workbench_enabled)
        seekbarHeight = findViewById(R.id.seekbar_workbench_height)
        tvHeightValue = findViewById(R.id.tv_height_value)

        // ========== 手势条设置 ==========
        seekbarGestureWidth = findViewById(R.id.seekbar_gesture_width)
        seekbarGestureHeight = findViewById(R.id.seekbar_gesture_height)
        seekbarGestureOffset = findViewById(R.id.seekbar_gesture_offset)
        seekbarGestureAlpha = findViewById(R.id.seekbar_gesture_alpha)
        tvGestureWidthValue = findViewById(R.id.tv_gesture_width_value)
        tvGestureHeightValue = findViewById(R.id.tv_gesture_height_value)
        tvGestureOffsetValue = findViewById(R.id.tv_gesture_offset_value)
        tvGestureAlphaValue = findViewById(R.id.tv_gesture_alpha_value)

        // ========== 应用列表设置 ==========
        seekbarAppListWidth = findViewById(R.id.seekbar_applist_width)
        seekbarAppListHeight = findViewById(R.id.seekbar_applist_height)
        seekbarAppListOffset = findViewById(R.id.seekbar_applist_offset)
        seekbarAppListCorner = findViewById(R.id.seekbar_applist_corner)
        seekbarAppListDim = findViewById(R.id.seekbar_applist_dim)
        tvAppListWidthValue = findViewById(R.id.tv_applist_width_value)
        tvAppListHeightValue = findViewById(R.id.tv_applist_height_value)
        tvAppListOffsetValue = findViewById(R.id.tv_applist_offset_value)
        tvAppListCornerValue = findViewById(R.id.tv_applist_corner_value)
        tvAppListDimValue = findViewById(R.id.tv_applist_dim_value)

        // ========== 磁贴面板开关 ==========
        switchTilesPanel = findViewById(R.id.switch_tiles_panel)

        // ========== 设置SeekBar范围 ==========
        seekbarAppListOffset.max = 400      // 0-400dp
        seekbarAppListHeight.max = 190      // 0.1-2.0
        seekbarAppListWidth.max = 100       // 0.5-1.0
        seekbarAppListCorner.max = 50       // 0-50dp
        seekbarAppListDim.max = 90          // 0-90%
    }

    private fun setupListeners() {
        // ========== 工作台启用/禁用 ==========
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            settings.enabled = isChecked
            if (isChecked) {
                WorkbenchManager.showWorkbench()
                Toast.makeText(this, "工作台已启用", Toast.LENGTH_SHORT).show()
            } else {
                WorkbenchManager.hideWorkbench()
                Toast.makeText(this, "工作台已禁用", Toast.LENGTH_SHORT).show()
            }
        }

        // ========== 工作台高度 ==========
        seekbarHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val height = (5 + progress * 2.95).toInt()
                tvHeightValue.text = "${height}dp"
                if (fromUser) {
                    settings.height = height
                    if (settings.enabled) {
                        WorkbenchManager.showWorkbench()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 手势条宽度 ==========
        seekbarGestureWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val width = (2 + progress).toInt()
                tvGestureWidthValue.text = "${width}dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 手势条高度 ==========
        seekbarGestureHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val height = if (progress == 0) 0 else (20 + progress * 8).toInt()
                tvGestureHeightValue.text = if (height == 0) "全屏" else "${height}dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 手势条偏移 ==========
        seekbarGestureOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = (progress * 2).toInt()
                tvGestureOffsetValue.text = "${offset}dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 手势条透明度 ==========
        seekbarGestureAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvGestureAlphaValue.text = "${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表宽度（0.5-1.0） ==========
        seekbarAppListWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = (50 + progress * 0.5f) / 100f
                tvAppListWidthValue.text = "${(ratio * 100).toInt()}%"
                if (fromUser) {
                    settings.appListWidthRatio = ratio
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表高度（0.1-2.0） ==========
        seekbarAppListHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = (10 + progress) / 100f
                tvAppListHeightValue.text = "${(ratio * 100).toInt()}%"
                if (fromUser) {
                    settings.appListHeightRatio = ratio
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表垂直偏移（0-400dp） ==========
        seekbarAppListOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = progress
                tvAppListOffsetValue.text = "${offset}dp"
                if (fromUser) {
                    settings.appListVerticalOffset = offset
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表圆角（0-50dp） ==========
        seekbarAppListCorner.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress
                tvAppListCornerValue.text = "${radius}dp"
                if (fromUser) {
                    settings.appListCornerRadius = radius
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表背景透明度（0-0.9） ==========
        seekbarAppListDim.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress / 100f * 0.9f
                tvAppListDimValue.text = "${(alpha * 100).toInt()}%"
                if (fromUser) {
                    settings.appListDimAlpha = alpha
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 磁贴面板禁用开关 ==========
        switchTilesPanel.setOnCheckedChangeListener { _, isChecked ->
            settings.tilesPanelEnabled = isChecked
            if (isChecked) {
                SidebarAccessibilityService.sidebarManager?.createGestureStrip()
                Toast.makeText(this, "磁贴面板已启用", Toast.LENGTH_SHORT).show()
            } else {
                SidebarAccessibilityService.sidebarManager?.destroyGestureStrip()
                SidebarAccessibilityService.sidebarManager?.hidePanelImmediately()
                Toast.makeText(this, "磁贴面板已禁用", Toast.LENGTH_SHORT).show()
            }
        }

        // ========== 按钮监听器 ==========
        findViewById<Button>(R.id.btn_apply_settings).setOnClickListener {
            applySettings()
        }

        findViewById<Button>(R.id.btn_reset_settings).setOnClickListener {
            resetSettings()
        }

        findViewById<Button>(R.id.btn_close).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_icon_pack)?.setOnClickListener {
            try {
                Log.d("Settings", "=== 图标包按钮被点击 ===")
                Toast.makeText(this, "正在加载图标包...", Toast.LENGTH_SHORT).show()

                if (workbenchManager == null) {
                    Log.e("Settings", "workbenchManager is null")
                    Toast.makeText(this, "WorkbenchManager 未初始化", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                workbenchManager?.showIconPackPicker(this)
            } catch (e: Exception) {
                Log.e("Settings", "Icon pack error", e)
                Toast.makeText(this, "无法打开图标包选择: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_freeze_management)?.setOnClickListener {
            try {
                if (workbenchManager == null) {
                    Toast.makeText(this, "WorkbenchManager 未初始化", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                workbenchManager?.showFreezeManagementDialog(this)
            } catch (e: Exception) {
                Log.e("Settings", "Freeze management error", e)
                Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_hidden_management)?.setOnClickListener {
            try {
                if (workbenchManager == null) {
                    Toast.makeText(this, "WorkbenchManager 未初始化", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                workbenchManager?.showHiddenManagementDialog(this)
            } catch (e: Exception) {
                Log.e("Settings", "Hidden management error", e)
                Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySettings() {
        try {
            // ========== 应用工作台设置 ==========
            val heightProgress = seekbarHeight.progress
            val height = (5 + heightProgress * 2.95).toInt()
            settings.height = height

            // ========== 应用手势条设置并持久化 ==========
            val width = (2 + seekbarGestureWidth.progress).toInt()
            val heightVal = if (seekbarGestureHeight.progress == 0) 0 else (20 + seekbarGestureHeight.progress * 8).toInt()
            val offset = (seekbarGestureOffset.progress * 2).toInt()
            val alpha = seekbarGestureAlpha.progress / 100f

            gestureManager.updateConfig(width, heightVal, offset, alpha)

            // 保存到 settings（持久化）
            settings.gestureStripWidth = width
            settings.gestureStripHeight = heightVal
            settings.gestureStripOffset = offset
            settings.gestureStripAlpha = alpha

            // ========== 应用应用列表设置 ==========
            settings.appListWidthRatio = (50 + seekbarAppListWidth.progress * 0.5f) / 100f
            settings.appListHeightRatio = (10 + seekbarAppListHeight.progress) / 100f
            settings.appListVerticalOffset = seekbarAppListOffset.progress
            settings.appListCornerRadius = seekbarAppListCorner.progress
            settings.appListDimAlpha = seekbarAppListDim.progress / 100f * 0.9f

            // ========== 磁贴面板开关 ==========
            settings.tilesPanelEnabled = switchTilesPanel.isChecked
            if (!switchTilesPanel.isChecked) {
                SidebarAccessibilityService.sidebarManager?.destroyGestureStrip()
                SidebarAccessibilityService.sidebarManager?.hidePanelImmediately()
            } else {
                SidebarAccessibilityService.sidebarManager?.createGestureStrip()
            }

            if (settings.enabled) {
                WorkbenchManager.showWorkbench()
            }

            writeLog("Settings applied successfully")
            Toast.makeText(
                this,
                "设置已应用\n高度: ${height}dp\n手势偏移: ${offset}dp\n面板宽度: ${(settings.appListWidthRatio * 100).toInt()}%\n面板高度: ${(settings.appListHeightRatio * 100).toInt()}%",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            writeLog("❌ Apply failed: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "应用失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetSettings() {
        settings.resetToDefaults()
        gestureManager.updateConfig(6, 0, 0, 0.3f)
        if (!gestureManager.isShowing()) {
            gestureManager.show()
        }
        loadSettings()
        Toast.makeText(this, "已重置为默认设置", Toast.LENGTH_SHORT).show()
    }

    private fun loadSettings() {
        // ========== 工作台设置 ==========
        switchEnabled.isChecked = settings.enabled

        val height = settings.height
        val progress = ((height - 5) / 2.95).toInt().coerceIn(0, 100)
        seekbarHeight.progress = progress
        tvHeightValue.text = "${height}dp"

        // ========== 手势条设置（从 settings 读取持久化数据） ==========
        val savedWidth = settings.gestureStripWidth
        val savedHeight = settings.gestureStripHeight
        val savedOffset = settings.gestureStripOffset
        val savedAlpha = (settings.gestureStripAlpha * 100).toInt()

        seekbarGestureWidth.progress = (savedWidth - 2).coerceIn(0, 30)
        tvGestureWidthValue.text = "${savedWidth}dp"

        val heightProgress = if (savedHeight == 0) 0 else ((savedHeight - 20) / 8).coerceIn(0, 100)
        seekbarGestureHeight.progress = heightProgress
        tvGestureHeightValue.text = if (savedHeight == 0) "全屏" else "${savedHeight}dp"

        seekbarGestureOffset.progress = (savedOffset / 2).coerceIn(0, 400)
        tvGestureOffsetValue.text = "${savedOffset}dp"

        seekbarGestureAlpha.progress = savedAlpha.coerceIn(0, 100)
        tvGestureAlphaValue.text = "${savedAlpha}%"

        // ========== 应用列表设置 ==========
        val widthRatio = settings.appListWidthRatio
        seekbarAppListWidth.progress = ((widthRatio - 0.5f) / 0.5f * 100).toInt().coerceIn(0, 100)
        tvAppListWidthValue.text = "${(widthRatio * 100).toInt()}%"

        val heightRatio = settings.appListHeightRatio
        seekbarAppListHeight.progress = ((heightRatio - 0.1f) * 100).toInt().coerceIn(0, 190)
        tvAppListHeightValue.text = "${(heightRatio * 100).toInt()}%"

        val verticalOffset = settings.appListVerticalOffset
        seekbarAppListOffset.progress = verticalOffset.coerceIn(0, 400)
        tvAppListOffsetValue.text = "${verticalOffset}dp"

        val cornerRadius = settings.appListCornerRadius
        seekbarAppListCorner.progress = cornerRadius.coerceIn(0, 50)
        tvAppListCornerValue.text = "${cornerRadius}dp"

        val dimAlpha = settings.appListDimAlpha
        seekbarAppListDim.progress = (dimAlpha / 0.9f * 100).toInt().coerceIn(0, 90)
        tvAppListDimValue.text = "${(dimAlpha * 100).toInt()}%"

        // ========== 磁贴面板开关 ==========
        switchTilesPanel.isChecked = settings.tilesPanelEnabled
    }

    /**
     * 应用持久化的手势条设置
     */
    private fun applySavedGestureSettings() {
        val savedWidth = settings.gestureStripWidth
        val savedHeight = settings.gestureStripHeight
        val savedOffset = settings.gestureStripOffset
        val savedAlpha = settings.gestureStripAlpha
        gestureManager.updateConfig(savedWidth, savedHeight, savedOffset, savedAlpha)
    }

    private fun writeLog(message: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "workbench_settings_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {}
    }
}