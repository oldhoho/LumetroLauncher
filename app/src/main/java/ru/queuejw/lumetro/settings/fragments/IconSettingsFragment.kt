package ru.queuejw.lumetro.settings.fragments

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.components.core.icons.IconPackManager
import ru.queuejw.lumetro.components.prefs.Prefs

class IconSettingsFragment : Fragment() {
    private val prefs by lazy { Prefs(requireContext()) }
    private var iconPackListView: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
        }

        layout.addView(createTitle("图标包"))
        layout.addView(createCurrentPackInfo())
        layout.addView(createButton("选择图标包") { showIconPackList() })
        layout.addView(createButton("清除图标包") {
            prefs.iconPackPackage = null
            requireContext().sendBroadcast(Intent("ru.queuejw.lumetro.RELOAD_ICONS"))
            refreshCurrentPackInfo()
            Toast.makeText(requireContext(), "已清除图标包", Toast.LENGTH_SHORT).show()
        })

        iconPackListView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        layout.addView(iconPackListView)

        return layout
    }

    private fun createTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
    }

    private fun createCurrentPackInfo(): TextView {
        val tv = TextView(requireContext()).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 16)
        }
        tv.tag = "current_pack"
        refreshCurrentPackInfo(tv)
        return tv
    }

    private fun refreshCurrentPackInfo(tv: TextView? = null) {
        val target = tv ?: view?.findViewWithTag<TextView>("current_pack") ?: return
        if (prefs.iconPackPackage != null) {
            target.text = "当前图标包: ${prefs.iconPackPackage}"
        } else {
            target.text = "未使用图标包"
        }
    }

    private fun createButton(text: String, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#FF333333"))
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 8)
        }
    }

    private fun showIconPackList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val iconPacks = IconPackManager(requireContext()).getAvailableIconPacks(true).sortedBy { it.name }
            withContext(Dispatchers.Main) {
                iconPackListView?.removeAllViews()
                iconPackListView?.addView(createTitle("可用图标包"))
                if (iconPacks.isEmpty()) {
                    iconPackListView?.addView(TextView(requireContext()).apply {
                        text = "未找到任何图标包"
                        setTextColor(Color.parseColor("#888888"))
                        setPadding(0, 16, 0, 0)
                    })
                } else {
                    iconPacks.forEach { pack ->
                        iconPackListView?.addView(createButton(pack.name ?: pack.packageName ?: "未知") {
                            prefs.iconPackPackage = pack.packageName
                            requireContext().sendBroadcast(Intent("ru.queuejw.lumetro.RELOAD_ICONS"))
                            refreshCurrentPackInfo()
                            Toast.makeText(requireContext(), "已选择: ${pack.name}", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            }
        }
    }
}