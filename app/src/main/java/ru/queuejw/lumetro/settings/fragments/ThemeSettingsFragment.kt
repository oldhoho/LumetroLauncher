package ru.queuejw.lumetro.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.prefs.Prefs

class ThemeSettingsFragment : Fragment() {
    private val prefs by lazy { Prefs(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.settings_theme, container, false)
    }
}
