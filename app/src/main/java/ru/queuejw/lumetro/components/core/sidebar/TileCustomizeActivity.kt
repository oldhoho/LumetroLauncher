package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.core.ColorManager
import ru.queuejw.lumetro.components.core.db.tile.TileDatabase
import ru.queuejw.lumetro.components.ui.dialog.ColorDialog
import ru.queuejw.lumetro.databinding.ActivityTileCustomizeBinding
import ru.queuejw.lumetro.model.TileEntity
import java.io.ByteArrayOutputStream

class TileCustomizeActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_TILE_ID = "tile_id"
        const val EXTRA_TILE_PACKAGE = "tile_package"
        const val EXTRA_TILE_LABEL = "tile_label"
        const val EXTRA_TILE_COLOR = "tile_color"
        const val EXTRA_TILE_SIZE = "tile_size"
        const val EXTRA_TILE_CORNER_RADIUS = "tile_corner_radius"
        private const val REQUEST_PICK_IMAGE = 1001
        
        fun createIntent(context: Context, tile: TileEntity): Intent {
            return Intent(context, TileCustomizeActivity::class.java).apply {
                putExtra(EXTRA_TILE_ID, tile.id)
                putExtra(EXTRA_TILE_PACKAGE, tile.tilePackage)
                putExtra(EXTRA_TILE_LABEL, tile.tileLabel)
                putExtra(EXTRA_TILE_COLOR, tile.tileColor)
                putExtra(EXTRA_TILE_SIZE, tile.tileSize)
                putExtra(EXTRA_TILE_CORNER_RADIUS, tile.tileCornerRadius)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
    
    private lateinit var binding: ActivityTileCustomizeBinding
    private var tileId: Long = 0
    private var tilePackage: String = ""
    private var tileLabel: String = ""
    private var tileColor: String? = null
    private var tileCornerRadius: Int = -1
    private var customIconBitmap: Bitmap? = null
    
    private val colorManager: ColorManager by lazy { ColorManager() }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityTileCustomizeBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            loadTileData()
            setupUI()
            setupListeners()
            loadCurrentIcon()
        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun loadTileData() {
        tileId = intent.getLongExtra(EXTRA_TILE_ID, 0)
        tilePackage = intent.getStringExtra(EXTRA_TILE_PACKAGE) ?: ""
        tileLabel = intent.getStringExtra(EXTRA_TILE_LABEL) ?: ""
        tileColor = intent.getStringExtra(EXTRA_TILE_COLOR)
        tileCornerRadius = intent.getIntExtra(EXTRA_TILE_CORNER_RADIUS, -1)
    }
    
    private fun setupUI() {
        binding.appLabel.text = tileLabel
        binding.labelEditText.setText(tileLabel)
        try {
            val color = colorManager.getAccentColor(this)
            binding.cornerRadiusSlider.trackTintList = android.content.res.ColorStateList.valueOf(color)
            binding.cornerRadiusSlider.thumbTintList = android.content.res.ColorStateList.valueOf(color)
            binding.cornerRadiusSlider.value = if (tileCornerRadius != -1) tileCornerRadius.toFloat() / 4 else 0f
        } catch (e: Exception) {}
    }
    
    private fun setupListeners() {
        binding.apply {
            backButton.setOnClickListener { finish() }
            appIcon.setOnClickListener { openImagePicker() }
            appIcon.setOnLongClickListener { resetIcon(); true }
            changeLabelBtn.setOnClickListener { toggleLabelEditor() }
            saveNewLabel.setOnClickListener { saveNewLabel() }
            changeColorBtn.setOnClickListener { showColorPicker() }
            closeButton.setOnClickListener { finish() }
            saveButton.setOnClickListener { saveAllChanges() }
            cornerRadiusSlider.addOnChangeListener { _, value, _ -> tileCornerRadius = value.toInt() * 4 }
        }
    }
    
    private fun openImagePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_PICK_IMAGE)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {}
                loadImageFromUri(uri)
            }
        }
    }
    
    private fun loadCurrentIcon() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(tilePackage, 0)
                val icon = packageManager.getApplicationIcon(appInfo)
                val size = resources.getDimensionPixelSize(R.dimen.icon_size_big)
                val bitmap = icon.toBitmap(size, size)
                withContext(Dispatchers.Main) { binding.appIcon.setImageBitmap(bitmap) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { binding.appIcon.setImageResource(android.R.drawable.ic_menu_edit) }
            }
        }
    }
    
    private fun loadImageFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val size = resources.getDimensionPixelSize(R.dimen.icon_size_big)
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true)
                        customIconBitmap = scaledBitmap
                        withContext(Dispatchers.Main) { binding.appIcon.setImageBitmap(scaledBitmap) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TileCustomizeActivity, "加载图片失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun resetIcon() {
        customIconBitmap = null
        getSharedPreferences("tile_custom_icons", MODE_PRIVATE).edit().remove("bg_" + tilePackage).apply()
        loadCurrentIcon()
        Toast.makeText(this, "已重置为默认图标", Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleLabelEditor() {
        if (binding.changeLabelLayout.visibility == View.GONE) {
            binding.changeLabelLayout.visibility = View.VISIBLE
            binding.labelEditText.setText(tileLabel)
            binding.labelEditText.selectAll()
        } else {
            binding.changeLabelLayout.visibility = View.GONE
        }
    }
    
    private fun saveNewLabel() {
        val newLabel = binding.labelEditText.text.toString().trim()
        if (newLabel.isNotEmpty()) {
            tileLabel = newLabel
            binding.appLabel.text = newLabel
            binding.changeLabelLayout.visibility = View.GONE
            Toast.makeText(this, "标签已更新", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showColorPicker() {
        try {
            val dialog = ColorDialog(this)
            dialog.show(supportFragmentManager, "color_picker")
            supportFragmentManager.setFragmentResultListener("color", this) { _, bundle ->
                bundle.getString("color_value")?.let { tileColor = it }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开颜色选择器", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveAllChanges() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = TileDatabase.getTileData(this@TileCustomizeActivity)
                val dao = db.getTilesDao()
                val tile = dao.getTilesData().find { it.id == tileId }
                if (tile != null) {
                    tile.tileLabel = tileLabel
                    tile.tileColor = tileColor
                    tile.tileCornerRadius = tileCornerRadius
                    if (customIconBitmap != null) {
                        saveIconToPrefs(tilePackage, customIconBitmap!!)
                    }
                    dao.updateAllTiles(listOf(tile))
                }
                db.close()
                withContext(Dispatchers.Main) {
                    sendBroadcast(Intent("ru.queuejw.lumetro.UPDATE_TILES").apply { setPackage(packageName) })
                    delay(300)
                    sendBroadcast(Intent("ru.queuejw.lumetro.UPDATE_TILES").apply { setPackage(packageName) })
                    Toast.makeText(this@TileCustomizeActivity, "保存成功", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TileCustomizeActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun saveIconToPrefs(packageName: String, bitmap: Bitmap) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, outputStream)
            val encoded = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
            getSharedPreferences("tile_custom_icons", MODE_PRIVATE).edit()
                .putString("bg_" + packageName, encoded)
                .apply()
        } catch (e: Exception) {}
    }
}
