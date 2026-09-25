#!/usr/bin/env python3
"""Create tuning Pareto and validation-gated keep/reject decisions.

Reads only the tuning sweep, its frozen 560 ms baseline, and named validation
runs from benchmark_policy.json. It deliberately has no hold-out input option.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text())


def values(metrics: dict[str, Any]) -> dict[str, float]:
    return {
        "wer": float(metrics["asr_wer"]),
        "cer": float(metrics["asr_cer"]),
        "rtf": float(metrics["rtf"]["mean"]),
        "stable_source_ms": float(metrics["stable_source_processing_ms"]["p50"]),
        "rewrites": float(metrics["rewritten_visible_words_per_utterance"]),
    }


def dominates(left: dict[str, float], right: dict[str, float]) -> bool:
    dimensions = ("wer", "cer", "rtf", "stable_source_ms", "rewrites")
    return all(left[k] <= right[k] for k in dimensions) and any(left[k] < right[k] for k in dimensions)


def summarize(root: Path, policy: dict[str, Any], sweep: Path) -> dict[str, Any]:
    index = load(root / sweep / "experiment_index.json")
    baseline = values(load(root / "benchmark_results/baseline/asr-maxspeed-clean/metrics.json"))
    validation_baseline = values(load(root / "benchmark_results" /
                                      policy["asr"]["baseline_validation"] / "metrics.json"))
    records = []
    for experiment in index["experiments"]:
        path = root / experiment["output"] / "metrics.json"
        after = values(load(path))
        before = values(load(root / experiment["parent"] / "metrics.json"))
        records.append({"id": experiment["id"], "path": experiment["output"], "before": before, "after": after,
                        "changed": experiment["changed"], "pareto": False})

    for row in records:
        row["pareto"] = not any(dominates(other["after"], row["after"])
                                 for other in records if other["id"] != row["id"])

    thresholds = policy["asr"]
    decisions = []
    for row in records:
        candidate_id = row["id"]
        validation_dir = thresholds["validation_mapping"].get(candidate_id)
        validation_raw = load(root / "benchmark_results" / validation_dir / "metrics.json") if validation_dir else None
        validation = values(validation_raw) if validation_raw else None
        validation_run_count = len(validation_raw.get("runs", [])) if validation_raw else 0
        comparison = validation or row["after"]
        gate_baseline = validation_baseline if validation else baseline
        wer_gain = (gate_baseline["wer"] - comparison["wer"]) / gate_baseline["wer"]
        cer_gain = (gate_baseline["cer"] - comparison["cer"]) / gate_baseline["cer"]
        rtf_gain = (gate_baseline["rtf"] - comparison["rtf"]) / gate_baseline["rtf"]
        stable_gain = (gate_baseline["stable_source_ms"] - comparison["stable_source_ms"]) / gate_baseline["stable_source_ms"]
        rewrite_delta = comparison["rewrites"] - gate_baseline["rewrites"]
        reasons = []
        if candidate_id == "chunk-560":
            decision = "keep-baseline"
            reasons.append("frozen 560 ms CPU profile")
        elif validation is None:
            decision = "reject-unvalidated"
            reasons.append("candidate was not promoted to validation")
        elif validation_run_count < thresholds["minimum_measured_runs_for_promotion"]:
            decision = "reject-insufficient-validation-runs"
            reasons.append(f"validation has {validation_run_count} runs; policy requires {thresholds['minimum_measured_runs_for_promotion']}")
        else:
            quality_ok = (
                validation["wer"] <= validation_baseline["wer"] + thresholds["max_validation_wer_regression"]
                and validation["cer"] <= validation_baseline["cer"] + thresholds["max_validation_cer_regression"]
            )
            stability_ok = rewrite_delta <= thresholds["maximum_rewrite_increase_per_utterance"]
            material_gain = max(wer_gain, cer_gain, rtf_gain, stable_gain) >= thresholds["minimum_latency_or_accuracy_gain"]
            if not quality_ok:
                decision = "reject-validation-accuracy"
                reasons.append("validation WER/CER regression exceeded policy")
            elif not stability_ok:
                decision = "reject-caption-stability"
                reasons.append("visible-word rewrites increased")
            elif not material_gain:
                decision = "reject-no-material-gain"
                reasons.append("no accuracy or latency gain reached the 10% threshold")
            else:
                decision = "keep-candidate"
                reasons.append("validation passed regression and material-gain thresholds")
        decisions.append({
            "id": candidate_id,
            "parent_metrics_before": row["before"],
            "tuning_metrics_after": row["after"],
            "validation_metrics_after": validation,
            "validation_path": validation_dir,
            "validation_run_count": validation_run_count,
            "tuning_pareto": row["pareto"],
            "changed": row["changed"],
            "decision": decision,
            "conclusion": "; ".join(reasons),
        })
    return {
        "policy_version": policy["version"],
        "selection_source": "tuning plus named validation only; final_holdout is not read",
        "baseline": baseline,
        "validation_baseline": validation_baseline,
        "tuning_pareto_ids": [row["id"] for row in records if row["pareto"]],
        "decisions": decisions,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--sweep", type=Path, default=Path("benchmark_results/postfix/sweep"))
    parser.add_argument("--out", type=Path, default=Path("benchmark_results/postfix/sweep/decision_summary.json"))
    args = parser.parse_args()
    if "final_holdout" in str(args.sweep) or "final_holdout" in str(args.out):
        raise SystemExit("The sweep summarizer cannot read or write final hold-out paths")
    policy = load(args.root / "scripts/benchmark/benchmark_policy.json")
    report = summarize(args.root, policy, args.sweep)
    out = args.root / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Pareto candidates: {', '.join(report['tuning_pareto_ids'])}")
    for row in report["decisions"]:
        print(f"{row['id']}: {row['decision']} — {row['conclusion']}")


if __name__ == "__main__":
    main()
