#!/usr/bin/env python3
"""Run a bounded, tuning-only Nemotron parameter sweep.

The sweep changes one parameter family at a time, saves every configuration
under a unique directory, and never reads validation or final hold-out data.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def candidates() -> list[dict]:
    result = []
    for chunk in (80, 160, 320, 560, 1120):
        result.append({"id": f"chunk-{chunk}", "parent": "baseline/asr-maxspeed",
                       "changed": {"chunk_ms": chunk}, "chunk": chunk, "threads": 6,
                       "rule2": 1.4, "language": "nl-NL"})
    for threads in (2, 4, 8):
        result.append({"id": f"threads-1120-{threads}", "parent": "experiments/chunk-1120",
                       "changed": {"threads": threads}, "chunk": 1120, "threads": threads,
                       "rule2": 1.4, "language": "nl-NL"})
    for threads in (2, 4, 8):
        result.append({"id": f"threads-560-{threads}", "parent": "baseline/asr-maxspeed",
                       "changed": {"threads": threads}, "chunk": 560, "threads": threads,
                       "rule2": 1.4, "language": "nl-NL"})
    for chunk, parent in ((560, "baseline/asr-maxspeed"), (1120, "experiments/chunk-1120")):
        for endpoint in (0.4, 0.7):
            label = str(endpoint).replace(".", "p")
            result.append({"id": f"endpoint-{chunk}-{label}", "parent": parent,
                           "changed": {"rule2_seconds": endpoint}, "chunk": chunk, "threads": 6,
                           "rule2": endpoint, "language": "nl-NL"})
    result.append({"id": "language-560-auto", "parent": "baseline/asr-maxspeed",
                   "changed": {"language": "auto"}, "chunk": 560, "threads": 6,
                   "rule2": 1.4, "language": "auto"})
    # Combined only after separate chunk, language, and endpoint sweeps.
    result.append({"id": "combined-1120-auto-0p7", "parent": "baseline/asr-maxspeed",
                   "changed": {"chunk_ms": 1120, "language": "auto", "rule2_seconds": 0.7},
                   "chunk": 1120, "threads": 6, "rule2": 0.7, "language": "auto"})
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("benchmark_results/postfix/sweep"))
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--only", help="Comma-separated experiment IDs; default runs every tuning candidate")
    args = parser.parse_args()
    if args.runs != 3:
        raise SystemExit("Use exactly three measured runs per tuning configuration")
    plan = candidates()
    if args.only:
        selected = set(args.only.split(","))
        unknown = selected - {item["id"] for item in plan}
        if unknown:
            raise SystemExit(f"Unknown experiment IDs: {sorted(unknown)}")
        plan = [item for item in plan if item["id"] in selected]
    args.out.mkdir(parents=True, exist_ok=True)
    if any((args.out / item["id"] / "metrics.json").exists() for item in plan):
        raise SystemExit("Output already contains a measured result; choose a new --out directory")
    index = {"split": "tuning", "runs_per_configuration": args.runs,
             "decision_policy": "Tune on FLEURS train only; review accuracy/latency/instability jointly; no scalar score.",
             "experiments": []}
    (args.out / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    for item in plan:
        output = args.out / item["id"]
        command = [sys.executable, str(Path(__file__).with_name("run_asr.py")),
                   "--out", str(output), "--split", "tuning", "--runs", str(args.runs),
                   "--mode", "maxspeed", "--chunk-ms", str(item["chunk"]),
                   "--threads", str(item["threads"]), "--rule1-seconds", "2.4",
                   "--rule2-seconds", str(item["rule2"]), "--language", item["language"]]
        print(f"START {item['id']}: parent={item['parent']} changed={item['changed']}", flush=True)
        with (output.parent / f"{item['id']}.runner.log").open("w") as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        metrics = json.loads((output / "metrics.json").read_text())
        index["experiments"].append({
            **item,
            "metrics_before": "see parent configuration's metrics.json",
            "metrics_after": {key: metrics[key] for key in
                               ("asr_wer", "asr_cer", "rtf", "processing_ms",
                                "stable_source_latency_ms", "partial_revisions_per_utterance",
                                "rewritten_visible_words_per_utterance")},
            "decision": "pending validation; keep/reject is recorded in BENCHMARK_REPORT.md",
            "output": str(output),
        })
        (args.out / "experiment_index.json").write_text(json.dumps(index, indent=2) + "\n")
        print(f"DONE {item['id']}: WER={metrics['asr_wer']:.4f} CER={metrics['asr_cer']:.4f} "
              f"RTF={metrics['rtf']['mean']:.4f}", flush=True)


if __name__ == "__main__":
    main()
