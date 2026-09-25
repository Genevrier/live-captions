import hashlib
import json
import struct
import tempfile
import unittest
import wave
from pathlib import Path

from validate_device_corpus import validate_manifest


class DeviceCorpusValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.samples = [1200, -2400, 5000, 32767]
        self.pcm = struct.pack("<4h", *self.samples)
        self.audio = self.root / "speaker-01.wav"
        with wave.open(str(self.audio), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(16000)
            output.writeframes(self.pcm)
        self.item = {
            "id": "nl-01",
            "benchmark_split": "tuning",
            "input_mode": "microphone",
            "audio_path": self.audio.name,
            "sha256": hashlib.sha256(self.audio.read_bytes()).hexdigest(),
            "reference_kind": "human_verbatim",
            "reference_nl": "Ik heb het niet om half vier gezegd.",
            "capture": {
                "sample_count_captured": len(self.samples),
                "sample_count_saved": len(self.samples),
                "dropped_samples": 0,
                "first_monotonic_ns": 1_000_000_000,
                "last_monotonic_ns": 1_000_250_000,
                "chunks": [
                    {"first_sample": 0, "sample_count": 2, "timestamp_monotonic_ns": 1_000_000_000},
                    {"first_sample": 2, "sample_count": 2, "timestamp_monotonic_ns": 1_000_125_000},
                ],
            },
            "correction_segments": [{
                "source_start_sample": 1,
                "source_end_sample": 3,
                "pcm_sha256": hashlib.sha256(self.pcm[2:6]).hexdigest(),
            }],
        }

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self, items):
        path = self.root / "manifest.json"
        path.write_text(json.dumps({"schema": "live-captions-device-corpus-v1", "samples": items}))
        return path

    def test_checks_audio_format_capture_continuity_human_reference_and_retained_segment_bytes(self):
        report = validate_manifest(self.write_manifest([self.item]))
        sample = report["samples"][0]
        self.assertEqual(16000, sample["sample_rate_hz"])
        self.assertEqual(4, sample["sample_count"])
        self.assertEqual(1, sample["clipped_samples"])
        self.assertEqual(1, sample["correction_segments"])
        self.assertGreater(sample["rms"], 0)

    def test_rejects_noncontiguous_audio_offsets(self):
        item = json.loads(json.dumps(self.item))
        item["capture"]["chunks"][1]["first_sample"] = 3
        with self.assertRaisesRegex(ValueError, "not contiguous"):
            validate_manifest(self.write_manifest([item]))

    def test_rejects_synthesized_or_missing_reference(self):
        item = json.loads(json.dumps(self.item))
        item["reference_kind"] = "asr_hypothesis"
        with self.assertRaisesRegex(ValueError, "human verbatim"):
            validate_manifest(self.write_manifest([item]))

    def test_rejects_corrected_pcm_that_is_not_the_exact_retained_sample_range(self):
        item = json.loads(json.dumps(self.item))
        item["correction_segments"][0]["pcm_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "differs from retained source"):
            validate_manifest(self.write_manifest([item]))


if __name__ == "__main__":
    unittest.main()
