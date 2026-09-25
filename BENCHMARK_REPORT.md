# Dutch live-caption benchmark report

Run date: 2026-09-25. App/source baseline: commit `6d79e448f8dfe3519b0d4c76966199eba222a48e`. The complete raw configurations, per-run CSV/JSON, hypotheses, logs, and host profiler samples are under [`benchmark_results/`](benchmark_results/). The measurement code and reproduction steps are in [`scripts/benchmark/README.md`](scripts/benchmark/README.md).

## What the app actually runs

The default Dutch recognizer is Nemotron 3.5 Streaming ASR 0.6B INT8 from sherpa-onnx 1.13.8. The selected exported graph is the separate 560 ms model (archive SHA-256 `c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a`), with Dutch `nl` supplied to each stream. Its streaming state remains open for a listening session. The CPU graph choices are 160, 320, 560 and 1120 ms; these are separate model assets, not a runtime chunk scalar. QNN has a separate SM8750/HTP v79 560 ms context, but no physical-device run or operator-delegation trace was available.

The microphone path is built-in `AudioRecord`, mono 16 kHz PCM16 converted into 1,600-sample/100 ms float chunks. Dutch Nemotron uses sherpa's endpoint rules; it does not run a separate VAD model. The effective rules measured here were 2.4 s trailing silence, 1.4 s trailing silence after recognized text, and 20 s minimum utterance duration. The other VAD classes belong to other recognizer paths.

Magic V5 policy selects Max Quality. That preset selects Nemotron 560 ms, Hy-MT2 7B Q4_K_M, and enables optional Parakeet endpoint correction for Dutch. The Hy model is pinned at revision `ab8472660ac61fac25f1af43fac2599d52a8a775`; Q4 file SHA-256 is `9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b` (Apache-2.0). The app keeps model objects resident for a session. Hy uses a 2,048-token context, 256/128 batch/ubatch, four inference threads, one sentence per request, and at most 384 output tokens; sampling is top-k 20, top-p 0.6, temperature 0.7, repetition penalty 1.05, seed 42. It clears llama.cpp's KV/memory state between requests, so there is no cross-sentence KV reuse. The optional Parakeet correction worker defaults to four threads. The current app's provisional translation policy requires two stable hypotheses at a complete-word boundary, 650 ms debounce, at least four stable characters, and a four-character material change. The audio queue is bounded to 16 100 ms chunks; provisional translation is coalesced to one item, final queue capacity is two, and endpoint correction capacity is one. Endpoint translations take priority.

This benchmark executed Nemotron through the CPU ONNX Runtime provider and Hy/OPUS through the native CPU path. The run configuration explicitly records `qnn_active: false` and `opencl_active: false`. Neither a QNN label in app settings nor packaged native libraries establish device execution. No Magic V5, QNN operator report, Adreno/OpenCL profiler, Android PCM injection, or phone runtime logs were available. Therefore this report makes no claim that this handset ran ASR on NPU or translation on GPU.

## Data and protocol

The primary corpus is the pinned Dutch FLEURS split (`168de341b3db6859a9bac1c50a2ef5e3b47647e0`): 30 train/tuning, 10 validation and 10 official test utterances, selected across duration deciles. Mono 16 kHz PCM WAVs and independent Dutch references were used for ASR; their parallel English text was used for translation. The split/reference manifest is [`benchmark_results/fleurs_manifest.json`](benchmark_results/fleurs_manifest.json). The FLEURS manifest marks the source dataset CC-BY-4.0; attribution is to Google Research's [FLEURS dataset](https://huggingface.co/datasets/google/fleurs). Audio is re-downloaded by pinned revision and remains out of Git. FLEURS is read speech, so it does not cover spontaneous conversation, varied Dutch accents, multiple speakers, technical vocabulary or realistic background noise.

The requested video `PMZi3f5kfmI` (“Lange periode met nazomerweer! | 10-daagse”, 444 s) was downloaded with its original audio and converted to mono 16 kHz PCM WAV. Dutch was confirmed by the video and the recognizer output. The available Dutch captions were YouTube automatic captions, not human subtitles; their comparison is explicitly **PSEUDO-GROUND-TRUTH**. The metadata manifest [`benchmark_results/robustness/youtube_source_manifest.json`](benchmark_results/robustness/youtube_source_manifest.json) records no manual caption tracks and two duplicate Dutch auto-caption files; HTTP 429 prevented the remaining downloads. The robustness output is in [`benchmark_results/robustness/youtube-560/`](benchmark_results/robustness/youtube-560/) and measured 14.57% WER / 10.03% CER against those pseudo-labels. It was not used to tune the selected profile.

The runner pins audio/model revisions and verifies model archive size and SHA-256 before extraction. Each tuning ASR configuration has three measured runs. Translation A/B uses the same run-1 Nemotron hypotheses for each translator. Validation is separate from tuning. The hold-out was run once, after locking the profile; the exact one-time run marker is [`benchmark_results/final_holdout/attempt.json`](benchmark_results/final_holdout/attempt.json). No settings were changed from its results.

## Baseline and measured results

ASR error rates are measured on Dutch transcript references. Latency and RTF are component-level host measurements; RTF is processing time divided by audio duration. The sweep rows use the same harness and tuning split. Host scheduling and thermal conditions varied, so small latency differences should not be treated as stable hardware wins.

| Configuration | Split | WER | CER | RTF mean | Processing p50 | Stable source p50/p95 | Endpoint→ASR final p50/p95 |
|---|---|---:|---:|---:|---:|---:|---:|
| Nemotron 560 ms, `nl-NL`, 6 threads (clean baseline) | tuning, 90 utterance-runs | 14.13% | 4.68% | 0.119 | 1,206 ms | — | — |
| Same, realtime input | 5 tuning utterances × 3 | 11.43% | 1.98% | 0.145 | — | 1,293 / 1,643 ms | 2,778 / 5,278 ms |
| Same frozen profile | validation, 30 utterance-runs | 14.55% | 8.40% | 0.078 | 780 ms | — | — |
| Same frozen profile | final test, 10 utterances, one run | 10.00% | 3.70% | 0.086 | 785 ms | — | — |

Realtime boundaries are an RMS-energy estimate with appended silence, not human phonetic annotations. Its end delays are approximate ASR-only timings. The host baseline ran under high system load and thermal readings up to 84°C, while later sweep runs were cooler. That makes baseline-to-sweep latency percentages unreliable; the result does not demonstrate an app or device speed improvement. The clean baseline's per-run logs and load/thermal readings are retained beside its metrics.

The corrected max-speed ASR harness saw 12.47 partial revisions and 4.50 rewritten visible words per utterance on the 90 tuning utterance-runs; the stable prefix itself did not regress after publication. Its scheduling estimate admitted 1.62 provisional and 1.07 final translation requests per utterance. These are simulated admission counts from recognizer hypotheses, not actual app queue/Hy calls per minute. Realtime first-partial and stable-source latency were 689 ms and 1,293 ms p50. The host translation benchmark then showed Hy's median prefill at 3.40 s and decode at 1.84 s; prefill is the larger measured Hy component. This identifies CPU Hy translation as the measured component latency bottleneck, but not necessarily the Magic V5 bottleneck.

### ASR tuning and validation

| Nemotron graph / candidate | Tuning WER / CER | Tuning RTF | Validation WER / CER | Decision |
|---|---:|---:|---:|---|
| 80 ms | 18.30% / 8.12% | 0.474 | — | Reject: worse accuracy and throughput |
| 160 ms | 15.89% / 6.08% | 0.188 | — | Reject: worse than 560 ms on tuning |
| 320 ms | 13.48% / 5.16% | 0.108 | 20.91% / 11.70% | Reject: tuning gain did not generalize |
| 560 ms baseline | 14.13% / 4.68% | 0.077 | 14.55% / 8.40% | Keep as validated baseline |
| 1120 ms | 13.16% / 4.92% | 0.052 | 17.27% / 9.72% | Reject: faster on host, validation accuracy regressed |
| 560 ms, automatic language | 13.80% / 4.45% | 0.114 | 14.55% / 8.98% | Reject: validation WER ties, CER and RTF are worse; keep `nl-NL` |
| 1120 ms, AUTO, 0.7 s endpoint | 13.64% / 4.18% | 0.078 | 18.64% / 9.06% | Reject: validation regression |

Thread sweeps at 560 ms gave identical WER/CER. Host mean RTF was 0.106 (2 threads), 0.079 (4), 0.077 (6), and 0.082 (8). Six threads remains the measured host choice, but its small edge over four is not enough to assert it is fastest on Snapdragon. At 1120 ms, eight threads improved RTF by only about 0.4% over six and uses more CPU. Shorter endpoint thresholds and combined Rule 1/Rule 2 candidates mostly increased WER; none survived validation. The detailed candidates and outputs are retained under [`postfix/sweep/`](benchmark_results/postfix/sweep/), [`postfix/endpoint-rule1/`](benchmark_results/postfix/endpoint-rule1/), and [`validation-postfix/`](benchmark_results/validation-postfix/).

The tuning-only Pareto utility lists `threads-1120-8`, `endpoint-1120-0p4`, `endpoint-1120-0p7`, and `combined-1120-auto-0p7`; they remain Pareto points because each trades accuracy against throughput. The validation gate rejects each tested 1120 ms candidate for an accuracy regression. Thus the Pareto result is not a new winner: shorter graphs lose accuracy or throughput, while longer context fails to generalize. No ASR parameter change was promoted to the app. The versioned policy and generated per-candidate before/after decisions are [`benchmark_policy.json`](scripts/benchmark/benchmark_policy.json) and [`decision_summary.json`](benchmark_results/postfix/sweep/decision_summary.json).

### Dutch→English translation A/B

| Translator | Split | chrF++ | BLEU | Completion p50 / p95 | TTFT p50 | Prefill / decode p50 | Tokens/s p50 | Process max RSS |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| OPUS-MT nl→en, CPU | tuning, 90 outputs | 51.02 | 24.28 | 175 / 374 ms | n/a | n/a | n/a | 550 MiB |
| Hy-MT2 7B Q4_K_M, CPU | tuning, 90 outputs | 58.72 | 35.31 | 5,136 / 9,065 ms | 3,406 ms | 3,402 / 1,839 ms | 12.21 | 4,744 MiB |
| OPUS-MT nl→en, CPU | validation, 30 outputs | 51.30 | 19.84 | 210 / 524 ms | n/a | n/a | n/a | 552 MiB |
| Hy-MT2 7B Q4_K_M, CPU | validation, 30 outputs | 57.33 | 24.15 | 6,397 / 16,334 ms | 3,580 ms | 3,575 / 2,687 ms | 11.08 | 4,746 MiB |
| Hy-MT2 7B Q4_K_M, CPU | final test, 10 outputs | 58.36 | 30.03 | 6,467 / 7,663 ms | 4,037 ms | 4,032 / 2,426 ms | 11.61 | 4,741 MiB |

On tuning, Hy added 7.70 chrF++ and 11.04 BLEU points over OPUS, with 29.3× higher median completion time on this CPU host. Validation kept the quality ordering, with +6.03 chrF++ and +4.30 BLEU but about 30.5× higher median latency. The two translators remain a quality/latency Pareto pair: OPUS is faster; Hy scores better against these references. These automatic metrics are approximate for ASR-conditioned text, not COMET or human evaluation. Only three reference sentences contain a number and the exact digit-string rendering check was 0%; no separate semantic number, named-entity, negation, units, date or technical-terminology annotation was available.

Q5_K_M and Q6_K were screened once each on the same tuning hypotheses. Q5 scored 58.12 chrF++ / 34.64 BLEU at 6,657 ms median; Q6 scored 57.73 / 34.68 at 6,933 ms median, versus Q4's 58.72 / 35.31 at 5,136 ms in the three-run benchmark. Neither screen supports replacing Q4. Q8 was not run. The generated translation Pareto/decision summary keeps OPUS and Hy Q4 as separate latency/quality points, rejects Q5/Q6, and is [`decision_summary.json`](benchmark_results/postfix/translation/decision_summary.json). Hy context/debounce variants, OPUS provisional cadence versus Hy inside the Android queue, Parakeet correction quality, and sustained phone thermals were not measured.

## Bugs, changes and decisions

The app's normal Stop path cancelled workers without calling the ASR engine's `finish()` method, so trailing captured samples and the final recognizer endpoint could be lost. This was fixed in commit `6d79e44`: normal stop now preserves the last partial PCM frame, drains the bounded audio queue, flushes the recognizer, drains final translation/correction workers, and only then releases native resources. QNN's streaming engine also receives its finish call. Regression tests cover drained mailboxes and final caption state during Stop. The host ASR runner had a matching endpoint-order bug (counting endpoint output as partial and omitting a trailing phrase); it now mirrors endpoint/final order and flushes at stream end, with fake-stream tests.

No tuning candidate passed validation strongly enough to justify changing the app's selected ASR parameters. The Q4 Max Quality translation path remains the quality choice; OPUS remains an explicitly separate fast/A-B option. There is no measured end-to-end improvement to report: promoted production ASR WER and latency changed by 0%, and target-device app latency was not measured. The measured Hy-vs-OPUS difference is a quality/latency tradeoff, not a change from a matched production baseline. Chunk, endpoint, language, quantization and validation experiments generated more than three rejected candidate directions, so the loop stopped without promoting an unsupported optimization. No change was made to claim NPU/GPU execution or better target-device performance.

Candidate profiles based on current code and the component measurements:

| Profile | Candidate | Evidence/status |
|---|---|---|
| Low latency | Nemotron 560 ms + OPUS-MT provisional | Fastest measured host translator; lower chrF++/BLEU than Hy; not measured in Android queue |
| Balanced | Nemotron 560 ms + Hy-MT2 7B Q4_K_M | Selected ASR validation baseline and higher translation scores; host CPU translation is slow |
| Max accuracy | Nemotron 560 ms + optional Parakeet endpoint hypothesis + Hy Q4 | Available in app policy; Parakeet acceptance/accuracy and combined sustained operation were not benchmarked |

These are candidates, not Magic V5 recommendations. In particular, none establishes that QNN or Adreno OpenCL improves sustained caption latency.

## Reproduction and remaining gaps

Run the commands and environment setup in [`scripts/benchmark/README.md`](scripts/benchmark/README.md). The frozen inputs and result directories are:

- ASR baseline and realtime: [`baseline/asr-maxspeed-clean/`](benchmark_results/baseline/asr-maxspeed-clean/), [`baseline/asr-realtime-five/`](benchmark_results/baseline/asr-realtime-five/)
- Tuning/validation: [`postfix/sweep/`](benchmark_results/postfix/sweep/), [`validation-postfix/`](benchmark_results/validation-postfix/)
- Translation A/B and quant screens: [`postfix/translation/`](benchmark_results/postfix/translation/)
- Single final hold-out: [`final_holdout/`](benchmark_results/final_holdout/)

This is a reproducible model-component benchmark, not the requested full Android end-to-end benchmark: there is no WAV source injected into `CaptionSession`, no Android queue/UI event trace per utterance, and no device connected to measure QNN delegation, Adreno kernels, process/system memory under the target runtime, or 15–30 minute thermal drift. The host is Linux aarch64 with Cortex-X925/A725 CPU and an NVIDIA environment, not SM8750; it had roughly 127 GB system memory and varying load. Android microphone latency, offline operation with networking disabled, transcription/translation together under real app scheduling, and on-device benchmark profiles remain unverified. The generated YouTube corpus is one Dutch weather narrator with automatic subtitles; the primary FLEURS set is read speech. The app APK builds and automated tests pass, but these results do not satisfy the user's requested physical-device performance validation or prove an end-to-end improvement.
