package com.asr.live.service

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process

object MemoryUsage {
    /** Approximate proportional RAM, including the same-UID QNN worker when present. */
    fun sample(context: Context): Pair<Long, Long> {
        val own = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
        val additional = runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val pids = manager.runningAppProcesses.orEmpty().filter {
                it.uid == Process.myUid() && it.pid != Process.myPid()
            }.map { it.pid }.toIntArray()
            if (pids.isEmpty()) 0L else manager.getProcessMemoryInfo(pids).sumOf { it.totalPss.toLong() }
        }.getOrDefault(0L)
        return own + additional to Debug.getNativeHeapAllocatedSize() / 1024
    }
}
