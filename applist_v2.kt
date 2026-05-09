
    inner class AppListAdapter(private var apps: List<App>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = -1
            private const val TYPE_APP = 0
        }

        inner class AppViewHolder(
            val container: LinearLayout,
            val icon: ImageView,
            val label: TextView
        ) : RecyclerView.ViewHolder(container)

        inner class HeaderViewHolder(
            val container: LinearLayout,
            val letter: TextView
        ) : RecyclerView.ViewHolder(container)

        fun updateData(newApps: List<App>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return apps[position].viewType
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val container = LinearLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        40.dpToPx()
                    )
                    setPadding(16.dpToPx(), 4.dpToPx(), 0, 4.dpToPx())
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#FF333333"))
                }
                val letterView = TextView(parent.context).apply {
                    textSize = 22f
                    setTextColor(Color.CYAN)
                }
                container.addView(letterView)
                return HeaderViewHolder(container, letterView)
            }

            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                gravity = Gravity.CENTER_VERTICAL
            }

            val iconView = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val labelView = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = 12.dpToPx()
                }
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
            }

            container.addView(iconView)
            container.addView(labelView)

            return AppViewHolder(container, iconView, labelView)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val app = apps[position]

            if (holder is HeaderViewHolder) {
                holder.letter.text = app.mName.uppercase()
                return
            }

            val appHolder = holder as AppViewHolder
            appHolder.label.text = app.mName
            appHolder.icon.setImageResource(android.R.drawable.sym_def_app_icon)

            val pkg = app.mPackage
            if (!pkg.isNullOrEmpty()) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val icon = iconLoader.getIconForPackage(context, pkg)
                        if (icon != null) {
                            val scaledBitmap = Bitmap.createScaledBitmap(icon, 40.dpToPx(), 40.dpToPx(), true)
                            withContext(Dispatchers.Main) {
                                appHolder.icon.setImageBitmap(scaledBitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SidebarManager", "Failed to load icon for $pkg", e)
                    }
                }
            }

            appHolder.container.setOnClickListener {
                animateAppLaunch(app, appHolder)
            }

            appHolder.container.setOnLongClickListener {
                showAppPopup(appHolder.container, app)
                true
            }
        }

        override fun getItemCount(): Int = apps.size
    }

    private fun animateAppLaunch(app: App, holder: AppListAdapter.AppViewHolder) {
        holder.container.animate()
            .translationX(-100f)
            .alpha(0.5f)
            .setDuration(150)
            .withEndAction {
                coroutineScope.launch {
                    try {
                        AppManager.launchApp(app.mPackage, context)
                    } catch (e: Exception) {
                        Log.e("SidebarManager", "Failed to launch app", e)
                    }
                    hidePanel()
                }
            }
            .start()
    }
