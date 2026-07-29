package ru.queuejw.lumetro.components.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import java.io.ByteArrayOutputStream

class PanelBgPickerActivity : Activity() {
    private var tilePackage: String? = null
    private var target: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tilePackage = intent.getStringExtra("tile_package")
        target = intent.getStringExtra("target") ?: "panel_bg"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val inputStream = contentResolver.openInputStream(data.data!!)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 480, 800, true)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                    when {
                        tilePackage != null -> {
                            // 磁贴背景图
                            getSharedPreferences("tile_custom_icons", MODE_PRIVATE)
                                .edit().putString("bg_$tilePackage", base64).apply()
                            sendBroadcast(Intent("ru.queuejw.lumetro.UPDATE_TILES"))
                        }
                        target == "main_bg" -> {
                            // 主页壁纸
                            getSharedPreferences("settings", MODE_PRIVATE)
                                .edit().putString("main_bg_image", base64).apply()
                            sendBroadcast(Intent("ru.queuejw.lumetro.UPDATE_MAIN_BG"))
                        }
                        else -> {
                            // 面板背景图
                            getSharedPreferences("settings", MODE_PRIVATE)
                                .edit().putString("panel_bg_image", base64).apply()
                            sendBroadcast(Intent("ru.queuejw.lumetro.UPDATE_PANEL_BG"))
                        }
                    }
                    Toast.makeText(this, "背景已设置", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}