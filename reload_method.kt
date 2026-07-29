
    fun reloadIconPack() {
        coroutineScope.launch(Dispatchers.IO) {
            iconLoader.resetIconLoader(true)
            val tiles = db?.getTilesDao()?.getTilesData() ?: emptyList()
            tiles.filter { it.tileType != -1 && !it.tilePackage.isNullOrEmpty() }.forEach {
                iconLoader.getIconForPackage(context, it.tilePackage!!)
            }
            cachedApps.forEach { app ->
                app.mPackage?.let { iconLoader.getIconForPackage(context, it) }
            }
            withContext(Dispatchers.Main) {
                refreshTiles()
                if (currentLevel == PanelLevel.APPS) loadAppsContent()
            }
        }
    }
