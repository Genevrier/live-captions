# Live Captions for Honor Magic V5

An Android app that listens through the phone microphone and shows on-device speech captions. It targets arm64-v8a / Android 10+ and uses a foreground microphone service. No root, Shizuku, Termux, external microphone, or cloud inference is needed for normal operation after model downloads.

## Current implementation

The default recognition model is **Nemotron 3.5 Streaming 0.6B INT8, 560 ms** on CPU (six inference threads). The default language is Dutch and the default target is English. The model is downloaded in the app, verified against the upstream archive SHA-256, and stored in app-private storage. Mandarin can use the multilingual Nemotron model, or the existing multilingual Whisper fallback. The older English-only Zipformer and Parakeet v2 models remain available; they should only be selected for English audio. Parakeet is never used as a Mandarin corrector.

Translation currently uses **ML Kit on-device translation**, downloaded through the same Download required models action. The UI names this translator explicitly. It is not OPUS-MT. Whisper's direct English translation remains available when a Whisper model is selected. Translation failures are shown instead of silently returning untranslated text. The audio queue is bounded; when decoding falls behind, the oldest audio chunk is discarded and the app shows a dropped-chunk count. Translation runs on a separate worker and delayed results update their caption by segment ID.

These capabilities still need implementation or device validation: pinned OPUS-MT models and decoder caching; a real QNN context and compatible Qualcomm native runtime; optional Parakeet v3 second-pass correction; and a physical Honor Magic V5 test. The app does not expose an NPU switch. CPU inference is labelled CPU.

## Build and install

The CI workflow builds and signs an arm64 release APK using a persistent keystore in GitHub Actions secrets, then uploads the APK and SHA-256. The signing keystore is also backed up outside this repository at `~/.local/share/live-captions-signing/release.jks` on the build operator's machine. Never commit it or its passwords. Subsequent CI builds use `LIVE_CAPTIONS_KEYSTORE_B64`, `LIVE_CAPTIONS_STORE_PASSWORD`, and `LIVE_CAPTIONS_KEY_PASSWORD` repository secrets.

For local builds, use JDK 17 and Android SDK 35. `./gradlew :app:testDebugUnitTest :app:assembleDebug` fetches a checksum-pinned sherpa-onnx v1.13.8 AAR. Install the signed APK from the latest successful CI artifact to retain the signing identity across updates.

The Nemotron archive is sourced from the [sherpa-onnx model release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) and is not committed to Git. The runtime is sourced from the [sherpa-onnx v1.13.8 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8).
