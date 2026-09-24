package com.asr.live.service

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import java.util.concurrent.TimeUnit

/** Best-effort ADPF deadline feedback for a long-lived inference worker thread. */
@Suppress("NewApi")
internal class WorkerPerformanceHint(context: Context, targetMs: Long) : AutoCloseable {
    private val session: PerformanceHintManager.Session? = if (Build.VERSION.SDK_INT >= 31) runCatching {
        context.getSystemService(PerformanceHintManager::class.java)
            ?.createHintSession(intArrayOf(Process.myTid()), TimeUnit.MILLISECONDS.toNanos(targetMs))
    }.getOrNull() else null
    val enabled: Boolean get() = session != null

    fun report(durationMs: Long) {
        if (durationMs <= 0L) return
        runCatching { session?.reportActualWorkDuration(TimeUnit.MILLISECONDS.toNanos(durationMs)) }
    }

    override fun close() { runCatching { session?.close() } }
}
