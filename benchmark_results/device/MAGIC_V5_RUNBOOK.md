# Magic V5 device benchmark runbook

## Status and scope

No Magic V5 is connected in the current environment (`adb` is not installed). There are no new Snapdragon, Adreno, HTP, microphone, thermal, or on-device RAM results from this change. The checked-in FLEURS samples have human transcripts but are read speech. The YouTube robustness set uses automatic captions and is marked pseudo-ground-truth. `device_spontaneous_dutch_prompts.json` is a prompt bank only: recordings and human references still need to be supplied.

The app now has embedded, isolated WAV-injection stages A and D, plus translation stages B and C. A runs the exact same selected WAV through Nemotron CPU and QNN. D runs that WAV with Parakeet correction off and on; correction runs on a separate worker with a one-item pending queue. The imported WAV must be 16 kHz mono PCM16. Both stages show the WAV hash, human-reference WER and digit-token errors, actual ASR decode calls, input signal metrics, scheduling lag, process-group PSS, QNN child-process PSS and thermal status. D scores the combined corrected-or-original endpoint sequence against the full human transcript; per-segment outputs carry the exact source sample range and retained PCM hash. The first-text latency in this runner is ASR text only, not a translated useful subtitle.

B compares CPU and OpenCL for 1.8B Q4; C compares 1.8B Q4 and 7B Q4 using the manually selected backend. Enter one independent human English reference line per Dutch source line to get a reference WER; review translation meaning and omissions by a person as well, since lexical WER alone is not a quality judgment. It records a cold first request separately from warmed p50/p95, and it never writes benchmark results into profile preferences. On a build without OpenCL, B can only report CPU and cannot establish a backend winner. For E, use the live Listen/Stop path with the actual microphone; the current UI exposes first useful subtitle, session latencies/backlog, captured sample diagnostics and logcat traces. Isolated WAV runs do not include the live mic queue, translator, Compose display, or all engine competitors.

## Capture and references

Record the prompt bank as spontaneous speech, in a quiet room and in the listed everyday-noise conditions. Keep the speaker, device, distance, room, and noise condition in the manifest. Record each response once; do not read the prompts. Split by prompt before tuning: `tuning`, `validation`, and `final_holdout`. Do not use validation or final-holdout results to tune settings. Have a person transcribe each WAV verbatim in Dutch; do not copy ASR output. For the separate digit-token subscore, use a consistent human editorial convention that writes spoken number expressions as digits; keep the unnormalized wording in an annotation note. A second person may produce an independent English translation reference.

The device-corpus manifest schema is `live-captions-device-corpus-v1`. Each sample needs `id`, `benchmark_split`, `input_mode`, `audio_path`, file `sha256`, `reference_kind: human_verbatim`, and `reference_nl`. Microphone samples also need a capture trace with monotonic chunk timestamps, contiguous sample offsets/counts, and zero reported losses. Optional `correction_segments` include source sample offsets and the SHA-256 of the exact retained PCM16 byte slice. Validate with:

```sh
python3 scripts/benchmark/validate_device_corpus.py /path/to/device-manifest.json \
  --report /path/to/audio-diagnostics.json
```

The validator rejects format drift from 16 kHz mono PCM16, missing human references, hash mismatch, capture gaps, cross-split audio reuse, and corrected PCM that does not match its stated source sample range. It reports duration, RMS, peak, clipping, and sample counts. Keep a clean recording and a separate noise condition; do not add synthetic noise to substitute for real microphone noise.

## Fixed comparison order

Run each stage on the Magic V5 after installing the same signed APK and downloading only the candidate models needed by that stage. Use the tuning split first, freeze a candidate, confirm on validation, and only then use the final holdout once. Alternate or randomize candidate order across repetitions where setup permits; record cold model load separately from warm request timings.

| Stage | Input and variable | Required report |
| --- | --- | --- |
| A | Same normalized WAV selected in the app and Nemotron 560 ms model; CPU then QNN, one engine resident at a time | WER/substitutions/deletions/insertions, digit-token errors, actual decode-call p50/p95, accept p50/p95, QNN init and RPC/service timings, fallback/crash count, process-group PSS, thermal status |
| B | Same Dutch phrases; 1.8B Q4 CPU vs OpenCL, identical generation parameters | Human translation judgment/reference score, cold and warm latency, first visible vs completion, prefill/decode/sync, actual offload, fallback, tokens, PSS |
| C | 1.8B Q4 vs 7B Q4 on the backend chosen after B | Same phrase order and settings; human translation score and p50/p95; report each model's load and memory separately |
| D | Same WAV and CPU Nemotron backend; Parakeet correction off vs on, Parakeet on a second worker | ASR error/number/negation changes, time to final, correction queue/decode/hash/sample range, correction skips and process-group memory |
| E | Best validated combination for 20–30 minutes of continuous speech | First useful subtitle, stability/revisions, WER and omissions, p50/p95 latencies, finalization, queue/backlog, dropped samples/translations, process-group PSS, temperature/thermal status |

For E, report isolated engines separately from a concurrent-engine run. The service writes transcript-free `LiveCaptionsRun` samples every five seconds and `CaptionPipeline` writes monotonic per-event traces. Retain the full logcat trace and summarize it instead of inferring a 20–30 minute distribution from one final snapshot:

```sh
adb logcat -v threadtime -s LiveCaptionsRun CaptionPipeline LiveCaptionsBenchmark > magic-v5-e.log
python3 scripts/benchmark/summarize_live_trace.py magic-v5-e.log \
  --json benchmark_results/device/magic-v5-e-summary.json
```

The summary reports first useful subtitle, event latency p50/p95, queue/backlog and loss counters, process-group and `:qnn` PSS samples, thermal samples, and the QNN phases exposed by the runtime. WER and omissions must still be scored against retained audio and the manifest's human verbatim reference. For the mic/injection comparison, use the same recorded prompt WAV for A/D and play that same content from a second device at the Magic V5 microphone for the live run; record the speaker, distance, and room condition. The acoustic capture is not sample-identical to direct injection. Do not preload all engines: load one candidate at a time, then test only the useful resident set under the concurrent run.

For QNN, preserve the current private `:qnn` process, fallback, and bounded RPCs. The current sherpa API exposes `OnlineRecognizer.decode()` as one combined execution; unless an instrumented runtime supplies graph-level counters, report encoder/decoder/joiner as unavailable rather than allocating that combined time to individual graphs. Measure client queue/RPC, QNN service work, recognizer preparation, audio feed, combined decode, and result handling before deciding whether transport or graph placement needs work.

## Before/after record

### Code-level choices pending phone validation

These changes control what will be measured; they are not performance findings.

| Setting | Before (`51cd8af`) | Current candidate | Decision |
| --- | --- | --- | --- |
| Fast translation profile | 7B Q4 | 1.8B Q4 | Keep the explicit 1.8B default so Fast cannot silently load/benchmark 7B |
| QNN / OpenCL default | May be selected automatically by device/autotune state | CPU unless the user explicitly selects the backend | Keep conservative selection; measure A/B before recommending either backend |
| ASR chunk / endpoint | 560 ms and existing endpoint | Unchanged | Retain as baseline; no shorter endpoint promoted |
| Translation threads and batch | Device autotune could select a saved combination | 4 threads, batch/ubatch 256/128 until manually changed | Do not tune without device accuracy, latency, RAM and thermal results |
| Parakeet | Enabled by default in Max on eligible profiles | Still optional in Max; A/B stage D exposes incremental load and correction effects | Do not claim benefit until human-reference validation |

No device parameter has been promoted or rejected on measured Snapdragon, Adreno or HTP performance. The table above records defaults and safeguards only.

| Candidate | Split | Backend/profile | Accuracy and omissions | First useful subtitle | p50/p95 | Finalize | Backlog/loss | Process-group PSS | Thermal |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Existing `51cd8af` | not measured on phone | not measured | not measured | not measured | not measured | not measured | not measured | not measured | not measured |
| Current changes | not measured on phone | not measured | not measured | not measured | not measured | not measured | not measured | not measured | not measured |

No parameter is promoted from this runbook. Keep an optimization only after the validation split shows no accuracy or omission regression and the device measurement demonstrates an improvement.

## Connection needed to run

Connect the Magic V5 to the same host by USB, enable Android developer options and USB debugging, unlock it, accept the computer's RSA prompt, and provide Android Platform Tools (`adb`). Then verify `adb devices -l` shows the Magic V5 as `device` (not `unauthorized` or `offline`). The device run can then install the repository's signed APK, collect logcat/thermal/memory traces, and execute the paired tests. Until then, only host unit tests and Android builds are valid; DGX or other host results cannot substitute for Snapdragon/Adreno/HTP measurements.
