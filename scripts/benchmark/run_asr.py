#!/usr/bin/env python3
"""Run pinned Nemotron CPU graphs on the frozen FLEURS tuning samples.

This is a reproducible host CPU component benchmark using sherpa-onnx 1.13.8.
It uses the app's 16 kHz / 100 ms feed size, nl-NL stream prompt, model assets,
endpoint values and stable-prefix/admission rules. It does not simulate Android
AudioRecord, CaptionSession queues, QNN, OpenCL, Compose rendering or thermals.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import re
import resource
import statistics
import subprocess
import sys
import tarfile
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx


MODEL_PINS = {
    80: (475_274_007, "fb170128c496db33a1fb9f5f9f823257f42f911224ee218bb429f3c2eaf90a8d"),
    160: (475_273_363, "a81909a1780d84cff16d73c15e13e67d9d81d8839faf14870d507d8499f7a61a"),
    320: (475_272_949, "5f311142337a5c161e92d49f7a3009d8607d3836f39d610bff5307c74d1d2c53"),
    560: (475_271_763, "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a"),
    1120: (475_276_334, "adbdd5e9fef87300c37cebfcfc4f1ebe56845c860c8a760af0a1dd65ce9beed3"),
}
MODEL_NAME = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-{chunk}ms-int8-2026-06-11"
SAMPLE_RATE = 16_000
CHUNK_SAMPLES = 1_600
ENDPOINT_PADDING_SECONDS = 2.6


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def ensure_model(root: Path, chunk_ms: int) -> Path:
    size, digest = MODEL_PINS[chunk_ms]
    name = MODEL_NAME.format(chunk=chunk_ms)
    existing_dir = root.parent / "chunk-smoke" / name
    existing_archive = root.parent / "chunk-smoke" / f"{name}.tar.bz2"
    needed = ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    if (all((existing_dir / filename).is_file() for filename in needed) and existing_archive.is_file()
            and existing_archive.stat().st_size == size and file_sha256(existing_archive) == digest):
        return existing_dir
    archive = existing_archive if (existing_archive.is_file() and existing_archive.stat().st_size == size
                                   and file_sha256(existing_archive) == digest) else root / "models" / f"{name}.tar.bz2"
    model_dir = root / "models" / name
    archive.parent.mkdir(parents=True, exist_ok=True)
    valid_archive = archive.is_file() and archive.stat().st_size == size and file_sha256(archive) == digest
    if not valid_archive:
        temporary = archive.with_suffix(archive.suffix + ".part")
        url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{archive.name}"
        subprocess.run([sys.executable, "-c", "import urllib.request,sys; urllib.request.urlretrieve(sys.argv[1],sys.argv[2])", url, str(temporary)], check=True)
        if temporary.stat().st_size != size or file_sha256(temporary) != digest:
            raise RuntimeError(f"Nemotron {chunk_ms} ms archive checksum mismatch")
        temporary.replace(archive)
    if not all((model_dir / name).is_file() for name in needed):
        model_dir.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive, "r:bz2") as bundle:
            bundle.extractall(root / "models", filter="data")
    for filename in needed:
        if not (model_dir / filename).is_file():
            raise RuntimeError(f"Incomplete Nemotron model: {model_dir / filename}")
    return model_dir


def normalized_words(text: str) -> list[str]:
    import re
    import unicodedata
    text = unicodedata.normalize("NFKC", text).casefold()
    return re.findall(r"[^\W_]+(?:['’][^\W_]+)*", text, flags=re.UNICODE)


def literal_number_tokens(text: str) -> list[str]:
    """Exact digit strings only; spelled-out number semantics need human annotation."""
    return re.findall(r"(?<!\w)[+-]?\d+(?:[.,]\d+)*(?:%|(?=\W|$))", text)


def levenshtein(left: list[str], right: list[str]) -> tuple[int, int, int, int]:
    rows, cols = len(left), len(right)
    cost = [[0] * (cols + 1) for _ in range(rows + 1)]
    op = [[""] * (cols + 1) for _ in range(rows + 1)]
    for i in range(1, rows + 1): cost[i][0], op[i][0] = i, "D"
    for j in range(1, cols + 1): cost[0][j], op[0][j] = j, "I"
    for i in range(1, rows + 1):
        for j in range(1, cols + 1):
            if left[i - 1] == right[j - 1]:
                cost[i][j], op[i][j] = cost[i - 1][j - 1], "M"
            else:
                candidates = [(cost[i - 1][j - 1] + 1, "S"), (cost[i - 1][j] + 1, "D"), (cost[i][j - 1] + 1, "I")]
                cost[i][j], op[i][j] = min(candidates, key=lambda item: (item[0], item[1]))
    substitutions = deletions = insertions = 0
    i, j = rows, cols
    while i or j:
        action = op[i][j]
        if action == "S": substitutions += 1; i -= 1; j -= 1
        elif action == "D": deletions += 1; i -= 1
        elif action == "I": insertions += 1; j -= 1
        else: i -= 1; j -= 1
    return cost[rows][cols], substitutions, deletions, insertions


class StablePrefix:
    """Direct port of app/src/main/java/.../pipeline/Segments.kt."""
    def __init__(self) -> None:
        self.previous = ""
        self.committed = ""

    def accept(self, hypothesis: str) -> str:
        text = hypothesis.strip()
        common = 0
        for left, right in zip(self.previous, text):
            if left != right: break
            common += 1
        end = common
        if common < len(text) and not text[common].isspace():
            while end > 0 and not self.previous[end - 1].isspace() and self.previous[end - 1] not in "。！？.!?,，":
                end -= 1
        candidate = text[:end].rstrip()
        self.committed = self.committed if text.startswith(self.committed) and len(candidate) < len(self.committed) else candidate
        self.previous = text
        return self.committed


def should_translate(previous: str, current: str, now_ms: float, last_ms: float) -> bool:
    candidate = current.strip()
    if len(candidate) < 4 or candidate == previous or now_ms - last_ms < 650:
        return False
    if not previous:
        return True
    shared = 0
    for left, right in zip(previous, candidate):
        if left != right: break
        shared += 1
    changed = candidate[shared:].strip()
    sentence_end = candidate[-1:] in ".!?。！？" and previous[-1:] not in ".!?。！？"
    return len(changed) >= 4 or sentence_end


def energy_bounds(samples: np.ndarray) -> tuple[int | None, int | None]:
    block = SAMPLE_RATE // 100
    active = []
    for offset in range(0, len(samples), block):
        frame = samples[offset:offset + block]
        if frame.size and float(np.sqrt(np.mean(frame * frame))) >= 0.01:
            active.append(offset)
    return (active[0], active[-1] + block) if active else (None, None)


def latency_summary(values: list[float | None]) -> dict:
    data = [float(value) for value in values if value is not None and math.isfinite(float(value))]
    if not data:
        return {key: None for key in ("n", "mean", "p50", "p90", "p95", "p99", "max", "stddev")}
    return {"n": len(data), "mean": statistics.fmean(data),
            "p50": float(np.percentile(data, 50)), "p90": float(np.percentile(data, 90)),
            "p95": float(np.percentile(data, 95)), "p99": float(np.percentile(data, 99)),
            "max": max(data), "stddev": statistics.pstdev(data)}


def thermal_snapshot() -> dict[str, str]:
    result = {}
    for path in Path("/sys/class/thermal").glob("thermal_zone*/temp"):
        if not path.exists():
            continue
        zone = path.parent
        kind_path = zone / "type"
        kind = kind_path.read_text().strip() if kind_path.exists() else "unknown"
        result[f"{zone.name}:{kind}"] = path.read_text().strip()
    return result


def cpu_model() -> str | None:
    try:
        output = subprocess.run(["lscpu"], capture_output=True, text=True, check=False).stdout
        names = list(dict.fromkeys(re.findall(r"^Model name:\s*(.+)$", output, flags=re.MULTILINE)))
        if names:
            return " / ".join(names)
    except OSError:
        pass
    path = Path("/proc/cpuinfo")
    if not path.exists():
        return None
    for line in path.read_text(errors="replace").splitlines():
        if line.lower().startswith(("model name", "hardware")) and ":" in line:
            return line.split(":", 1)[1].strip()
    return None


def read_wave(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as source:
        if source.getframerate() != SAMPLE_RATE or source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise RuntimeError(f"Expected mono 16 kHz s16le WAV, got {path}")
        return np.frombuffer(source.readframes(source.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


def make_recognizer(model_dir: Path, threads: int, rule1: float, rule2: float):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model_dir / "tokens.txt"), encoder=str(model_dir / "encoder.int8.onnx"),
        decoder=str(model_dir / "decoder.int8.onnx"), joiner=str(model_dir / "joiner.int8.onnx"),
        num_threads=threads, provider="cpu", model_type="", enable_endpoint_detection=True,
        rule1_min_trailing_silence=rule1, rule2_min_trailing_silence=rule2,
        rule3_min_utterance_length=20.0,
    )


def run_one(recognizer, stream, sample: dict, source_samples: np.ndarray, mode: str, language: str,
            session_started_ns: int, audio_offset_samples: int) -> tuple[dict, int]:
    rate = SAMPLE_RATE
    trailing = np.zeros(int(ENDPOINT_PADDING_SECONDS * rate), dtype=np.float32)
    audio = np.concatenate((source_samples, trailing))
    onset, speech_end = energy_bounds(source_samples)
    onset = 0 if onset is None else onset
    speech_end = len(source_samples) if speech_end is None else min(speech_end, len(source_samples))
    audio_first_ns = (session_started_ns + int(audio_offset_samples * 1e9 / rate)
                      if mode == "realtime" else time.perf_counter_ns())
    speech_onset_ns = audio_first_ns + int(onset * 1e9 / rate)
    speech_end_ns = audio_first_ns + int(speech_end * 1e9 / rate)
    latest_raw = ""
    current_segment = ""
    prefix = StablePrefix()
    first_feed_ns = None
    first_partial_ns = None
    first_stable_ns = None
    chunk_times: list[float] = []
    raw_revisions = 0
    changed_visible_words = 0
    stable_prefix_revisions = 0
    final_correction_word_distance = 0
    partial_count = 0
    provisional_requests = 0
    last_request_ms = -1e12
    last_request_text = ""
    previous_stable = ""
    max_stable_length = 0
    endpoint_ns = None
    asr_final_ns = None
    finalized = []
    compute_ns = 0
    end_feed_ns = None
    ended_on_endpoint = False
    for offset in range(0, len(audio), CHUNK_SAMPLES):
        frame = audio[offset:offset + CHUNK_SAMPLES]
        if mode == "realtime":
            due = audio_first_ns + int((offset + len(frame)) * 1e9 / rate)
            while True:
                wait_ns = due - time.perf_counter_ns()
                if wait_ns <= 0: break
                time.sleep(min(wait_ns / 1e9, 0.025))
        before = time.perf_counter_ns()
        if first_feed_ns is None:
            first_feed_ns = before
        stream.accept_waveform(rate, frame)
        while recognizer.is_ready(stream):
            decode_start = time.perf_counter_ns()
            recognizer.decode_stream(stream)
            chunk_times.append((time.perf_counter_ns() - decode_start) / 1e6)
        after = time.perf_counter_ns()
        end_feed_ns = after
        compute_ns += after - before
        hypothesis = recognizer.get_result(stream)
        if recognizer.is_endpoint(stream):
            if latest_raw:
                final_correction_word_distance += levenshtein(
                    normalized_words(latest_raw), normalized_words(hypothesis.strip()))[0]
            finalized.append(hypothesis.strip())
            endpoint_ns = after
            # Continue if this endpoint is inside the recorded phrase; a full
            # phrase endpoint consumes the appended silence and starts no next phrase.
            if offset + len(frame) >= len(source_samples):
                ended_on_endpoint = True
                asr_final_ns = after
                break
            recognizer.reset(stream)
            stream.set_option("language", language)
            latest_raw = ""
            current_segment = ""
            prefix = StablePrefix()
            previous_stable = ""
            endpoint_ns = None
            continue
        if hypothesis and hypothesis != latest_raw:
            partial_count += 1
            if first_partial_ns is None:
                first_partial_ns = after
            if latest_raw:
                old, new = normalized_words(latest_raw), normalized_words(hypothesis)
                common = 0
                while common < min(len(old), len(new)) and old[common] == new[common]: common += 1
                if old != new:
                    raw_revisions += 1
                    changed_visible_words += len(old) - common
            latest_raw = hypothesis
            current_segment = hypothesis
            relative_ms = (after - audio_first_ns) / 1e6
            stable = prefix.accept(hypothesis)
            if stable:
                max_stable_length = max(max_stable_length, len(stable))
                if first_stable_ns is None:
                    first_stable_ns = after
                if previous_stable and not stable.startswith(previous_stable):
                    stable_prefix_revisions += 1
                previous_stable = stable
                if should_translate(last_request_text, stable, relative_ms, last_request_ms):
                    provisional_requests += 1
                    last_request_ms, last_request_text = relative_ms, stable
    if not ended_on_endpoint:
        # Flush the trailing live phrase as the application does when Stop is
        # pressed. This also preserves speech after an earlier endpoint in a
        # multi-phrase recording instead of silently dropping the last phrase.
        final_start_ns = time.perf_counter_ns()
        stream.input_finished()
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        asr_final_ns = time.perf_counter_ns()
        compute_ns += asr_final_ns - final_start_ns
        tail = recognizer.get_result(stream).strip()
        if tail and (not finalized or tail != finalized[-1]):
            final_correction_word_distance += levenshtein(
                normalized_words(latest_raw), normalized_words(tail))[0]
            finalized.append(tail)
    final_text = " ".join(part for part in finalized if part).strip()
    reference = sample["reference_nl"]
    errors, substitutions, deletions, insertions = levenshtein(normalized_words(reference), normalized_words(final_text))
    reference_numbers = literal_number_tokens(reference)
    hypothesis_numbers = literal_number_tokens(final_text)
    number_errors, number_subs, number_dels, number_ins = levenshtein(reference_numbers, hypothesis_numbers)
    ref_chars = list("".join(normalized_words(reference)))
    hyp_chars = list("".join(normalized_words(final_text)))
    char_errors = levenshtein(ref_chars, hyp_chars)[0]
    # Max-speed mode has no wall-clock relationship to the original recording.
    # Keep processing delay as its own metric and only report perceived latency
    # in real-time mode, where samples are scheduled at their source timestamps.
    endpoint_delay = ((endpoint_ns - speech_end_ns) / 1e6
                      if mode == "realtime" and endpoint_ns is not None else None)
    speech_end_to_asr_final = ((asr_final_ns - speech_end_ns) / 1e6
                               if mode == "realtime" and asr_final_ns is not None else None)
    first_partial_processing_ms = ((first_partial_ns - first_feed_ns) / 1e6
                                   if first_feed_ns is not None and first_partial_ns is not None else None)
    stable_source_processing_ms = ((first_stable_ns - first_feed_ns) / 1e6
                                  if first_feed_ns is not None and first_stable_ns is not None else None)
    first_partial_latency = ((first_partial_ns - speech_onset_ns) / 1e6
                             if mode == "realtime" and first_partial_ns is not None else None)
    stable_source_latency = ((first_stable_ns - speech_onset_ns) / 1e6
                             if mode == "realtime" and first_stable_ns is not None else None)
    processing_ms = compute_ns / 1e6
    audio_seconds = len(source_samples) / rate
    real_time_factor = processing_ms / max(1.0, audio_seconds * 1000)
    result = {
        "sample_id": sample["id"], "row_id": sample["row_id"], "benchmark_split": sample["benchmark_split"],
        "audio_seconds": audio_seconds, "reference_words": len(normalized_words(reference)),
        "hypothesis": final_text, "reference": reference,
        "wer": errors / max(1, len(normalized_words(reference))),
        "reference_literal_numbers": reference_numbers,
        "hypothesis_literal_numbers": hypothesis_numbers,
        "literal_number_errors": number_errors,
        "literal_number_substitutions": number_subs,
        "literal_number_deletions": number_dels,
        "literal_number_insertions": number_ins,
        "cer": char_errors / max(1, len(ref_chars)), "word_errors": errors,
        "substitutions": substitutions, "deletions": deletions, "insertions": insertions,
        "partial_count": partial_count, "raw_partial_revisions": raw_revisions,
        "changed_previously_visible_words": changed_visible_words,
        "stable_prefix_revisions": stable_prefix_revisions,
        "final_correction_word_distance": final_correction_word_distance,
        "stable_prefix_length": max_stable_length, "provisional_translation_candidates": provisional_requests,
        "final_translation_candidates": len(finalized),
        "asr_processing_ms": processing_ms, "asr_rtf": real_time_factor,
        "endpoint_delay_ms": endpoint_delay, "chunk_decode_p50_ms": float(np.percentile(chunk_times, 50)) if chunk_times else None,
        "speech_end_to_asr_final_ms": speech_end_to_asr_final,
        "chunk_decode_p95_ms": float(np.percentile(chunk_times, 95)) if chunk_times else None,
        "first_partial_latency_ms": first_partial_latency,
        "stable_source_latency_ms": stable_source_latency,
        "first_partial_processing_ms": first_partial_processing_ms,
        "stable_source_processing_ms": stable_source_processing_ms,
        "sample_speech_onset_energy_estimate_ms": onset / 16,
        "mode": mode,
    }
    # Drop session-local state; the caller resets the resident recognizer stream.
    return result, len(audio)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("build/benchmark-data"))
    parser.add_argument("--manifest", type=Path, default=Path("build/benchmark-data/manifest.json"))
    parser.add_argument("--out", type=Path, default=Path("benchmark_results/baseline"))
    parser.add_argument("--split", choices=("tuning", "validation", "final_holdout", "robustness"), default="tuning")
    parser.add_argument("--allow-validation", action="store_true")
    parser.add_argument("--allow-final-holdout", action="store_true")
    parser.add_argument("--chunk-ms", type=int, choices=tuple(MODEL_PINS), default=560)
    parser.add_argument("--threads", type=int, choices=(2, 4, 6, 8), default=6)
    parser.add_argument("--rule1-seconds", type=float, default=2.4)
    parser.add_argument("--rule2-seconds", type=float, default=1.4)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--mode", choices=("maxspeed", "realtime"), default="maxspeed")
    parser.add_argument("--language", choices=("nl-NL", "auto"), default="nl-NL")
    parser.add_argument("--limit", type=int, default=0, help="0 means all frozen samples in the chosen split")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.split == "validation" and not args.allow_validation:
        raise SystemExit("Validation is reserved until a tuning-set candidate is selected; pass --allow-validation")
    if args.split == "final_holdout" and not args.allow_final_holdout:
        raise SystemExit("The final holdout may only be run once after tuning is complete; pass --allow-final-holdout")
    if args.split == "final_holdout" and args.runs != 1:
        raise SystemExit("Final holdout is run exactly once; use --runs 1")
    if args.split == "final_holdout":
        marker = args.root / "final_holdout_attempt.json"
        if marker.exists() or (args.out / "metrics.json").exists():
            raise SystemExit(f"The single final hold-out attempt has already been consumed: {marker}")
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text(json.dumps({"attempted_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                                      "out": str(args.out), "chunk_ms": args.chunk_ms,
                                      "threads": args.threads, "rule1_seconds": args.rule1_seconds,
                                      "rule2_seconds": args.rule2_seconds}, indent=2) + "\n")
    manifest = json.loads(args.manifest.read_text())
    samples = [sample for sample in manifest["samples"] if sample["benchmark_split"] == args.split]
    if args.limit:
        samples = samples[:args.limit]
    if not samples:
        raise SystemExit(f"No samples for split {args.split}")
    model_dir = ensure_model(args.root, args.chunk_ms)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "profiler").mkdir(exist_ok=True)
    head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=False).stdout.strip()
    source_diff = subprocess.run(["git", "diff", "--binary", "HEAD"], capture_output=True, check=False).stdout
    dataset_revision = manifest.get("revision", manifest.get("dataset", "unversioned dataset"))
    config = {
        "commit": head, "model": "Nemotron 3.5 Streaming ASR 0.6B INT8",
        "asr_harness_sha256": file_sha256(Path(__file__).resolve()),
        "tracked_worktree_diff_sha256": hashlib.sha256(source_diff).hexdigest(),
        "chunk_ms": args.chunk_ms, "model_archive_sha256": MODEL_PINS[args.chunk_ms][1],
        "backend": "CPU (sherpa-onnx Python 1.13.8 / ONNX Runtime CPU provider)",
        "qnn_active": False, "opencl_active": False, "language": args.language,
        "threads": args.threads, "sample_rate_hz": SAMPLE_RATE, "input_frame_ms": 100,
        "endpointing": {"enabled": True, "rule1_min_trailing_silence_s": args.rule1_seconds,
                        "rule2_min_trailing_silence_s": args.rule2_seconds, "rule3_min_utterance_s": 20.0},
        "mode": args.mode, "runs": args.runs, "split": args.split,
        "dataset_revision": dataset_revision, "dataset_manifest_sha256": file_sha256(args.manifest),
        "pipeline_scope": "sherpa model component only; not Android CaptionSession",
        "source_language": ("Dutch (YouTube automatic caption; PSEUDO-GROUND-TRUTH)" if args.split == "robustness"
                             else "Dutch (FLEURS nl_nl)"),
        "reference_kind": ("PSEUDO-GROUND-TRUTH; YouTube automatic Dutch captions"
                           if args.split == "robustness"
                           else "FLEURS Dutch transcript; parallel English text is reserved for translation evaluation"),
        "queue_depth": "not instrumented by this host runner",
        "warmup": "5 x 100 ms silence fed before timing; model/recognizer loaded once per run",
    }
    (args.out / "config.json").write_text(json.dumps(config, indent=2) + "\n")
    os_info = {"platform": platform.platform(), "machine": platform.machine(), "cpu_model": cpu_model(),
               "python": sys.version, "sherpa_onnx": sherpa_onnx.__version__,
               "available_memory": Path("/proc/meminfo").read_text().splitlines()[0] if Path("/proc/meminfo").exists() else None,
               "load_average_before": os.getloadavg() if hasattr(os, "getloadavg") else None,
               "thermal_before": thermal_snapshot()}
    if source_diff:
        (args.out / "profiler" / "worktree.patch").write_bytes(source_diff)
    (args.out / "profiler" / "environment.json").write_text(json.dumps(os_info, indent=2) + "\n")

    all_rows = []
    run_rows = []
    output_lines = []
    for run_id in range(1, args.runs + 1):
        load_start = time.perf_counter_ns()
        recognizer = make_recognizer(model_dir, args.threads, args.rule1_seconds, args.rule2_seconds)
        load_ms = (time.perf_counter_ns() - load_start) / 1e6
        stream = recognizer.create_stream()
        stream.set_option("language", args.language)
        warm_start = time.perf_counter_ns()
        for _ in range(5):
            stream.accept_waveform(SAMPLE_RATE, np.zeros(CHUNK_SAMPLES, dtype=np.float32))
            while recognizer.is_ready(stream): recognizer.decode_stream(stream)
        recognizer.reset(stream)
        stream.set_option("language", args.language)
        warm_ms = (time.perf_counter_ns() - warm_start) / 1e6
        run_start = time.perf_counter_ns()
        run_results = []
        audio_offset_samples = 0
        for index, sample in enumerate(samples, 1):
            pcm = read_wave(args.root / sample["audio_path"])
            result, consumed = run_one(recognizer, stream, sample, pcm, args.mode, args.language,
                                       run_start, audio_offset_samples)
            result["run"] = run_id
            result["load_ms"] = load_ms
            result["end_to_end_caption_latency_ms"] = None  # Translation worker/UI are outside this host component harness.
            run_results.append(result)
            all_rows.append(result)
            output_lines.append(f"{result['sample_id']}\t{result['hypothesis']}\n")
            audio_offset_samples += consumed
            recognizer.reset(stream)
            stream.set_option("language", args.language)
            if index % 5 == 0:
                print(f"run {run_id}/{args.runs}: {index}/{len(samples)} utterances", flush=True)
        run_ms = (time.perf_counter_ns() - run_start) / 1e6
        run_rows.append({"run": run_id, "load_ms": load_ms, "warm_ms": warm_ms,
                         "elapsed_ms": run_ms, "utterances": len(run_results),
                         "mean_wer": statistics.fmean(row["wer"] for row in run_results),
                         "mean_rtf": statistics.fmean(row["asr_rtf"] for row in run_results)})
        del stream, recognizer

    with (args.out / "utterances.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(all_rows[0].keys()))
        writer.writeheader(); writer.writerows(all_rows)
    with (args.out / "runs.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(run_rows[0].keys()))
        writer.writeheader(); writer.writerows(run_rows)
    (args.out / "asr_outputs.txt").write_text("".join(output_lines))

    total_errors = total_words = total_chars = total_char_errors = subs = dels = ins = 0
    for row in all_rows:
        total_errors += row["word_errors"]; total_words += row["reference_words"]
        total_chars += len("".join(normalized_words(row["reference"])))
        total_char_errors += round(row["cer"] * max(1, len("".join(normalized_words(row["reference"])))))
        subs += row["substitutions"]; dels += row["deletions"]; ins += row["insertions"]
    metrics = {
        "runs": run_rows, "utterances": len(all_rows), "audio_minutes": sum(r["audio_seconds"] for r in all_rows) / 60,
        "asr_wer": total_errors / max(1, total_words), "asr_cer": total_char_errors / max(1, total_chars),
        "word_error_counts": {"substitutions": subs, "deletions": dels, "insertions": ins, "reference_words": total_words},
        "literal_number_error_count": sum(r["literal_number_errors"] for r in all_rows),
        "literal_number_reference_count": sum(len(r["reference_literal_numbers"]) for r in all_rows),
        "literal_number_exact_rate": (
            sum(len(r["reference_literal_numbers"]) - r["literal_number_substitutions"] -
                r["literal_number_deletions"] for r in all_rows) /
            max(1, sum(len(r["reference_literal_numbers"]) for r in all_rows))
        ) if any(r["reference_literal_numbers"] for r in all_rows) else None,
        "named_entity_metric": None,
        "named_entity_metric_note": "No independently annotated named-entity labels are included in the frozen FLEURS sample.",
        "technical_terminology_metric": None,
        "technical_terminology_metric_note": "No independently annotated technical-term labels or in-domain audio are included.",
        "processing_ms": latency_summary([r["asr_processing_ms"] for r in all_rows]),
        "rtf": latency_summary([r["asr_rtf"] for r in all_rows]),
        "endpoint_delay_ms": latency_summary([r["endpoint_delay_ms"] for r in all_rows]),
        "speech_end_to_asr_final_ms": latency_summary([r["speech_end_to_asr_final_ms"] for r in all_rows]),
        "first_partial_latency_ms": latency_summary([r["first_partial_latency_ms"] for r in all_rows]),
        "stable_source_latency_ms": latency_summary([r["stable_source_latency_ms"] for r in all_rows]),
        "first_partial_processing_ms": latency_summary([r["first_partial_processing_ms"] for r in all_rows]),
        "stable_source_processing_ms": latency_summary([r["stable_source_processing_ms"] for r in all_rows]),
        "partial_revisions_per_utterance": statistics.fmean(r["raw_partial_revisions"] for r in all_rows),
        "rewritten_visible_words_per_utterance": statistics.fmean(r["changed_previously_visible_words"] for r in all_rows),
        "stable_prefix_revisions_per_utterance": statistics.fmean(r["stable_prefix_revisions"] for r in all_rows),
        "final_correction_word_distance_per_utterance": statistics.fmean(r["final_correction_word_distance"] for r in all_rows),
        "hy_provisional_request_candidates_per_utterance": statistics.fmean(r["provisional_translation_candidates"] for r in all_rows),
        "hy_final_request_candidates_per_utterance": statistics.fmean(r["final_translation_candidates"] for r in all_rows),
        "translation_latency": "not measured here; use the same pinned native C++ Hy benchmark",
        "queue_depth": "not measured here; host runner does not instantiate CaptionSession",
        "temperature": "host sensor values only; no Honor device connected",
    }
    os_info["process_max_rss_kb"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    os_info["load_average_after"] = os.getloadavg() if hasattr(os, "getloadavg") else None
    os_info["thermal_after"] = thermal_snapshot()
    (args.out / "profiler" / "environment.json").write_text(json.dumps(os_info, indent=2) + "\n")
    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n")
    print(json.dumps({"out": str(args.out), "wer": metrics["asr_wer"], "cer": metrics["asr_cer"],
                      "mean_rtf": metrics["rtf"]["mean"], "run_count": len(run_rows)}))


if __name__ == "__main__":
    main()
