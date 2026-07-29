
    private fun loadAppsContent() {
        val container = contentContainer ?: return
        container.removeAllViews()

        val searchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.DKGRAY)
        }

        val sortLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }

        val nameBtn = TextView(context).apply {
            text = "按名称"
            textSize = 12f
            setTextColor(if (appSortMode == AppSortMode.NAME) Color.CYAN else Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                appSortMode = AppSortMode.NAME
                refreshApps()
            }
        }
        sortLayout.addView(nameBtn)

        val usageBtn = TextView(context).apply {
            text = "按使用频率"
            textSize = 12f
            setTextColor(if (appSortMode == AppSortMode.USAGE) Color.CYAN else Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    appSortMode = AppSortMode.USAGE
                    refreshApps()
                } else {
                    Toast.makeText(context, "需要 Android 5.0 以上", Toast.LENGTH_SHORT).show()
                }
            }
        }
        sortLayout.addView(usageBtn)

        searchLayout.addView(sortLayout)

        val searchBox = EditText(context).apply {
            hint = "搜索应用..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
                false
            }
        }
        searchLayout.addView(searchBox)

        appsRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        searchLayout.addView(appsRecyclerView)

        container.addView(searchLayout)

        val sortedApps = sortApps(cachedApps)
        appAdapter = AppListAdapter(sortedApps)
        appsRecyclerView?.layoutManager = LinearLayoutManager(context)
        appsRecyclerView?.adapter = appAdapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase() ?: ""
                val baseList = sortApps(cachedApps)
                val filtered = baseList.filter { it.mName.lowercase().contains(query) }
                appAdapter?.updateData(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun refreshApps() {
        coroutineScope.launch {
            cachedApps = withContext(Dispatchers.IO) {
                appManager.getInstalledApps(context, true)
            }
            withContext(Dispatchers.IO) {
                cachedApps.forEach { app ->
                    app.mPackage?.let { iconLoader.getIconForPackage(context, it) }
                }
            }
            if (currentLevel == PanelLevel.APPS) {
                loadAppsContent()
            }
        }
    }

    inner class AppListAdapter(private var apps: List<App>) :
        RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        inner class ViewHolder(
            val container: LinearLayout,
            val icon: ImageView,
            val label: TextView
        ) : RecyclerView.ViewHolder(container)

        fun updateData(newApps: List<App>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
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

            return ViewHolder(container, iconView, labelView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.label.text = app.mName

            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)

            val pkg = app.mPackage
            if (!pkg.isNullOrEmpty()) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val icon = iconLoader.getIconForPackage(context, pkg)
                        if (icon != null) {
                            val scaledBitmap = Bitmap.createScaledBitmap(icon, 40.dpToPx(), 40.dpToPx(), true)
                            withContext(Dispatchers.Main) {
                                holder.icon.setImageBitmap(scaledBitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SidebarManager", "Failed to load icon for $pkg", e)
                    }
                }
            }

            holder.container.setOnClickListener {
                animateAppLaunch(app, holder)
            }

            holder.container.setOnLongClickListener {
                showAppPopup(holder.container, app)
                true
            }
        }

        override fun getItemCount(): Int = apps.size
    }

    private fun animateAppLaunch(app: App, holder: AppListAdapter.ViewHolder) {
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

    private fun showAppPopup(anchor: View, app: App) {
        currentPopup?.dismiss()

        val popupView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(8, 8, 8, 8)
        }

        val pinBtn = TextView(context).apply {
            text = "固定到开始屏幕"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                currentPopup?.dismiss()
                pinApp(app)
            }
        }
        popupView.addView(pinBtn)

        val infoBtn = TextView(context).apply {
            text = "应用信息"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                currentPopup?.dismiss()
                openAppInfo(app)
            }
        }
        popupView.addView(infoBtn)

        val uninstallBtn = TextView(context).apply {
            text = "卸载"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                currentPopup?.dismiss()
                uninstallApp(app)
            }
        }
        popupView.addView(uninstallBtn)

        currentPopup = PopupWindow(popupView, 250.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        currentPopup?.setBackgroundDrawable(
            ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame)
        )
        currentPopup?.showAsDropDown(anchor, 0, -anchor.height)
    }
