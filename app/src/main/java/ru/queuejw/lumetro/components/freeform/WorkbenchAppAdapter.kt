package ru.queuejw.lumetro.components.freeform

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.queuejw.lumetro.R

class WorkbenchAppAdapter(
    private val apps: List<WorkbenchActivity.AppItem>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<WorkbenchAppAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workbench_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.bind(app)
    }

    override fun getItemCount(): Int = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.app_icon)
        private val nameView: TextView = itemView.findViewById(R.id.app_name)

        fun bind(app: WorkbenchActivity.AppItem) {
            iconView.setImageDrawable(app.icon)
            nameView.text = app.name

            itemView.setOnClickListener {
                onItemClick(app.packageName)
            }
        }
    }
}
