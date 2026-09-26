package com.asr.live.pipeline.engine

import com.asr.live.i18n.LocalTranslator
import com.asr.live.pipeline.Profile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Result of Stage A compatibility/smoke testing for one [EngineCandidate]. */
sealed class SmokeOutcome {
    data class Qualified(val actualBackend: String, val loadMs: Long, val translateMs: Long,
                         val backendMismatch: Boolean) : SmokeOutcome()
    data class Failed(val reason: String) : SmokeOutcome()
    data class Unsupported(val reason: String) : SmokeOutcome()
}

/**
 * A crude, script-aware guard against a translator that loaded but silently echoed or dropped
 * the source instead of translating it. This is a smoke check, not a quality judgment — real
 * quality comes from [ChrFScorer] against the benchmark corpus in Stage B.
 */
fun interface LanguageSmokeCheck { fun looksLikeTargetLanguage(output: String, input: String): Boolean }

object LanguageSmokeChecks {
    private val cjk = Regex("[\\u3400-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]")
    val default = LanguageSmokeCheck { output, input ->
        output.isNotBlank() && output.trim() != input.trim()
    }
    /** English/French/Dutch output must not still be in the CJK source script. */
    val notCjk = LanguageSmokeCheck { output, input -> default.looksLikeTargetLanguage(output, input) && !cjk.containsMatchIn(output) }

    fun forProfile(profile: Profile): LanguageSmokeCheck = if (profile.source == "zh") notCjk else default
}

/**
 * Stage A gate: load, translate a nonempty smoke sentence, check it looks like the target
 * language, cancel a second in-flight request, close, and report the actual backend.
 *
 * This exercises the real [LocalTranslator] contract used by [com.asr.live.service.CaptionSession]
 * — the same interface, not a simplified imitation — so a candidate that passes here has
 * demonstrably loaded, tokenized/templated, translated and cleaned up through the real adapter.
 * It does not, by itself, prove on-device latency or quality; those are Stage B/C/D concerns.
 */
object EngineSmokeGate {
    private const val SMOKE_TEXT = "Good morning, could you please send the report before noon?"
    private const val CANCEL_PROBE_TEXT = "This second request should be cancelled before it finishes."

    fun run(profile: Profile, requestedOpenCl: Boolean,
            check: LanguageSmokeCheck = LanguageSmokeChecks.forProfile(profile),
            createTranslator: () -> LocalTranslator): SmokeOutcome {
        val loadStartedNs = System.nanoTime()
        val translator = try { createTranslator() }
        catch (t: Throwable) { return SmokeOutcome.Failed("load failed: ${t.message}") }
        val loadMs = (System.nanoTime() - loadStartedNs) / 1_000_000L
        val outcome = runCatching {
            val translateStartedNs = System.nanoTime()
            val result = try { translator.translate(SMOKE_TEXT) }
            catch (t: Throwable) { return@runCatching SmokeOutcome.Failed("translation failed: ${t.message}") }
            val translateMs = (System.nanoTime() - translateStartedNs) / 1_000_000L
            if (!check.looksLikeTargetLanguage(result, SMOKE_TEXT))
                return@runCatching SmokeOutcome.Failed("output did not look like the target language: \"${result.take(80)}\"")
            checkCancellation(translator)?.let { return@runCatching it }
            val backend = try { translator.backend } catch (t: Throwable) { return@runCatching SmokeOutcome.Failed("backend report failed: ${t.message}") }
            val mismatch = requestedOpenCl && !backend.contains("OpenCL", ignoreCase = true) &&
                !backend.contains("SDK-managed", ignoreCase = true)
            SmokeOutcome.Qualified(backend, loadMs, translateMs, mismatch)
        }.getOrElse { SmokeOutcome.Failed("unexpected error: ${it.message}") }
        val closeFailure = runCatching { translator.close() }.exceptionOrNull()
        // A failure to translate is more informative than a failure to close afterward; only
        // surface the close failure when the translation itself otherwise looked fine.
        return if (closeFailure != null && outcome is SmokeOutcome.Qualified)
            SmokeOutcome.Failed("cleanup (close) failed: ${closeFailure.message}") else outcome
    }

    /**
     * Starts a request on a worker thread, cancels it almost immediately, and requires the
     * worker to actually finish (not hang) within a bounded window. A translator that ignores
     * cancellation and blocks forever fails Stage A regardless of translation quality.
     */
    private fun checkCancellation(translator: LocalTranslator): SmokeOutcome.Failed? {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread({
            started.countDown()
            // Either a clean result or an exception is an acceptable response to cancellation;
            // what matters is that the call actually returns instead of hanging.
            runCatching { translator.translate(CANCEL_PROBE_TEXT, requestId = CANCEL_PROBE_REQUEST_ID) }
            finished.countDown()
        }, "engine-smoke-cancel-probe").apply { isDaemon = true }
        worker.start()
        if (!started.await(2, TimeUnit.SECONDS)) return SmokeOutcome.Failed("cancellation probe never started")
        translator.cancel(CANCEL_PROBE_REQUEST_ID)
        val settled = finished.await(5, TimeUnit.SECONDS)
        if (!settled) return SmokeOutcome.Failed("translator ignored cancel(requestId) and kept running past its deadline")
        return null
    }

    private const val CANCEL_PROBE_REQUEST_ID = -424_242L
}
