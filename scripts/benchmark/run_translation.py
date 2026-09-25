#!/usr/bin/env python3
"""Benchmark the pinned production C++ Hy/OPUS engines on identical ASR text.

The input text comes from run 1 of the frozen tuning-set Nemotron baseline, so
translation systems see the same recognition errors. Human-produced parallel
FLEURS English lines are used as translation references. No hold-out material
is read by this runner.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
from sacrebleu.metrics import BLEU, CHRF


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def ensure_bundle(root: Path, bundle: dict) -> Path:
    directory = root / bundle["id"]
    for asset in bundle["files"]:
        path = directory / asset["path"]
        valid = path.is_file() and path.stat().st_size == asset["size"] and sha256(path) == asset["sha256"]
        if valid:
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".part")
        print(f"Downloading and verifying {bundle['id']}/{asset['path']} ({asset['size']} bytes)", flush=True)
        digest = hashlib.sha256()
        count = 0
        with urllib.request.urlopen(asset["url"], timeout=180) as response, temporary.open("wb") as output:
            while block := response.read(8 * 1024 * 1024):
                output.write(block)
                digest.update(block)
                count += len(block)
                if count % (256 * 1024 * 1024) < len(block):
                    print(f"  {count}/{asset['size']} bytes", flush=True)
        if count != asset["size"] or digest.hexdigest() != asset["sha256"]:
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"Pinned asset integrity check failed: {bundle['id']}/{asset['path']}")
        temporary.replace(path)
    return directory


def percentile_summary(values: list[float]) -> dict:
    return {"n": len(values), "mean": float(np.mean(values)), "p50": float(np.percentile(values, 50)),
            "p90": float(np.percentile(values, 90)), "p95": float(np.percentile(values, 95)),
            "p99": float(np.percentile(values, 99)), "max": float(max(values)),
            "stddev": float(np.std(values))}


def thermal_snapshot() -> dict[str, str]:
    result = {}
    for path in Path("/sys/class/thermal").glob("thermal_zone*/temp"):
        zone = path.parent
        kind = (zone / "type").read_text().strip() if (zone / "type").exists() else "unknown"
        result[f"{zone.name}:{kind}"] = path.read_text().strip()
    return result


def literal_numbers(text: str) -> list[str]:
    return re.findall(r"(?<!\w)[+-]?\d+(?:[.,]\d+)*(?:%|(?=\W|$))", text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("hy", "opus"), required=True)
    parser.add_argument("--bundle", help="Pinned translation bundle ID; defaults to Hy 7B Q4_K_M or Dutch→English OPUS")
    parser.add_argument("--native-backend", choices=("cpu", "opencl"), default="cpu",
                        help="Requested native backend for both matched CPU/OpenCL runs")
    parser.add_argument("--threads", type=int, help="Native inference threads; defaults to the production choice")
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--ubatch", type=int, default=128)
    parser.add_argument("--cache-ab", action="store_true",
                        help="For Hy, compare uncached cold and repeated same-prompt warm output/timings")
    parser.add_argument("--split", choices=("tuning", "validation", "final_holdout"), default="tuning")
    parser.add_argument("--allow-final-holdout", action="store_true")
    parser.add_argument("--manifest", type=Path, default=Path("build/benchmark-data/manifest.json"))
    parser.add_argument("--asr-csv", type=Path, default=Path("benchmark_results/baseline/asr-maxspeed/utterances.csv"))
    parser.add_argument("--model-root", type=Path, default=Path("build/benchmark-models"))
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=3)
    args = parser.parse_args()
    if args.runs < 1:
        raise SystemExit("runs must be positive")
    if args.split == "final_holdout" and (not args.allow_final_holdout or args.runs != 1):
        raise SystemExit("Final translation hold-out is part of the single final pipeline attempt; explicitly allow it with --runs 1")
    if args.split == "final_holdout" and (args.out / "metrics.json").exists():
        raise SystemExit("Final translation hold-out output already exists; do not run the hold-out twice")
    if args.split == "validation" and args.runs != 3:
        raise SystemExit("Validation translation runs must be three to match the tuning procedure")
    asr_config_path = args.asr_csv.parent / "config.json"
    if not asr_config_path.is_file():
        raise SystemExit("The selected ASR output has no frozen config.json")
    asr_config = json.loads(asr_config_path.read_text())
    if asr_config.get("split") != args.split:
        raise SystemExit(f"ASR source split {asr_config.get('split')} does not match translation split {args.split}")
    if args.split == "final_holdout":
        marker = args.manifest.parent / "final_holdout_attempt.json"
        if not marker.is_file():
            raise SystemExit("Run the single explicitly approved final ASR hold-out first")
        attempt = json.loads(marker.read_text())
        if (args.asr_csv.parent.resolve() != Path(attempt["out"]).resolve() or
                attempt.get("chunk_ms") != asr_config.get("chunk_ms") or
                attempt.get("threads") != asr_config.get("threads")):
            raise SystemExit("Final translation must use the one frozen ASR hold-out run recorded by the lock marker")

    manifest = json.loads(args.manifest.read_text())
    samples = [sample for sample in manifest["samples"] if sample["benchmark_split"] == args.split]
    references = {str(sample["row_id"]): sample for sample in samples}
    with args.asr_csv.open(newline="") as source:
        asr_rows = [row for row in csv.DictReader(source) if row["run"] == "1"]
    if len(asr_rows) != len(samples):
        raise RuntimeError(f"Expected one ASR row per {args.split} sample: {len(asr_rows)} vs {len(samples)}")
    cases = []
    for row in asr_rows:
        sample = references[row["row_id"]]
        if not row["hypothesis"].strip():
            raise RuntimeError(f"Empty Nemotron output for {row['sample_id']}")
        cases.append({"id": row["sample_id"], "source": row["hypothesis"],
                      "reference_en": sample["reference_en"], "audio_seconds": sample["duration_seconds"]})
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "cases.jsonl").write_text("".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases))

    lock = json.loads(Path("app/src/main/assets/translation-models.json").read_text())
    bundle_id = args.bundle or ("hymt2-7b-Q4_K_M" if args.mode == "hy" else "opus-nl-en")
    if args.mode == "hy" and not bundle_id.startswith("hymt2-7b-"):
        raise SystemExit("Hy mode requires a pinned Hy-MT2 7B GGUF bundle")
    if args.mode == "opus" and bundle_id != "opus-nl-en":
        raise SystemExit("This runner supports only the Dutch→English OPUS bundle")
    bundle = next(item for item in lock["bundles"] if item["id"] == bundle_id)
    model_dir = ensure_bundle(args.model_root, bundle)
    model_arg = str(model_dir / "model.gguf") if args.mode == "hy" else str(model_dir)
    threads = args.threads if args.threads is not None else (4 if args.mode == "hy" else 2)
    if threads < 1 or args.batch < 1 or args.ubatch < 1 or args.ubatch > args.batch:
        raise SystemExit("threads, batch and ubatch must be positive, with ubatch <= batch")
    config = {
        "mode": args.mode, "bundle": bundle_id, "revision": bundle["revision"],
        "license": bundle["license"], "assets": bundle["files"],
        "threads": threads, "batch": args.batch, "ubatch": args.ubatch,
        "requested_backend": args.native_backend,
        "backend_note": "The host runner has no Adreno OpenCL device; the runtime backend field reports actual selection/fallback.",
        "cache_ab": args.cache_ab,
        "source": f"run-1 hypotheses from the frozen Nemotron {args.split} ASR run",
        "reference": "parallel human-produced FLEURS English reference; translation quality is approximate",
        "dataset_revision": manifest["revision"], "split": args.split,
        "case_count": len(cases), "runs": args.runs,
        "host": {"platform": platform.platform(), "machine": platform.machine(), "python": sys.version},
        "translation_prompt": "production Dutch→English TranslationPrompt; no glossary/context",
    }
    (args.out / "config.json").write_text(json.dumps(config, indent=2) + "\n")
    environment = {"thermal_before": thermal_snapshot(),
                   "load_average_before": os.getloadavg() if hasattr(os, "getloadavg") else None}
    all_results = []
    run_summaries = []
    for run in range(1, args.runs + 1):
        output_path = args.out / f"run-{run}.jsonl"
        log_path = args.out / f"run-{run}.log"
        start = time.perf_counter()
        with log_path.open("w") as log:
            command = [str(args.binary.resolve()), args.mode, model_arg, str(threads),
                       str((args.out / "cases.jsonl").resolve()), str(output_path.resolve()),
                       args.native_backend, str(args.batch), str(args.ubatch)]
            if args.cache_ab:
                command.append("cache-ab")
            subprocess.run(command,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        wall_ms = (time.perf_counter() - start) * 1000
        results = [json.loads(line) for line in output_path.read_text().splitlines() if line]
        if len(results) != len(cases):
            raise RuntimeError(f"Run {run}: expected {len(cases)} outputs; got {len(results)}")
        for row in results:
            row["run"] = run
            row["audio_seconds"] = next(case["audio_seconds"] for case in cases if case["id"] == row["id"])
            row["reference_literal_numbers"] = literal_numbers(row["reference_en"])
            row["hypothesis_literal_numbers"] = literal_numbers(row["translation"])
            row["literal_numbers_exact_match"] = row["reference_literal_numbers"] == row["hypothesis_literal_numbers"]
        all_results.extend(results)
        times = [row["elapsed_us"] / 1000 for row in results]
        run_summaries.append({"run": run, "wall_ms_including_model_load": wall_ms,
                              "translation_ms": percentile_summary(times),
                              "translation_rtf": percentile_summary([
                                  row["elapsed_us"] / (row["audio_seconds"] * 1_000_000) for row in results])})

    with (args.out / "utterances.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(all_results[0].keys()))
        writer.writeheader()
        writer.writerows(all_results)
    hypotheses = [row["translation"] for row in all_results]
    targets = [row["reference_en"] for row in all_results]
    reference_number_rows = [row for row in all_results if row["reference_literal_numbers"]]
    metrics = {
        "runs": run_summaries, "utterances": len(all_results),
        "translation_latency_ms": percentile_summary([row["elapsed_us"] / 1000 for row in all_results]),
        "translation_rtf": percentile_summary([
            row["elapsed_us"] / (row["audio_seconds"] * 1_000_000) for row in all_results]),
        "chrf_plus_plus": CHRF(word_order=2).corpus_score(hypotheses, [targets]).score,
        "bleu": BLEU().corpus_score(hypotheses, [targets]).score,
        "quality_caveat": "FLEURS parallel English text is a useful reference, not a professional evaluation of every ASR-conditioned output; scores are approximate.",
        "hy_prefill_ms": percentile_summary([row["prefill_ms"] for row in all_results]) if args.mode == "hy" else None,
        "hy_decode_ms": percentile_summary([row["decode_ms"] for row in all_results]) if args.mode == "hy" else None,
        "hy_first_token_ms": percentile_summary([row["first_token_ms"] for row in all_results]) if args.mode == "hy" else None,
        "hy_first_visible_ms": percentile_summary([row["first_visible_ms"] for row in all_results]) if args.mode == "hy" else None,
        "hy_complete_ms": percentile_summary([row["complete_ms"] for row in all_results]) if args.mode == "hy" else None,
        "hy_input_tokens": percentile_summary([row["input_tokens"] for row in all_results]) if args.mode == "hy" else None,
        "hy_output_tokens": percentile_summary([row["output_tokens"] for row in all_results]) if args.mode == "hy" else None,
        "hy_cache_reused_tokens": percentile_summary([row["cache_reused_tokens"] for row in all_results]) if args.mode == "hy" else None,
        "hy_prefill_decode_us": percentile_summary([row["prefill_decode_us"] for row in all_results]) if args.mode == "hy" else None,
        "hy_prefill_sync_us": percentile_summary([row["prefill_sync_us"] for row in all_results]) if args.mode == "hy" else None,
        "hy_sampling_us": percentile_summary([row["sampling_us"] for row in all_results]) if args.mode == "hy" else None,
        "hy_decode_compute_us": percentile_summary([row["decode_compute_us"] for row in all_results]) if args.mode == "hy" else None,
        "hy_decode_sync_us": percentile_summary([row["decode_sync_us"] for row in all_results]) if args.mode == "hy" else None,
        "hy_offloaded_layers": sorted(set(row["offloaded_layers"] for row in all_results)) if args.mode == "hy" else None,
        "hy_device_bytes_allocated": sorted(set(row["device_bytes_allocated"] for row in all_results)) if args.mode == "hy" else None,
        "hy_fallback_count": sorted(set(row["fallback_count"] for row in all_results)) if args.mode == "hy" else None,
        "hy_cold_elapsed_ms": percentile_summary([row["cold_elapsed_us"] / 1000 for row in all_results
            if row.get("cache_ab") and row["cold_elapsed_us"] >= 0]) if args.mode == "hy" and args.cache_ab else None,
        "hy_cold_first_visible_ms": percentile_summary([row["cold_first_visible_ms"] for row in all_results
            if row.get("cache_ab")]) if args.mode == "hy" and args.cache_ab else None,
        "hy_warm_elapsed_ms": percentile_summary([row["warm_elapsed_us"] / 1000 for row in all_results
            if row.get("cache_ab") and row["warm_elapsed_us"] >= 0]) if args.mode == "hy" and args.cache_ab else None,
        "hy_warm_first_visible_ms": percentile_summary([row["warm_first_visible_ms"] for row in all_results
            if row.get("cache_ab")]) if args.mode == "hy" and args.cache_ab else None,
        "hy_tokens_per_second": percentile_summary([
            row["output_tokens"] * 1000 / max(1, row["decode_ms"]) for row in all_results
        ]) if args.mode == "hy" else None,
        "process_max_rss_kb": max(row["process_max_rss_kb"] for row in all_results),
        "literal_number_reference_sentences": len(reference_number_rows),
        "literal_number_exact_match_rate": (
            sum(row["literal_numbers_exact_match"] for row in reference_number_rows) / len(reference_number_rows)
            if reference_number_rows else None),
        "literal_number_metric_note": "Exact digit-string rendering only; no reference contains a number written out as words.",
        "named_entity_metric": None,
        "named_entity_metric_note": "FLEURS samples have no independent named-entity annotations.",
        "technical_terminology_metric": None,
        "technical_terminology_metric_note": "No in-domain technical audio or independently annotated glossary is present.",
        "end_to_end_caption_latency_ms": None,
        "opus_token_count": "not exposed by the current OPUS native engine; total completion latency is measured",
    }
    environment["thermal_after"] = thermal_snapshot()
    environment["load_average_after"] = os.getloadavg() if hasattr(os, "getloadavg") else None
    (args.out / "profiler").mkdir(exist_ok=True)
    (args.out / "profiler" / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n")
    (args.out / "translations.txt").write_text("".join(
        f"{row['run']}\t{row['id']}\t{row['translation']}\n" for row in all_results))
    print(json.dumps({"out": str(args.out), "mode": args.mode, "latency": metrics["translation_latency_ms"],
                      "chrf++": metrics["chrf_plus_plus"], "bleu": metrics["bleu"]}))


if __name__ == "__main__":
    main()
