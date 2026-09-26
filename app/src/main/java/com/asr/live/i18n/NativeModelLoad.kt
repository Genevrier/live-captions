package com.asr.live.i18n

/**
 * Cancellation token for one native model load.
 *
 * It exists before the load call is made, which is what makes a Stop during a multi-GB GGUF
 * read actionable: the translator handle does not exist yet, so per-request cancellation
 * cannot help. Cancelling this token makes the llama.cpp model-load progress callback return
 * false and the load aborts.
 */
interface ModelLoadControl {
    /** Native identifier passed to the loader; 0 when no token could be created. */
    val handle: Long
    fun cancel()
    fun release()
}

/** Registry-backed native token. cancel/release are safe in any order and release is idempotent. */
class NativeModelLoad : ModelLoadControl {
    private var id: Long = createControl()
    override val handle: Long get() = synchronized(this) { id }
    @Synchronized override fun cancel() { if (id != 0L) cancelControl(id) }
    @Synchronized override fun release() { if (id != 0L) { releaseControl(id); id = 0L } }
    private external fun createControl(): Long
    private external fun cancelControl(id: Long)
    private external fun releaseControl(id: Long)
    companion object { init { System.loadLibrary("live-translator") } }
}

/**
 * Serializes token retirement against the load call that owns it, mirroring the native
 * shared-ownership rule on the Java side so the ordering is deterministic and testable:
 *
 *  - [cancel] is accepted before, during and after the load.
 *  - [release] runs exactly once and never while the load still holds the token.
 */
class ModelLoadScope(private val control: ModelLoadControl) {
    private var loading = false
    private var releaseRequested = false
    private var cancelRequested = false
    private var released = false

    /** Marks the token as owned by an in-flight load and returns the handle to pass natively. */
    @Synchronized fun beginLoad(): Long {
        check(!released) { "Model load token was already released" }
        loading = true
        return control.handle
    }

    /** The load call has returned; a release that arrived meanwhile now takes effect. */
    @Synchronized fun endLoad() {
        loading = false
        if (releaseRequested) releaseLocked()
    }

    @Synchronized fun cancel() {
        cancelRequested = true
        if (!released) control.cancel()
    }

    @Synchronized fun release() {
        releaseRequested = true
        if (!loading) releaseLocked()
    }

    @Synchronized fun isReleased() = released
    @Synchronized fun isCancelled() = cancelRequested
    @Synchronized fun isLoading() = loading

    private fun releaseLocked() {
        if (released) return
        released = true
        control.release()
    }
}
