# Preserved pre-fix experiments

The first exploratory files under `experiments/asr-*`, the old `validation/asr-*`
folders, `baseline/asr-realtime-n5`, and the initial OPUS/Hy runs are retained
for audit only. Do not use them for profile selection or final metrics. The
corrected replacements are now complete under `baseline/asr-maxspeed-clean`,
`baseline/asr-realtime-five`, `postfix/`, `validation-postfix/`, and
`final_holdout/`.

The benchmark harness initially treated the hypothesis returned on an endpoint
callback as another provisional partial, and could omit a final phrase after an
earlier endpoint. The Android implementation finalizes before emitting another
partial and flushes the remaining stream when stopped. The corrected runner now
matches that order, flushes the final phrase, measures flush processing time,
and has a fake-stream regression test. The corrected baseline, tuning,
validation, OPUS-vs-Hy A/B and the one-time final hold-out were run with that
version. See [`BENCHMARK_REPORT.md`](../BENCHMARK_REPORT.md) for the selected
configuration and its limitations.
