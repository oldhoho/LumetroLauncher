package ru.queuejw.lumetro.components.freeform

data class WorkbenchState(
    val isVisible: Boolean = false,
    val isAppsListVisible: Boolean = false,
    val currentPage: Int = 0,
    val currentMode: WorkbenchMode = WorkbenchMode.NORMAL,
    val foregroundApp: String? = null,
    val lastUpdateTime: Long = System.currentTimeMillis()
)

enum class WorkbenchMode {
    NORMAL,
    ADD,
    REMOVE
}

data class WorkbenchStateChange(
    val oldState: WorkbenchState,
    val newState: WorkbenchState,
    val changeType: WorkbenchChangeType
)

enum class WorkbenchChangeType {
    SHOW,
    HIDE,
    PAGE_CHANGE,
    MODE_CHANGE,
    FOREGROUND_APP_CHANGE,
    APPS_LIST_SHOW,
    APPS_LIST_HIDE
}