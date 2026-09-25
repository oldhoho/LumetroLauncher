package ru.queuejw.lumetro.components.freeform.util

/**
 * 内存环形日志缓冲。只保留最近 N 条，不写磁盘。
 * 崩溃时由 CrashLogger 一次性 dump。
 */
object LogBuffer {

    private const val MAX_SIZE = 200

    private val buffer = ArrayDeque<String>(MAX_SIZE + 1)

    @Synchronized
    fun add(tag: String, message: String) {
        val time = android.text.format.DateFormat.format("HH:mm:ss.SSS", System.currentTimeMillis())
        buffer.addLast("[$time][$tag] $message")
        while (buffer.size > MAX_SIZE) {
            buffer.removeFirst()
        }
    }

    @Synchronized
    fun dump(): String {
        if (buffer.isEmpty()) return "(no buffered logs)\n"
        val sb = StringBuilder()
        sb.append("---- buffered logs (last ${buffer.size}) ----\n")
        for (line in buffer) {
            sb.append(line).append('\n')
        }
        sb.append("-------------------------------------------\n")
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}