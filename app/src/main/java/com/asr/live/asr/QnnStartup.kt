package com.asr.live.asr

/**
 * Startup deadlines for the experimental QNN/NPU path.
 *
 * QNN is optional and CPU Nemotron is always available, so a slow or wedged vendor stack must
 * cost an interactive fraction of a second rather than tens of seconds. These bounds are what
 * keeps Listen responsive: the recognition worker runs them inline before the microphone starts,
 * so the total is the worst-case added latency before capture begins.
 */
object QnnStartup {
    /** Binding a same-UID isolated service is local IPC; a slow bind means the stack is unhealthy. */
    const val BIND_TIMEOUT_MS = 3_000L
    /** A trivial round trip that proves the remote process actually answers. */
    const val PID_TIMEOUT_MS = 1_000L
    /** Graph preparation and DSP setup; beyond this CPU wins on time-to-first-caption. */
    const val INITIALIZE_TIMEOUT_MS = 8_000L
    /** Steady-state decode round trip. */
    const val ACCEPT_TIMEOUT_MS = 5_000L
    /** Final flush round trip; bounded so Stop cannot wait on a wedged DSP call. */
    const val FINISH_TIMEOUT_MS = 5_000L

    /** Worst case added to Listen before CPU fallback is chosen. */
    const val TOTAL_STARTUP_BUDGET_MS = BIND_TIMEOUT_MS + PID_TIMEOUT_MS + INITIALIZE_TIMEOUT_MS
}
