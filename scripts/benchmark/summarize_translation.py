#!/usr/bin/env python3
"""Compare measured Dutch→English CPU translators without a blended score."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]


def load(root: Path, relative: str) -> dict[str, Any]:
    return json.loads((root / relative / "metrics.json").read_text())


def measure(metrics: dict[str, Any]) -> dict[str, float]:
    return {
        "chrf_plus_plus": float(metrics["chrf_plus_plus"]),
        "bleu": float(metrics["bleu"]),
        "p50_ms": float(metrics["translation_latency_ms"]["p50"]),
        "p95_ms": float(metrics["translation_latency_ms"]["p95"]),
    }


def dominates(left: dict[str, float], right: dict[str, float]) -> bool:
    return (left["chrf_plus_plus"] >= right["chrf_plus_plus"]
            and left["bleu"] >= right["bleu"]
            and left["p50_ms"] <= right["p50_ms"]
            and (left["chrf_plus_plus"] > right["chrf_plus_plus"]
                 or left["bleu"] > right["bleu"] or left["p50_ms"] < right["p50_ms"]))


def summarize(root: Path, policy: dict[str, Any]) -> dict[str, Any]:
    folders = {
        "OPUS tuning": "benchmark_results/postfix/translation/opus-nl-en",
        "Hy Q4 tuning": "benchmark_results/postfix/translation/hy-q4",
        "Hy Q5 tuning screen": "benchmark_results/postfix/translation/hy-q5-screen",
        "Hy Q6 tuning screen": "benchmark_results/postfix/translation/hy-q6-screen",
    }
    raw = {label: load(root, path) for label, path in folders.items()}
    rows = {label: measure(metrics) for label, metrics in raw.items()}
    baseline = rows["Hy Q4 tuning"]
    tuning_labels = list(rows)
    frontier = [label for label in tuning_labels if not any(
        dominates(rows[other], rows[label]) for other in tuning_labels if other != label)]

    validation = {
        "OPUS": measure(load(root, "benchmark_results/postfix/translation/opus-nl-en-validation")),
        "Hy Q4": measure(load(root, "benchmark_results/postfix/translation/hy-q4-validation")),
    }
    thresholds = policy["translation"]
    quantization_decisions = {}
    for label in ("Hy Q5 tuning screen", "Hy Q6 tuning screen"):
        candidate = rows[label]
        run_count = len(raw[label].get("runs", []))
        quality_not_regressed = all(
            candidate[metric] >= baseline[metric] - thresholds["maximum_reference_metric_regression_points_for_default_change"]
            for metric in ("chrf_plus_plus", "bleu"))
        latency_gain = (baseline["p50_ms"] - candidate["p50_ms"]) / baseline["p50_ms"]
        quality_gain = max(candidate["chrf_plus_plus"] - baseline["chrf_plus_plus"],
                           candidate["bleu"] - baseline["bleu"])
        promote = run_count >= thresholds["minimum_measured_runs_for_default_change"] and quality_not_regressed and (
            latency_gain >= thresholds["minimum_latency_gain_for_default_change"]
            or quality_gain >= thresholds["minimum_reference_metric_gain_points_for_default_change"]
        )
        quantization_decisions[label] = {
            "decision": "promote" if promote else "reject",
            "reason": ("passes versioned quality/latency thresholds" if promote else
                       "screen has fewer than three runs" if run_count < thresholds["minimum_measured_runs_for_default_change"] else
                       "slower or lower reference scores than Q4"),
            "runs": run_count,
        }

    return {
        "selection_source": "tuning and validation only; final_holdout is not read",
        "measurements": rows,
        "tuning_pareto_labels": frontier,
        "validation": validation,
        "quantization_decisions": quantization_decisions,
        "default_translator": "Hy Q4_K_M",
        "opus_mode": "retain only as explicit low-latency option; it is a quality/latency tradeoff, not a silent default replacement",
        "quality_caveat": "chrF++ and BLEU are approximate on ASR-conditioned FLEURS text; screens have one measured run",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, default=Path("benchmark_results/postfix/translation/decision_summary.json"))
    args = parser.parse_args()
    if "final_holdout" in str(args.out):
        raise SystemExit("Translation selector cannot write into final hold-out paths")
    policy = json.loads((args.root / "scripts/benchmark/benchmark_policy.json").read_text())
    result = summarize(args.root, policy)
    destination = args.root / args.out
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2) + "\n")
    print("Tuning Pareto: " + ", ".join(result["tuning_pareto_labels"]))
    for label, decision in result["quantization_decisions"].items():
        print(f"{label}: {decision['decision']} — {decision['reason']}")


if __name__ == "__main__":
    main()
