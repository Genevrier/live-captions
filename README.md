# Live Captions for Honor Magic V5

Local microphone captions for arm64 Android (Android 10+, optimized for Snapdragon
8 Elite). Download the selected models, tap **Listen**, then **Stop**. No cloud
inference, external microphone, root, Shizuku or Termux is used. Audio stays in a
bounded in-memory buffer and is not saved or uploaded.

## Profiles

| Profile | Recognition | Provisional translation | Final translation |
|---|---|---|---|
| **Dutch → English (default)** | Nemotron 3.5 0.6B INT8, 560 ms | OPUS-MT nl-en INT8 | Hy-MT2 7B Q6_K on Magic V5; 1.8B Q8_0 elsewhere |
| Mandarin → English | Qwen3-ASR 0.6B INT8, VAD phrases up to 4 s | Source transcript while final is pending | Selected Hy-MT2 7B or 1.8B |
| English → French | Nemotron 3.5 0.6B INT8, 560 ms | OPUS-MT en-fr INT8 | Selected Hy-MT2 7B or 1.8B |

The Honor Magic V5 starts in **MAX QUALITY** mode: Nemotron 560 ms and Hy-MT2
7B Q6_K on CPU. FAST and BALANCED presets select smaller Hy-MT2 models. Selecting
a preset never silently enables an untested accelerator or second-pass recognizer.
Settings include a sequential on-device translation A/B control for installed
1.8B Q8, 7B Q4 and 7B Q6 models. Compare outputs with a human reference before
judging accuracy; latency and memory telemetry alone cannot rank quality.
The 7B model requires 2 GiB of available system memory beyond its estimated model
and ASR file sizes; low-memory devices should choose a smaller model explicitly.

All translation defaults use native CPU inference. OPUS uses SentencePiece,
separate encoder execution and a merged decoder with cached self/cross attention.
Hy-MT2 uses pinned llama.cpp, resets its context per request, and supports an
optional `source -> target` glossary. Q6_K and Q4_K_M are selectable memory/speed
alternatives; all three quantizations have local model execution evidence. ML Kit is an explicitly selected local
fallback, never a silent substitution. Whisper base is a compatibility ASR option;
it transcribes source text through the same translation pipeline.

Dutch and English can optionally use Parakeet TDT 0.6B v3 for an endpoint second
hypothesis. It is off by default, limited to one pending utterance, and skipped
when work falls behind. A valid timely result revises the same caption and is
translated again. Parakeet is never used for Mandarin. This heuristic is not a
claim that Parakeet is always more accurate.

## Reliability and controls

- Independent microphone, recognition, provisional translation, final translation
  and optional correction workers; bounded queues and explicit overload counters.
- Stable source prefixes, caption segment IDs and monotonically increasing
  revisions reject stale results. Provisional, final, revised and skipped states
  are visible. Source text appears below the prominent translation.
- Stop/restart invalidates old work. Native resources are released by their owning
  worker; cancellation does not free an in-use recognizer or translator.
- Performance panel: recognition time/RTF, translation and correction time,
  endpoint-to-caption latency, queue depths, audio backlog and dropped work.
  Endpoint latency starts at the recognizer's endpoint event, not a measured
  acoustic end-of-speech timestamp.
- Model downloads use pinned URLs, sizes and SHA-256, temporary files, verified
  installation, retries and progress. Installed models are rehashed before use.
  Settings → **Verify / repair required models** repairs damaged installations.
- Dutch Magic V5 default download is approximately 6.8 GB; optional correction adds
  487 MB. Model files require additional space during verified installation.

## CPU, QNN and GPU

CPU is the default. The CI APK also includes a real **experimental QNN/NPU** path
for **SM8750 only**, using the released 560-ms contexts and matching QAIRT 2.40 /
HTP v79 libraries. It is opt-in, visibly labeled experimental and isolated in a
private process. Native failure or timeout falls back to the downloaded CPU model.
Only a successfully initialized QNN session is labeled QNN; CPU fallback is
explicit. No additional QNN chunk option is exposed. CPU has separately verified
160/320/560/1120-ms model profiles; 560 ms remains the default.

Hy-MT2 GPU/OpenCL offload is **not enabled**. See [accelerator investigation,
provenance and limitations](docs/ACCELERATION.md). No thermal safeguards are
changed. No physical Honor Magic V5 test or real-time performance guarantee is
claimed; phone benchmarking is still required, especially under simultaneous ASR,
translation and correction load.

## Build and validation

Optional floating captions use Android's **Display over other apps** permission;
no Accessibility Service is used. Settings include opacity, font size, 1–4
translation lines, source text and touch-through. Turn touch-through off to drag;
the listening notification can show/hide the window. Position is remembered
separately for compact and expanded screens. In-app captions require no overlay
permission. See [overlay and chunk-profile validation](docs/OVERLAY.md).

Settings → Download / remove models manages pinned model installations. New CPU
chunk profiles require a successful on-device load/decode test before selection.
Performance includes model/backend/chunk, latencies, RTF, bounded-queue backlog
and PSS (including QNN worker), main-process RSS, native and Java heaps,
estimated model file sizes and available system RAM.

CI builds a persistently signed arm64 APK and runs the unit tests, ZIP integrity,
signature, manifest, native ABI and 16 KB alignment checks. APK and SHA-256 are
uploaded as workflow artifacts. A separate **Model execution smoke** workflow
runs the shared native translation core and pinned ASR models on a CPU runner.
Those results establish model execution on a host, not an Android microphone test.
[Model pins and provenance](docs/MODELS.md), [recorded validation](docs/validation/).

Build requirements: JDK 17, Android SDK/build-tools 35, NDK 28.2.13676358,
CMake 3.22.1. A CPU development APK can be built with:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

For the QNN variant, follow `.github/workflows/ci.yml`: fetch the verified original
AAR, extract its arm64 ORT library, run `scripts/build_qnn.sh` with ANDROID_NDK set,
then build with `-Pqnn=true`. Proprietary SDK headers/runtime binaries and model
weights are downloaded during builds or app setup and are kept out of Git.

Release signing uses repository secrets `LIVE_CAPTIONS_KEYSTORE_B64`,
`LIVE_CAPTIONS_STORE_PASSWORD`, and `LIVE_CAPTIONS_KEY_PASSWORD`. The persistent
key is backed up outside the repository on the operator's machine. Never commit
the key or its passwords. Use APKs signed with this identity for subsequent updates.
Both workflows verify the pinned public signing certificate and upload
`LiveTranslate-MagicV5.apk` with its `.apk.sha256` in one artifact.
See [persistent signing and secret setup](docs/SIGNING.md) for recovery commands
and the version-code rule for future releases.

## MAX QUALITY status

Qwen3-ASR 1.7B Dutch endpoint recognition, a Dutch Qwen-versus-Parakeet A/B
accuracy winner, Hy-MT2 7B Q5, Adreno OpenCL offload and physical-device QNN
validation are not included in this APK. The 7B Q4 and Q6 models' host load/output smoke
and the on-device translation A/B control do not establish real-time performance
on the Honor phone. The app retains bounded queues and the CPU fallback.
