#!/usr/bin/env python3
"""Summarize transcript-free CaptionService/CaptionSession logcat traces."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


KV = re.compile(r"([A-Za-z][A-Za-z0-9_]*)=([^\s]+)")
TAGS = ("LiveCaptionsRun", "CaptionPipeline", "LiveCaptionsBenchmark")
EVENT_METRICS = {
    "asr-endpoint": ("endpointWaitMs",),
    "asr-finish-complete": ("finalizeMs",),
    "correction-started": ("queueWaitMs",),
    "correction-complete": ("computeMs",),
    "hy-started": ("queueWaitMs",),
    "hy-progress-displayed": ("elapsedMs",),
    "hy-displayed-to-state": ("computeMs", "displayMs", "firstVisibleMs", "completeMs"),
    "hy-result-computed": ("computeMs", "displayMs", "firstVisibleMs", "completeMs"),
    "opus-started": ("queueWaitMs",),
    "qnn-profile": ("initMs", "rpcP50Ms", "rpcP95Ms", "serviceP95Ms", "binderP95Ms",
                    "feedMs", "decodeCombinedMs", "resultMs", "endpointMs"),
}
SAMPLE_METRICS = (
    "appGroupPssKb", "qnnPssKb", "availableKb", "captureBacklogMs", "captionBacklogMs",
    "audioDepth", "provisionalDepth", "finalDepth", "audioClipped", "audioTimestampGaps",
    "thermalMaxC", "thermalSensors",
)
QNN_NS_FIELDS = {
    "initializeTotal": "init_ns",
    "libraryLoad": "library_ns",
    "dspCopy": "dsp_copy_ns",
    "dspSetup": "dsp_setup_ns",
    "recognizerPrepare": "recognizer_prepare_ns",
    "clientQueueP50": "client_queue_p50_ns",
    "clientQueueP95": "client_queue_p95_ns",
    "rpcP50": "rpc_p50_ns",
    "rpcP95": "rpc_p95_ns",
    "serviceP50": "service_p50_ns",
    "serviceP95": "service_p95_ns",
    "binderP50": "binder_p50_ns",
    "binderP95": "binder_p95_ns",
    "audioFeed": "feed_ns",
    "combinedDecode": "combined_decode_ns",
    "result": "result_ns",
    "endpoint": "endpoint_ns",
}


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)]


def summary(values: list[float]) -> dict[str, float | int] | None:
    if not values:
        return None
    return {
        "count": len(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "max": max(values),
    }


def records_from_log(path: Path) -> tuple[dict[str, list[dict[str, str]]], int]:
    sessions: dict[str, list[dict[str, str]]] = defaultdict(list)
    parsed = 0
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            if not any(tag in line for tag in TAGS):
                continue
            fields = dict(KV.findall(line))
            session = fields.get("session")
            if not session:
                continue
            if "LiveCaptionsRun" in line:
                fields["recordType"] = "sample"
            elif "LiveCaptionsBenchmark" in line and "qnn_profile" in line:
                fields["recordType"] = "qnn"
            elif "CaptionPipeline" in line:
                fields["recordType"] = "event"
            else:
                continue
            sessions[session].append(fields)
            parsed += 1
    for rows in sessions.values():
        rows.sort(key=lambda row: int(row.get("atNs", "0")) if row.get("atNs", "0").isdigit() else 0)
    return sessions, parsed


def number(row: dict[str, str], key: str) -> float | None:
    try:
        value = float(row[key])
    except (KeyError, TypeError, ValueError):
        return None
    return value if math.isfinite(value) and value >= 0 else None


def summarize_session(rows: list[dict[str, str]]) -> dict[str, Any]:
    samples = [row for row in rows if row["recordType"] == "sample"]
    events = [row for row in rows if row["recordType"] == "event"]
    qnn_profiles = [row for row in rows if row["recordType"] == "qnn"]
    event_counts = Counter(row.get("event", "unknown") for row in events)
    latencies: dict[str, list[float]] = defaultdict(list)
    for row in events:
        event = row.get("event", "")
        for key in EVENT_METRICS.get(event, ()):
            value = number(row, key)
            if value is not None:
                latencies[f"{event}.{key}"].append(value)
    sampled = {
        key: stats
        for key in SAMPLE_METRICS
        if (stats := summary([value for row in samples if (value := number(row, key)) is not None])) is not None
    }
    first_useful = [value for row in samples if (value := number(row, "firstUsefulMs")) is not None]
    dropped = [number(row, "droppedAudioMs") for row in samples]
    skipped = [number(row, "skippedTranslations") for row in samples]
    run_elapsed = [number(row, "elapsedNs") for row in samples]
    sample_counts = [number(row, "audioSamples") for row in samples]
    rate = [number(row, "audioHz") for row in samples]
    thermal = Counter(row.get("thermal", "Unknown") for row in samples)
    qnn_presence = Counter(row.get("qnnPresent", "false") for row in samples)
    qnn_timings = {
        f"{name}Ms": stats
        for name, field in QNN_NS_FIELDS.items()
        if (stats := summary([value / 1_000_000 for row in qnn_profiles
                              if (value := number(row, field)) is not None])) is not None
    }
    qnn_calls = [number(row, "calls") for row in qnn_profiles]
    return {
        "samples": len(samples),
        "events": len(events),
        "qnnProfileRecords": len(qnn_profiles),
        "qnnCallsAtProfile": max((value for value in qnn_calls if value is not None), default=None),
        "qnnProfileMs": qnn_timings,
        "qnnGraphBreakdown": sorted({row.get("graph_breakdown") for row in qnn_profiles if row.get("graph_breakdown")}),
        "eventCounts": dict(sorted(event_counts.items())),
        "eventLatencyMs": {key: value for key, values in sorted(latencies.items()) if (value := summary(values))},
        "sampleDistributions": sampled,
        "firstUsefulSubtitleMs": min(first_useful) if first_useful else None,
        "durationObservedMs": round((max(run_elapsed) - min(run_elapsed)) / 1_000_000) if len(run_elapsed) > 1 else None,
        "audioSampleRateHz": max(rate) if rate else None,
        "audioSamples": max(sample_counts) if sample_counts else None,
        "droppedAudioMsFinal": dropped[-1] if dropped else None,
        "skippedTranslationsFinal": skipped[-1] if skipped else None,
        "thermalSamples": dict(sorted(thermal.items())),
        "qnnProcessSamples": dict(sorted(qnn_presence.items())),
        "humanReferenceScoring": "not derivable from logcat; score retained audio against the manifest's human_verbatim reference",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logcat", type=Path, help="text captured with adb logcat -v threadtime")
    parser.add_argument("--session", help="summarize one session id; defaults to all sessions")
    parser.add_argument("--json", type=Path, help="write machine-readable JSON here")
    args = parser.parse_args()
    sessions, parsed = records_from_log(args.logcat)
    if args.session:
        sessions = {args.session: sessions[args.session]} if args.session in sessions else {}
    report = {
        "schema": "live-captions-logcat-summary-v1",
        "parsedRecords": parsed,
        "sessions": {session: summarize_session(rows) for session, rows in sorted(sessions.items())},
    }
    if not sessions:
        print("No matching CaptionSession records found.", file=sys.stderr)
        return 2
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.json:
        args.json.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
