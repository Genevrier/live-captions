package com.asr.live.service

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.system.Os
import android.system.OsConstants

/** All values are snapshots in KiB. Model bytes are file sizes, not additional RSS. */
data class MemorySnapshot(
    val appPssKb: Long,
    val rssKb: Long,
    val nativeHeapKb: Long,
    val javaHeapKb: Long,
    val availableKb: Long,
    val thermalStatus: String,
)

object MemoryUsage {
    fun sample(context: Context): MemorySnapshot {
        val manager = context.getSystemService(ActivityManager::class.java)
        val own = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
        val additional = runCatching {
            val pids = manager.runningAppProcesses.orEmpty().filter {
                it.uid == Process.myUid() && it.pid != Process.myPid()
            }.map { it.pid }.toIntArray()
            if (pids.isEmpty()) 0L else manager.getProcessMemoryInfo(pids).sumOf { it.totalPss.toLong() }
        }.getOrDefault(0L)
        val rss = runCatching {
            val pages = java.io.File("/proc/self/statm").readText().trim().split(Regex("\\s+"))[1].toLong()
            pages * Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
        }.getOrDefault(0L)
        val runtime = Runtime.getRuntime()
        val system = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val status = context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus
            when (status) {
                android.os.PowerManager.THERMAL_STATUS_NONE -> "None"
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else "Unavailable"
        return MemorySnapshot(own + additional, rss, Debug.getNativeHeapAllocatedSize() / 1024,
            (runtime.totalMemory() - runtime.freeMemory()) / 1024, system.availMem / 1024, thermal)
    }

    /** Refuse an optional large model if Android cannot retain a 2 GiB system reserve. */
    fun canLoad(context: Context, modelBytes: Long, reserveBytes: Long = 2L * 1024 * 1024 * 1024): Boolean {
        val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return !info.lowMemory && info.availMem > modelBytes + reserveBytes
    }
}
