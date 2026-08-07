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

class GroupedAppListAdapter(
    private val context: Context,
    private var items: List<GroupItem>,
    private val iconLoader: IconLoader,
    private val coroutineScope: CoroutineScope,
    private val iconCache: MutableMap<String, Bitmap>,
    private val onAppClick: (App) -> Unit,
    private val onAppLongClick: (App, View) -> Unit,
    private val onFreezeClick: () -> Unit,
    private val onSettingsClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
        private const val TYPE_BUTTON = 2
    }

    sealed class GroupItem {
        data class Header(val letter: String) : GroupItem()
        data class AppItem(val app: App) : GroupItem()
        object FreezeButton : GroupItem()
        object SettingsButton : GroupItem()
    }

    fun updateData(newItems: List<GroupItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GroupItem.Header -> TYPE_HEADER
            is GroupItem.AppItem -> TYPE_APP
            is GroupItem.FreezeButton -> TYPE_BUTTON
            is GroupItem.SettingsButton -> TYPE_BUTTON
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val tv = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        40.dpToPx()
                    )
                    setPadding(16.dpToPx(), 0, 0, 0)
                    gravity = Gravity.CENTER_VERTICAL
                    textSize = 16f
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    setBackgroundColor(Color.parseColor("#FF222222"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                HeaderViewHolder(tv)
            }
            TYPE_APP -> {
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
                AppViewHolder(container, icon, label)
            }
            else -> {
                val container = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        48.dpToPx()
                    )
                    setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#FF333333"))
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
                }

                container.addView(icon)
                container.addView(label)
                ButtonViewHolder(container, icon, label)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        
        when {
            holder is HeaderViewHolder && item is GroupItem.Header -> {
                holder.textView.text = item.letter
            }
            holder is AppViewHolder && item is GroupItem.AppItem -> {
                val app = item.app
                holder.label.text = app.mName
                
                val isFrozen = app.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
                holder.container.setBackgroundColor(if (isFrozen) Color.parseColor("#33AADDFF") else Color.TRANSPARENT)
                holder.container.alpha = if (isFrozen) 0.6f else 1f

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
                                    val scaled = Bitmap.createScaledBitmap(bmp, 36.dpToPx(), 36.dpToPx(), true)
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
            holder is ButtonViewHolder -> {
                when (position) {
                    0 -> {
                        holder.icon.setImageResource(android.R.drawable.ic_lock_lock)
                        holder.label.text = "❄ 一键冻结"
                        holder.container.setOnClickListener { onFreezeClick() }
                    }
                    1 -> {
                        holder.icon.setImageResource(android.R.drawable.ic_menu_manage)
                        holder.label.text = "⚙ 设置"
                        holder.container.setOnClickListener { onSettingsClick() }
                    }
                }
            }
        }
    }

    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    class AppViewHolder(val container: LinearLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(container)
    class ButtonViewHolder(val container: LinearLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(container)

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}