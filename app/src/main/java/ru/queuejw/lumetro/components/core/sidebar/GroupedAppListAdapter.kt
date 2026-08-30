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
import java.lang.ref.WeakReference

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
        private const val LOOP_MULTIPLIER = 100
        private const val BASE_ICON_SIZE_DP = 36
        private const val GRID_HEIGHT_DP = 64  // 固定格子高度
        private const val HEADER_HEIGHT_DP = 28
        private const val BASE_PADDING_DP = 16
    }

    sealed class GroupItem {
        data class Header(val letter: String) : GroupItem()
        data class AppItem(val app: App, val score: Int = 0) : GroupItem()
        object FreezeButton : GroupItem()
        object SettingsButton : GroupItem()
    }

    private var layoutManagerRef: WeakReference<ScaleLayoutManager>? = null
    private var isLoopEnabled = false
    private var isResetting = false
    private var resetJob: Job? = null

    fun updateData(newItems: List<GroupItem>) {
        items = newItems
        notifyDataSetChanged()
        resetJob?.cancel()
        resetJob = coroutineScope.launch {
            delay(150)
            if (!isResetting && isLoopEnabled) {
                resetToCenter()
            }
        }
        layoutManagerRef?.get()?.setScaleEnabled(true)
    }

    fun setLoopEnabled(enabled: Boolean) {
        isLoopEnabled = enabled
        if (enabled && items.isNotEmpty()) {
            resetToCenter()
        } else if (!enabled) {
            notifyDataSetChanged()
        }
    }

    fun resetToCenter() {
        if (isLoopEnabled && items.isNotEmpty() && !isResetting) {
            isResetting = true
            val targetPosition = (items.size * LOOP_MULTIPLIER / 2)
            
            layoutManagerRef?.get()?.let { lm ->
                lm.scrollToPosition(targetPosition)
            }
            
            coroutineScope.launch {
                delay(100)
                isResetting = false
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val lm = recyclerView.layoutManager
        if (lm is ScaleLayoutManager) {
            layoutManagerRef = WeakReference(lm)
            lm.setScaleEnabled(true)
            
            if (isLoopEnabled && items.isNotEmpty()) {
                recyclerView.post {
                    resetToCenter()
                }
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        layoutManagerRef = null
        resetJob?.cancel()
        resetJob = null
    }

    override fun getItemViewType(position: Int): Int {
        val realPosition = getRealPosition(position)
        if (realPosition < 0 || realPosition >= items.size) return TYPE_APP
        return when (items[realPosition]) {
            is GroupItem.Header -> TYPE_HEADER
            is GroupItem.AppItem -> TYPE_APP
            is GroupItem.FreezeButton, is GroupItem.SettingsButton -> TYPE_BUTTON
        }
    }

    override fun getItemCount(): Int {
        return if (isLoopEnabled && items.isNotEmpty()) {
            items.size * LOOP_MULTIPLIER
        } else {
            items.size
        }
    }

    private fun getRealPosition(position: Int): Int {
        if (items.isEmpty()) return -1
        return if (isLoopEnabled) {
            position % items.size
        } else {
            position
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val tv = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        HEADER_HEIGHT_DP.dpToPx()
                    )
                    gravity = Gravity.CENTER
                    textSize = 12f
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
                        GRID_HEIGHT_DP.dpToPx()  // 固定格子高度
                    )
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL  // 底部居中
                    setPadding(BASE_PADDING_DP.dpToPx(), 0, BASE_PADDING_DP.dpToPx(), 4.dpToPx())
                    clipChildren = false
                    clipToPadding = false
                }

                val icon = ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        BASE_ICON_SIZE_DP.dpToPx(), 
                        BASE_ICON_SIZE_DP.dpToPx()
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val label = TextView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
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
                        GRID_HEIGHT_DP.dpToPx()  // 固定格子高度
                    )
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL  // 底部居中
                    setPadding(BASE_PADDING_DP.dpToPx(), 0, BASE_PADDING_DP.dpToPx(), 4.dpToPx())
                    setBackgroundColor(Color.parseColor("#FF333333"))
                    clipChildren = false
                    clipToPadding = false
                }

                val icon = ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        BASE_ICON_SIZE_DP.dpToPx(), 
                        BASE_ICON_SIZE_DP.dpToPx()
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val label = TextView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
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
        val realPosition = getRealPosition(position)
        if (realPosition < 0 || realPosition >= items.size) return
        
        val item = items[realPosition]

        when {
            holder is HeaderViewHolder && item is GroupItem.Header -> {
                holder.textView.text = item.letter
            }
            holder is AppViewHolder && item is GroupItem.AppItem -> {
                val app = item.app
                holder.label.text = app.mName

                val isFrozen = app.mPackage?.let { FreezeManager.isFrozen(context, it) } ?: false
                holder.container.setBackgroundColor(Color.TRANSPARENT)
                holder.icon.alpha = if (isFrozen) 0.4f else 1.0f
                holder.label.alpha = if (isFrozen) 0.4f else 1.0f

                app.mPackage?.let { pkg ->
                    val cached = iconCache[pkg]
                    if (cached != null) {
                        holder.icon.setImageBitmap(cached)
                    } else {
                        holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
                        coroutineScope.launch(Dispatchers.IO) {
                            val bmp = iconLoader.getIconForPackage(context, pkg)
                            withContext(Dispatchers.Main) {
                                if (bmp != null && holder.adapterPosition != RecyclerView.NO_POSITION) {
                                    val iconSize = BASE_ICON_SIZE_DP.dpToPx()
                                    val scaled = Bitmap.createScaledBitmap(
                                        bmp,
                                        iconSize,
                                        iconSize,
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
            holder is ButtonViewHolder -> {
                when (item) {
                    is GroupItem.FreezeButton -> {
                        holder.icon.setImageResource(android.R.drawable.ic_lock_lock)
                        holder.label.text = "❄ 一键冻结"
                        holder.container.setOnClickListener { onFreezeClick() }
                    }
                    is GroupItem.SettingsButton -> {
                        holder.icon.setImageResource(android.R.drawable.ic_menu_manage)
                        holder.label.text = "⚙ 设置"
                        holder.container.setOnClickListener { onSettingsClick() }
                    }
                    else -> {}
                }
            }
        }
    }

    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    class AppViewHolder(
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(container)
    class ButtonViewHolder(
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(container)

    fun clearReferences() {
        layoutManagerRef = null
        resetJob?.cancel()
        resetJob = null
    }

    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}