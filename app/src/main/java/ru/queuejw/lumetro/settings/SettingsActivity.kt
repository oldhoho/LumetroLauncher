package ru.queuejw.lumetro.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import ru.queuejw.lumetro.components.prefs.Prefs
import ru.queuejw.lumetro.components.ui.metro.MetroSwitch
import ru.queuejw.lumetro.databinding.SettingsTilesBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsTilesBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsTilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        // 角落半径滑块
        binding.cornerRadiusSlider.value = prefs.tileCornerRadius.toFloat()
        binding.cornerRadiusSlider.addOnChangeListener { _, value, _ ->
            prefs.tileCornerRadius = value.toInt()
        }

        // 锁定磁贴开关
        binding.lockTilesSwitch.isChecked = prefs.tilesLocked
        binding.lockTilesSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.tilesLocked = isChecked
        }
    }
}