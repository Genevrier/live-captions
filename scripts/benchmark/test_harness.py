import unittest
import tempfile
from pathlib import Path

from prepare_fleurs import choose_rows
from prepare_youtube_robustness import transcript_from_vtt
from run_asr import CHUNK_SAMPLES, StablePrefix, levenshtein, normalized_words, run_one, should_translate
from summarize_asr_sweep import dominates
from summarize_translation import dominates as dominates_translation
import numpy as np


class FrozenCorpusTests(unittest.TestCase):
    def test_duration_stratified_split_is_deterministic(self):
        rows = [{"id": i, "num_samples": (i * 37) % 101 + 1000} for i in range(500)]
        first = choose_rows(rows.copy(), "train", 30)
        second = choose_rows(rows.copy(), "train", 30)
        self.assertEqual([row["id"] for row in first], [row["id"] for row in second])
        self.assertEqual(len(first), 30)


class StreamingMetricTests(unittest.TestCase):
    def test_pareto_dominance_requires_no_metric_regression(self):
        better = {"wer": .1, "cer": .02, "rtf": .1, "stable_source_ms": 100., "rewrites": 1.}
        worse = {"wer": .11, "cer": .02, "rtf": .11, "stable_source_ms": 110., "rewrites": 1.}
        tradeoff = {"wer": .09, "cer": .03, "rtf": .1, "stable_source_ms": 100., "rewrites": 1.}
        self.assertTrue(dominates(better, worse))
        self.assertFalse(dominates(better, tradeoff))
        self.assertFalse(dominates(worse, better))

    def test_translation_pareto_keeps_quality_latency_tradeoffs(self):
        quality = {"chrf_plus_plus": 58., "bleu": 35., "p50_ms": 5000.}
        fast = {"chrf_plus_plus": 51., "bleu": 24., "p50_ms": 175.}
        weaker = {"chrf_plus_plus": 50., "bleu": 23., "p50_ms": 200.}
        self.assertFalse(dominates_translation(quality, fast))
        self.assertFalse(dominates_translation(fast, quality))
        self.assertTrue(dominates_translation(fast, weaker))

    def test_word_error_breakdown(self):
        reference = normalized_words("De temperatuur is goed")
        hypothesis = normalized_words("De temperatuur was goed extra")
        self.assertEqual(levenshtein(reference, hypothesis), (2, 1, 0, 1))

    def test_stable_prefix_never_commits_unstable_tail(self):
        prefix = StablePrefix()
        self.assertEqual(prefix.accept("De temperatuur stijg"), "")
        self.assertEqual(prefix.accept("De temperatuur stijgt"), "De temperatuur")
        self.assertEqual(prefix.accept("De temperatuur stijgt"), "De temperatuur stijgt")
        self.assertEqual(prefix.accept("De temperatuur daalt"), "De temperatuur")

    def test_translation_candidate_coalescing_thresholds(self):
        self.assertFalse(should_translate("", "", 1000, 0))
        self.assertTrue(should_translate("", "De temperatuur", 1000, 0))
        self.assertFalse(should_translate("De temperatuur", "De temperatuur", 2000, 0))
        self.assertFalse(should_translate("De temperatuur", "De temperatuur daalt", 1200, 1000))
        self.assertTrue(should_translate("De temperatuur", "De temperatuur daalt", 1800, 1000))

    def test_youtube_auto_caption_deduplicates_rolling_cues(self):
        source = """WEBVTT

00:00:00.000 --> 00:00:01.000
Goedendag. Leuk naar

00:00:01.000 --> 00:00:01.100
Goedendag. Leuk naar

00:00:01.100 --> 00:00:02.000
naar de <c>video.</c>
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "auto.vtt"
            path.write_text(source)
            self.assertEqual(transcript_from_vtt(path), "Goedendag. Leuk naar de video.")

    def test_asr_endpoint_is_not_a_partial_and_post_endpoint_tail_is_flushed(self):
        class Stream:
            def __init__(self):
                self.frames = 0
                self.finished = False

            def accept_waveform(self, rate, samples):
                self.frames += 1

            def input_finished(self):
                self.finished = True

            def set_option(self, key, value):
                pass

        class Recognizer:
            def is_ready(self, stream):
                return False

            def decode_stream(self, stream):
                pass

            def get_result(self, stream):
                if stream.finished:
                    return "laatste"
                if stream.frames == 1:
                    return "eerste"
                if stream.frames >= 2:
                    return "laatste voorlopig"
                return ""

            def is_endpoint(self, stream):
                return stream.frames == 1

            def reset(self, stream):
                pass

        stream = Stream()
        sample = {"id": "fake", "row_id": "fake", "benchmark_split": "tuning",
                  "reference_nl": "eerste laatste", "duration_seconds": 0.3}
        result, _ = run_one(Recognizer(), stream, sample,
                            np.ones(CHUNK_SAMPLES * 3, dtype=np.float32) * 0.1,
                            "maxspeed", "nl-NL", 0, 0)
        self.assertEqual(result["hypothesis"], "eerste laatste")
        self.assertEqual(result["partial_count"], 1)
        self.assertEqual(result["final_correction_word_distance"], 1)


if __name__ == "__main__":
    unittest.main()
