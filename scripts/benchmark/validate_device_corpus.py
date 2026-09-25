#!/usr/bin/env python3
"""Validate human-referenced device WAVs and report their audio/capture metadata.

The validator does not create references or infer them from ASR output. Manifest
references must be transcribed by a human before a sample can enter a benchmark.
"""

from __future__ import annotations

import argparse
import array
import hashlib
import json
import math
import struct
import sys
import wave
from pathlib import Path
from typing import Any


SPLITS = {"tuning", "validation", "final_holdout"}
SAMPLE_RATE = 16_000


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_capture(item: dict[str, Any], frame_count: int, rate: int) -> None:
    mode = item.get("input_mode")
    capture = item.get("capture")
    if mode not in {"microphone", "wav_injection"}:
        raise ValueError(f"{item.get('id')}: input_mode must be microphone or wav_injection")
    if mode == "microphone" and not isinstance(capture, dict):
        raise ValueError(f"{item.get('id')}: microphone samples require a capture trace")
    if capture is None:
        return
    if capture.get("sample_count_captured") != frame_count or capture.get("sample_count_saved") != frame_count:
        raise ValueError(f"{item.get('id')}: captured/saved sample count does not match the WAV")
    if int(capture.get("dropped_samples", -1)) != 0:
        raise ValueError(f"{item.get('id')}: capture trace reports dropped samples")
    first_ns = int(capture["first_monotonic_ns"])
    last_ns = int(capture["last_monotonic_ns"])
    if first_ns <= 0 or last_ns < first_ns:
        raise ValueError(f"{item.get('id')}: invalid monotonic capture interval")
    chunks = capture.get("chunks")
    if not isinstance(chunks, list) or not chunks:
        raise ValueError(f"{item.get('id')}: capture trace needs chunk offsets and timestamps")
    next_sample = 0
    prior_time = None
    prior_count = None
    for chunk in chunks:
        offset = int(chunk["first_sample"])
        count = int(chunk["sample_count"])
        timestamp = int(chunk["timestamp_monotonic_ns"])
        if offset != next_sample or count <= 0:
            raise ValueError(f"{item.get('id')}: sample chunks are not contiguous")
        if prior_time is not None:
            expected = prior_time + round(prior_count * 1_000_000_000 / rate)
            if timestamp < prior_time or abs(timestamp - expected) > 250_000_000:
                raise ValueError(f"{item.get('id')}: chunk timestamps are discontinuous or non-monotonic")
        next_sample += count
        prior_time, prior_count = timestamp, count
    if next_sample != frame_count:
        raise ValueError(f"{item.get('id')}: chunk map covers {next_sample} of {frame_count} samples")


def _validate_correction_slices(item: dict[str, Any], pcm: bytes, frame_count: int) -> None:
    previous_end = 0
    for segment in item.get("correction_segments", []):
        start = int(segment["source_start_sample"])
        end = int(segment["source_end_sample"])
        if start < previous_end or start < 0 or end <= start or end > frame_count:
            raise ValueError(f"{item.get('id')}: invalid or overlapping corrected-source sample range")
        source_slice = pcm[start * 2:end * 2]
        actual = hashlib.sha256(source_slice).hexdigest()
        if actual != segment.get("pcm_sha256"):
            raise ValueError(f"{item.get('id')}: corrected-segment PCM differs from retained source samples")
        previous_end = end


def inspect_sample(root: Path, item: dict[str, Any]) -> dict[str, Any]:
    sample_id = item.get("id")
    if not sample_id:
        raise ValueError("sample id is required")
    split = item.get("benchmark_split")
    if split not in SPLITS:
        raise ValueError(f"{sample_id}: benchmark_split must be one of {sorted(SPLITS)}")
    if item.get("reference_kind") != "human_verbatim" or not str(item.get("reference_nl", "")).strip():
        raise ValueError(f"{sample_id}: add a human verbatim Dutch reference; ASR hypotheses are not references")

    relative = Path(item["audio_path"])
    path = (root / relative).resolve()
    if root.resolve() not in path.parents:
        raise ValueError(f"{sample_id}: audio_path escapes the corpus directory")
    if not path.is_file():
        raise ValueError(f"{sample_id}: missing audio file {relative}")
    expected_hash = item.get("sha256")
    actual_hash = sha256_file(path)
    if expected_hash != actual_hash:
        raise ValueError(f"{sample_id}: audio SHA-256 mismatch")

    with wave.open(str(path), "rb") as audio:
        if audio.getcomptype() != "NONE" or audio.getnchannels() != 1 or audio.getsampwidth() != 2:
            raise ValueError(f"{sample_id}: expected uncompressed mono PCM16 WAV")
        rate = audio.getframerate()
        if rate != SAMPLE_RATE:
            raise ValueError(f"{sample_id}: expected {SAMPLE_RATE} Hz, found {rate} Hz")
        frame_count = audio.getnframes()
        pcm = audio.readframes(frame_count)
    if not frame_count:
        raise ValueError(f"{sample_id}: empty WAV")
    _validate_capture(item, frame_count, rate)
    _validate_correction_slices(item, pcm, frame_count)

    values = array.array("h")
    values.frombytes(pcm)
    if sys.byteorder != "little":
        values.byteswap()
    peak = max(abs(value) for value in values)
    clipped = sum(value in (-32768, 32767) for value in values)
    rms = math.sqrt(sum(value * value for value in values) / len(values)) / 32768.0
    return {
        "id": sample_id,
        "split": split,
        "input_mode": item["input_mode"],
        "sha256": actual_hash,
        "sample_rate_hz": rate,
        "channels": 1,
        "sample_count": frame_count,
        "duration_ms": round(frame_count * 1000 / rate, 3),
        "rms": round(rms, 6),
        "peak_normalized": round(peak / 32768.0, 6),
        "clipped_samples": clipped,
        "clipped_percent": round(clipped * 100 / frame_count, 6),
        "human_reference_words": len(str(item["reference_nl"]).split()),
        "correction_segments": len(item.get("correction_segments", [])),
    }


def validate_manifest(manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "live-captions-device-corpus-v1":
        raise ValueError("unsupported corpus schema")
    samples = manifest.get("samples")
    if not isinstance(samples, list) or not samples:
        raise ValueError("manifest must contain at least one sample")
    ids: set[str] = set()
    hashes_by_split: dict[str, set[str]] = {}
    inspected = []
    for item in samples:
        report = inspect_sample(manifest_path.parent, item)
        if report["id"] in ids:
            raise ValueError(f"duplicate sample id: {report['id']}")
        ids.add(report["id"])
        hashes_by_split.setdefault(report["split"], set()).add(report["sha256"])
        inspected.append(report)
    split_names = list(hashes_by_split)
    for index, split in enumerate(split_names):
        for other in split_names[index + 1:]:
            if hashes_by_split[split] & hashes_by_split[other]:
                raise ValueError(f"audio files are reused across {split} and {other}")
    return {"schema": manifest["schema"], "sample_count": len(inspected), "samples": inspected}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Human-referenced device-corpus JSON manifest")
    parser.add_argument("--report", type=Path, help="Write validated audio diagnostics as JSON")
    args = parser.parse_args()
    report = validate_manifest(args.manifest)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
