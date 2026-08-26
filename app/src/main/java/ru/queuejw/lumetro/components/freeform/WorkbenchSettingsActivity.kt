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

        // WorkbenchSettingsActivity.kt - 修改手势条监听器，立即持久化

// ========== 手势条宽度 ==========
seekbarGestureWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        val width = (2 + progress).toInt()
        tvGestureWidthValue.text = "${width}dp"
        if (fromUser) {
            // ========== 立即保存到持久化 ==========
            settings.gestureStripWidth = width
            // 也立即应用到手势条
            gestureManager.updateConfig(
                width,
                settings.gestureStripHeight,
                settings.gestureStripOffset,
                settings.gestureStripAlpha
            )
        }
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})

// ========== 手势条高度 ==========
seekbarGestureHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        val height = if (progress == 0) 0 else (20 + progress * 8).toInt()
        tvGestureHeightValue.text = if (height == 0) "全屏" else "${height}dp"
        if (fromUser) {
            settings.gestureStripHeight = height
            gestureManager.updateConfig(
                settings.gestureStripWidth,
                height,
                settings.gestureStripOffset,
                settings.gestureStripAlpha
            )
        }
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})

// ========== 手势条偏移 ==========
seekbarGestureOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        val offset = (progress * 2).toInt()
        tvGestureOffsetValue.text = "${offset}dp"
        if (fromUser) {
            settings.gestureStripOffset = offset
            gestureManager.updateConfig(
                settings.gestureStripWidth,
                settings.gestureStripHeight,
                offset,
                settings.gestureStripAlpha
            )
        }
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})

// ========== 手势条透明度 ==========
seekbarGestureAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        tvGestureAlphaValue.text = "${progress}%"
        if (fromUser) {
            val alpha = progress / 100f
            settings.gestureStripAlpha = alpha
            gestureManager.updateConfig(
                settings.gestureStripWidth,
                settings.gestureStripHeight,
                settings.gestureStripOffset,
                alpha
            )
        }
    }
    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})

        // ========== 应用列表宽度 ==========
        seekbarAppListWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = (50 + progress / 2) / 100f
                tvAppListWidthValue.text = "${(ratio * 100).toInt()}%"
                if (fromUser) {
                    settings.appListWidthRatio = ratio
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表高度 ==========
        seekbarAppListHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = (30 + progress * 0.7f) / 100f
                tvAppListHeightValue.text = "${(ratio * 100).toInt()}%"
                if (fromUser) {
                    settings.appListHeightRatio = ratio
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表垂直偏移 ==========
        seekbarAppListOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = progress * 2
                tvAppListOffsetValue.text = "${offset}dp"
                if (fromUser) {
                    settings.appListVerticalOffset = offset
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // ========== 应用列表圆角 ==========
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

        // ========== 应用列表背景透明度 ==========
        seekbarAppListDim.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress / 100f
                tvAppListDimValue.text = "${(alpha * 100).toInt()}%"
                if (fromUser) {
                    settings.appListDimAlpha = alpha
                    workbenchManager?.hideAppsList()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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

    // WorkbenchSettingsActivity.kt - 修改 applySettings()

private fun applySettings() {
    try {
        // ========== 应用工作台设置 ==========
        val heightProgress = seekbarHeight.progress
        val height = (5 + heightProgress * 2.95).toInt()
        settings.height = height

        // ========== 应用手势条设置（保存到持久化） ==========
        val width = (2 + seekbarGestureWidth.progress).toInt()
        val heightVal = if (seekbarGestureHeight.progress == 0) 0 else (20 + seekbarGestureHeight.progress * 8).toInt()
        val offset = (seekbarGestureOffset.progress * 2).toInt()
        val alpha = seekbarGestureAlpha.progress / 100f

        // 保存到 Settings（持久化）
        settings.gestureStripWidth = width
        settings.gestureStripHeight = heightVal
        settings.gestureStripOffset = offset
        settings.gestureStripAlpha = alpha

        // 应用到手势条
        gestureManager.updateConfig(width, heightVal, offset, alpha)

        // ========== 应用应用列表设置 ==========
        settings.appListWidthRatio = (50 + seekbarAppListWidth.progress / 2) / 100f
        settings.appListHeightRatio = (30 + seekbarAppListHeight.progress * 0.7f) / 100f
        settings.appListVerticalOffset = seekbarAppListOffset.progress * 2
        settings.appListCornerRadius = seekbarAppListCorner.progress
        settings.appListDimAlpha = seekbarAppListDim.progress / 100f

        if (settings.enabled) {
            WorkbenchManager.showWorkbench()
        }

        writeLog("Settings applied successfully")
        Toast.makeText(
            this,
            "设置已应用\n高度: ${height}dp\n手势偏移: ${offset}dp\n面板宽度: ${(settings.appListWidthRatio * 100).toInt()}%",
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

        // ========== 手势条设置 ==========
        val density = resources.displayMetrics.density
        val currentWidth = (gestureManager.stripWidth / density).toInt()
        val currentHeight = if (gestureManager.stripHeight == 0) 0 else (gestureManager.stripHeight / density).toInt()
        val currentOffset = (gestureManager.stripOffset / density).toInt()
        val currentAlpha = (gestureManager.stripAlpha * 100).toInt()

        seekbarGestureWidth.progress = (currentWidth - 2).coerceIn(0, 30)
        tvGestureWidthValue.text = "${currentWidth}dp"

        val heightProgress = if (currentHeight == 0) 0 else ((currentHeight - 20) / 8).coerceIn(0, 100)
        seekbarGestureHeight.progress = heightProgress
        tvGestureHeightValue.text = if (currentHeight == 0) "全屏" else "${currentHeight}dp"

        seekbarGestureOffset.progress = (currentOffset / 2).coerceIn(0, 400)
        tvGestureOffsetValue.text = "${currentOffset}dp"

        seekbarGestureAlpha.progress = currentAlpha.coerceIn(0, 100)
        tvGestureAlphaValue.text = "${currentAlpha}%"

        // ========== 应用列表设置 ==========
        val widthRatio = settings.appListWidthRatio
        seekbarAppListWidth.progress = ((widthRatio - 0.5f) * 200).toInt().coerceIn(0, 100)
        tvAppListWidthValue.text = "${(widthRatio * 100).toInt()}%"

        val heightRatio = settings.appListHeightRatio
        seekbarAppListHeight.progress = ((heightRatio - 0.3f) / 0.7f * 100).toInt().coerceIn(0, 100)
        tvAppListHeightValue.text = "${(heightRatio * 100).toInt()}%"

        val verticalOffset = settings.appListVerticalOffset
        seekbarAppListOffset.progress = (verticalOffset / 2).coerceIn(0, 100)
        tvAppListOffsetValue.text = "${verticalOffset}dp"

        val cornerRadius = settings.appListCornerRadius
        seekbarAppListCorner.progress = cornerRadius.coerceIn(0, 60)
        tvAppListCornerValue.text = "${cornerRadius}dp"

        val dimAlpha = settings.appListDimAlpha
        seekbarAppListDim.progress = (dimAlpha * 100).toInt().coerceIn(0, 100)
        tvAppListDimValue.text = "${(dimAlpha * 100).toInt()}%"
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