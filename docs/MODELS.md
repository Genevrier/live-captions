# Pinned models and runtime validation

The source of truth for translation download URLs, exact file sizes and SHA-256 values is `app/src/main/assets/translation-models.json`. The app verifies each file before atomic publication and requires a complete verified bundle before use. ASR archive pins are in `ModelCatalog.kt`; installed contents have a manifest and are rehashed before native model loading.

- sherpa-onnx v1.13.8 is the latest release returned by GitHub on 2026-09-24. Its AAR SHA-256 is checked by CI. The Android AAR bundles ONNX Runtime 1.28.2; native OPUS links to that same packaged library. Vendored ORT headers are from commit `33ca9628233dc8f002435e868d4c2e9f82766ca1` (v1.28.2), MIT licensed.
- llama.cpp v0.5.0 is pinned to `7fe450e19305b828c199d602c23a8337aaa1f03b`, with a source archive hash in CMake. MIT licensed. The CPU bridge uses the GGUF chat template, a 2048-token context, a 384-token output ceiling, and KV caching. Exceeding the limit is an error, never a silently truncated final caption. Sampling follows Tencent's recommended temperature 0.7, top-p 0.6, top-k 20, repetition penalty 1.05; seed 42 supports repeatable smoke tests.
- Tencent's official Hy-MT2 1.8B GGUF revision is `a0c709d9fac510f2c807aa3af52872340dc37a4a`. Q8_0 is preferred, with explicit Q6_K and Q4_K_M options. The model card declares Apache-2.0. Downloads come from Tencent's repository, not a third-party re-quantization.
- Xenova OPUS-MT nl-en is pinned to `82c42d95d1508037cbe7172d85fd4e940a2f3584`. It converts Helsinki-NLP's Apache-2.0 OPUS-MT model. We use the quantized encoder and merged decoder, the repository's source/target SentencePiece models and shared vocabulary. SentencePiece v0.2.2 is pinned to `e0cce7d37b065b5140349dbe12c6bcf6192fdd78` (Apache-2.0). The implementation maps pieces through vocab.json, appends EOS, starts from the configured decoder-start token, suppresses PAD and retains first-step cross-attention caches. Provisional translation deliberately uses greedy generation for latency; the upstream default uses six beams. Hy-MT2 provides quality finals.
- No QNN or GPU execution is claimed by the CPU build.

The shared C++ translation core has executed local smoke tests for Dutch → English, Chinese → English with a wafer/overlay glossary, and English → French. Repeated OPUS calls test cache reset; cancellation is tested before new inference. These host results are not physical Honor phone measurements. `scripts/native_smoke.py` repeats the tests with pinned downloads. `scripts/verify_apk.py` checks ELF ABI and 16 KB alignment; CI separately checks APK ZIP alignment and signature.

Sources: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8 ; https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF ; https://huggingface.co/Xenova/opus-mt-nl-en ; https://huggingface.co/Helsinki-NLP/opus-mt-nl-en ; https://github.com/ggml-org/llama.cpp ; https://github.com/google/sentencepiece

## Optional second hypothesis

Parakeet TDT 0.6B v3 INT8 uses the sherpa ASR release archive pinned in
ModelCatalog, SHA-256 `5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf`.
The [upstream model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
licenses the model CC-BY-4.0 and includes Dutch and English among its languages;
Mandarin is excluded. Only NL/EN endpoint correction is exposed. Local host
execution passed; runtime admission limits optional work to one utterance,
RTF <= 0.5, no queued final translations and <=100 ms audio queue. Results over
3 seconds late are rejected. This is not phone benchmarking or guaranteed accuracy.

## Mandarin recognition

Qwen3-ASR 0.6B INT8 (2026-03-25 sherpa export) is the Mandarin default.
The archive digest is `393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`.
[Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR) and its model use Apache-2.0.
The archive README attributes the ONNX export to
[Wasser1462/Qwen3-ASR-onnx](https://github.com/Wasser1462/Qwen3-ASR-onnx).
Sherpa 1.13.8 ships the corresponding Android JNI model configuration.
The runtime receives `setOption("language", "Chinese")` on every offline stream;
this is the actual decoder prompt API. The app uses 128-bin features and the
released convolution frontend, encoder, decoder and complete tokenizer directory.
Local execution of the released Mandarin audio passed. Four-second VAD phrase
limits bound work; this is not token-by-token streaming. Nemotron remains an
explicit broader-coverage Mandarin option and Whisper is a compatibility option.
Parakeet correction is never enabled for this profile.

OPUS English→French is pinned to Xenova revision
`28726206f80896b90035bd99cccd5cc1e151f916`; every file digest is in
`translation-models.json`. Its shared SentencePiece vocabulary has 59,514 entries,
PAD/decoder-start 59,513 and EOS 0. The decoder has six layers, eight heads, 512
hidden dimensions. The same merged-decoder KV cache implementation passed two
consecutive English→French requests. Provisional decoding is greedy; Hy-MT2
supplies the separately generated final output.

The recorded Dutch smoke test uses Google FLEURS (CC-BY-4.0), `nl_nl` test row 0,
converted dataset revision `168de341b3db6859a9bac1c50a2ef5e3b47647e0`. The corpus
and extracted audio hashes are pinned in `scripts/asr_smoke.py`. Nemotron produced
multiple real partial transcripts and a Dutch final with `language=nl`.
This single-sample loading/streaming test is not an accuracy benchmark. Sample
weights/audio remain outside Git and the APK.
