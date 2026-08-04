package ru.queuejw.lumetro.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.databinding.ActivitySettingsBinding
import ru.queuejw.lumetro.settings.fragments.IconSettingsFragment
import ru.queuejw.lumetro.settings.fragments.ThemeSettingsFragment

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent.getBooleanExtra("open_icons", false)) {
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainerView.id, IconSettingsFragment())
                .commit()
        } else {
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainerView.id, ThemeSettingsFragment())
                .commit()
        }
    }
}
