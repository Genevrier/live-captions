package com.asr.live.pipeline

enum class ComparisonEngine { OPUS, HY_MT2 }
enum class TranslationVote { OPUS, HY_MT2, TIE }

data class TranslationComparison(
    val key: SegmentKey,
    val source: String,
    val opusText: String? = null,
    val opusMs: Long? = null,
    val hyText: String? = null,
    val hyMs: Long? = null,
    val vote: TranslationVote? = null,
)

/** In-memory A/B results keyed by the exact same stable ASR text/revision. */
class TranslationComparisonLedger(private val maxRows: Int = 8) {
    private val rows = linkedMapOf<SegmentKey, TranslationComparison>()

    @Synchronized fun record(key: SegmentKey, source: String, engine: ComparisonEngine,
                             output: String, elapsedMs: Long): TranslationComparison {
        val row = rows[key]?.takeIf { it.source == source } ?: TranslationComparison(key, source)
        val updated = when (engine) {
            ComparisonEngine.OPUS -> row.copy(opusText = output, opusMs = elapsedMs)
            ComparisonEngine.HY_MT2 -> row.copy(hyText = output, hyMs = elapsedMs)
        }
        rows[key] = updated
        while (rows.size > maxRows) rows.remove(rows.keys.first())
        return updated
    }

    @Synchronized fun vote(key: SegmentKey, choice: TranslationVote): Boolean {
        val row = rows[key] ?: return false
        if (row.opusText == null || row.hyText == null) return false
        rows[key] = row.copy(vote = choice)
        return true
    }

    @Synchronized fun snapshot(): List<TranslationComparison> = rows.values.toList()
    @Synchronized fun clear() = rows.clear()
}
