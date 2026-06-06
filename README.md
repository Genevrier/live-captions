# Live Captions — offline on-device ASR for Pixel 9 Pro

An Android app that listens through the microphone and prints what it hears, in real time,
**100% on-device / offline**. No cloud, no Google APIs, no account.

## What it does

- Captures ambient speech and shows a live, scrolling transcript.
- Four switchable recognition engines (all run offline via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.2 + ONNX Runtime):

| Engine | Model | Size (download) | Latency | Notes |
|---|---|---|---|---|
| **Streaming** (default) | Streaming Zipformer int8 (`2023-06-26`) | ~296 MB archive → ~70 MB kept | word-by-word, ~300 ms | English, very good |
| **Accuracy** | NVIDIA Parakeet TDT 0.6B v2 int8 + Silero VAD | ~460 MB | utterance, ~1 s | English, SOTA (OpenASR leader) |
| **Multi-base** | Whisper base int8 + Silero VAD | ~197 MB | utterance, ~1 s | ~90 languages incl. Turkish |
| **Multi-small** | Whisper small int8 + Silero VAD | ~609 MB | utterance, ~2-4 s | ~90 languages, best accuracy |

Models are **downloaded on first use** into app storage and then work fully offline forever
(verify with airplane mode). Silero VAD is bundled in the APK.

### Languages & translation

The toolbar's **translate** icon opens language settings:

- **Spoken language** (Multilingual engines): the language Whisper should expect — Turkish and
  ~15 others. This is the source language for recognition.
- **Translate to**: off, or any of 16 targets (English, Turkish, German, …). Translation is
  shown on top with the **original transcription beneath it**, and works with any engine.

Two translation paths are used automatically, both fully offline:

- **Whisper → English**: when a Multilingual engine targets English, Whisper's built-in
  `task=translate` does it in one pass (no extra download). E.g. Turkish speech → English text.
- **[ML Kit](https://developers.google.com/ml-kit/language/translation) → any target**: for
  every other target (e.g. Turkish→German, English→Turkish) the recognized text is translated by
  ML Kit's on-device models (~30 MB per language, downloaded once, then offline).

## Why these choices (state of the art, mid-2026)

- **Runtime:** sherpa-onnx is the mature offline on-device ASR stack — prebuilt arm64 JNI,
  streaming + offline recognizers, Silero VAD, all the current open models.
- **Models:** Parakeet TDT 0.6B leads the Hugging Face OpenASR leaderboard; streaming Zipformer
  gives true low-latency captions (RTF ~0.062, i.e. ~16× faster than real time on a phone CPU).
- **Quantization:** int8 dynamic quant is the on-device standard — ~half the size, faster CPU
  inference, negligible WER loss. Inference runs on **CPU + 4 threads** (the reliable provider;
  NNAPI/GPU on Tensor are flaky for these graphs).

## Build

Requires JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`; NDK not required —
native `.so` files come from the vendored AAR).

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The sherpa-onnx runtime is vendored at `app/libs/sherpa-onnx-1.13.2.aar` (arm64-v8a only, to
match the Pixel 9 Pro and keep the APK small).

## Install & run (Pixel 9 Pro)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Launch **Live Captions**, grant Microphone (and Notifications on Android 13+).
2. Pick an engine in the top bar (default **Streaming**), tap **Download** once.
3. Tap **Listen** and speak — captions appear live. A persistent notification shows it's
   listening; tap **Stop** there or in-app to end.

## Architecture

```
audio/AudioCapture      AudioRecord 16 kHz mono → FloatArray chunks (own thread)
service/CaptionService  foreground (type=microphone) service; queue decouples capture from decode
asr/StreamingEngine     OnlineRecognizer (Zipformer) → partial + endpointed finals
asr/OfflineVadEngine    Silero VAD → OfflineRecognizer (Parakeet) per utterance
model/ModelRepository   download .tar.bz2 + extract (commons-compress) → filesDir
service/CaptionState    StateFlow bridge service → Compose UI
ui/CaptionScreen        transcript, engine picker, download gate, Listen/Stop
```
