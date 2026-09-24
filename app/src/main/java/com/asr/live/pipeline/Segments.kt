package com.asr.live.pipeline

enum class CaptionStage { PROVISIONAL, FINAL, REVISED, SKIPPED, CANCELLED }
data class SegmentKey(val session: Long, val id: Long, val revision: Int)
data class Caption(
    val key: SegmentKey,
    val source: String,
    val stableSource: String = "",
    val translation: String = "",
    val stage: CaptionStage = CaptionStage.PROVISIONAL,
    val endpointAtMs: Long? = null,
    val translatedRevision: Int = -1,
    val qualityRank: Int = -1,
    val detail: String = "",
)

/** Two consecutive hypotheses must agree on a complete word before translating it. */
class StablePrefix {
    private var previous = ""
    private var committed = ""
    fun accept(hypothesis: String): String {
        val text = hypothesis.trim()
        val common = previous.zip(text).takeWhile { it.first == it.second }.size
        var end = common
        if (common < text.length && !text[common].isWhitespace()) {
            while (end > 0 && !text[end - 1].isWhitespace() && text[end - 1] !in "。！？.!?,，") end--
        }
        val candidate = text.take(end).trimEnd()
        // A revised earlier word invalidates the old prefix; never fabricate a hybrid sentence.
        committed = if (text.startsWith(committed) && candidate.length < committed.length) committed else candidate
        previous = text
        return committed
    }
    fun reset() { previous = ""; committed = "" }
}

/** Session and exact revision matching prevents old native work from updating a new session. */
class SegmentLedger(private val maxCaptions: Int = 150) {
    private var session = 0L
    private var nextId = 1L
    private var active: Long? = null
    private val captions = linkedMapOf<Long, Caption>()
    private val stable = StablePrefix()
    @Synchronized fun start(generation: Long) {
        cancel()
        session = generation
    }
    @Synchronized fun source(generation: Long, text: String, endpoint: Boolean, nowMs: Long): Caption? {
        if (generation != session || text.isBlank()) return null
        val id = active ?: nextId++.also { active = it }
        val old = captions[id]
        if (old?.source == text.trim() && !endpoint) return old
        val key = SegmentKey(session, id, (old?.key?.revision ?: -1) + 1)
        val row = Caption(key, text.trim(), if (endpoint) text.trim() else stable.accept(text),
            translation = old?.translation ?: "", endpointAtMs = if (endpoint) nowMs else null,
            translatedRevision = old?.translatedRevision ?: -1,
            detail = if (endpoint) "Awaiting final translation" else "Live hypothesis")
        captions[id] = row
        if (endpoint) { active = null; stable.reset() }
        while (captions.size > maxCaptions) captions.remove(captions.keys.first())
        return row
    }
    @Synchronized fun translate(key: SegmentKey, text: String, rank: Int, final: Boolean): Boolean {
        val old = captions[key.id] ?: return false
        if (key.session != session || old.key != key || rank < old.qualityRank || old.stage == CaptionStage.CANCELLED) return false
        captions[key.id] = old.copy(translation = text, qualityRank = rank, translatedRevision = key.revision,
            stage = if (final) CaptionStage.FINAL else CaptionStage.PROVISIONAL,
            detail = if (final) "Final" else "Provisional")
        return true
    }
    @Synchronized fun skip(key: SegmentKey, reason: String) {
        val old = captions[key.id] ?: return
        if (old.key == key && key.session == session && old.stage == CaptionStage.PROVISIONAL)
            captions[key.id] = old.copy(stage = CaptionStage.SKIPPED, detail = reason)
    }
    @Synchronized fun current(key: SegmentKey) = key.session == session && captions[key.id]?.key == key &&
        captions[key.id]?.stage != CaptionStage.CANCELLED
    @Synchronized fun snapshot() = captions.values.toList()
    @Synchronized fun cancel() {
        captions.replaceAll { _, c -> if (c.stage == CaptionStage.PROVISIONAL) c.copy(stage = CaptionStage.CANCELLED, detail = "Stopped") else c }
        active = null
        stable.reset()
        session = -1
    }
    @Synchronized fun clear() { captions.clear(); active = null; stable.reset() }
}
