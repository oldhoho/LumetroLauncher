package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import ru.queuejw.lumetro.components.core.icons.IconLoader
import ru.queuejw.lumetro.components.freeze.FreezeManager
import ru.queuejw.lumetro.model.App

class AppListAdapter(
    private val context: Context,
    private var apps: List<App>,
    private val iconLoader: IconLoader,
    private val coroutineScope: CoroutineScope,
    private val iconCache: MutableMap<String, Bitmap>,
    private val onAppClick: (App) -> Unit,
    private val onAppLongClick: (App, View) -> Unit,
    private val onFreezeClick: () -> Unit,
    private val onSettingsClick: () -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val colorFrozen = Color.parseColor("#33AADDFF")
    private val colorButtonBg = Color.parseColor("#FF333333")

    inner class ViewHolder(
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(container)

    fun updateData(newApps: List<App>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            )
            setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val label = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(12.dpToPx(), 0, 0, 0)
            }
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        container.addView(icon)
        container.addView(label)
        return ViewHolder(container, icon, label)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.label.text = app.mName

        // 特殊按钮：冻结
        if (app.mPackage == "freeze_button") {
            holder.container.setBackgroundColor(colorButtonBg)
            holder.icon.setImageResource(android.R.drawable.ic_lock_lock)
            holder.label.text = "❄ 一键冻结"
            holder.container.setOnClickListener { onFreezeClick() }
            holder.container.setOnLongClickListener { true }
            return
        }

        // 特殊按钮：设置
        if (app.mPackage == "settings_button") {
            holder.container.setBackgroundColor(colorButtonBg)
            holder.icon.setImageResource(android.R.drawable.ic_menu_manage)
            holder.label.text = "⚙ 设置"
            holder.container.setOnClickListener { onSettingsClick() }
            holder.container.setOnLongClickListener { true }
            return
        }

        // 普通应用
        val isFrozen = app.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
        holder.container.setBackgroundColor(if (isFrozen) colorFrozen else Color.TRANSPARENT)
        holder.container.alpha = if (isFrozen) 0.6f else 1f

        // 加载图标
        app.mPackage?.let { pkg ->
            val cached = iconCache[pkg]
            if (cached != null) {
                holder.icon.setImageBitmap(cached)
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
                coroutineScope.launch(Dispatchers.IO) {
                    val bmp = iconLoader.getIconForPackage(context, pkg)
                    withContext(Dispatchers.Main) {
                        if (bmp != null) {
                            val scaled = Bitmap.createScaledBitmap(
                                bmp,
                                36.dpToPx(),
                                36.dpToPx(),
                                true
                            )
                            iconCache[pkg] = scaled
                            holder.icon.setImageBitmap(scaled)
                        }
                    }
                }
            }
        }

        holder.container.setOnClickListener {
            onAppClick(app)
        }

        holder.container.setOnLongClickListener {
            onAppLongClick(app, holder.container)
            true
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
