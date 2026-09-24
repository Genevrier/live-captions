# Accelerator status

CPU remains the portable baseline and automatic fallback. No Honor Magic V5 was
connected for this build, so physical QNN/OpenCL execution and performance are
not claimed. Android thermal safeguards are untouched.

## QNN: implemented, experimental, SM8750 only

The release/CI build (`-Pqnn=true`) recompiles sherpa-onnx 1.13.8 with
`SHERPA_ONNX_ENABLE_QNN=ON`. The source archive, QNN headers and runtime bundles
are checksum-pinned in `scripts/build_qnn.sh`. The Kotlin API and ONNX Runtime
1.28.2 remain from the verified original AAR. CI builds the experimental variant;
the default app setting still uses CPU.

The model comes from `asr-models-qnn-binary-3`:
`sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-560ms.tar.bz2`,
442,651,414 bytes, SHA-256
`a5af6d03ebba0425074e38d0ebd819fff88ff404fd7340433de30bb515dbbd51`.
The verified archive contains encoder.bin (609,431,552), decoder.bin (29,978,624),
joiner.bin (9,732,096), tokens.txt and an explicit 560-ms info.txt. CPU ONNX models
are a separate installation and are never supplied to QNN's context loader.
Additional QNN chunk choices are not exposed. Upstream now has separate
160/320/1120-ms SM8750 context archives with digests, but they have not been loaded
or executed on the target hardware here. Their existence does not validate them.
The newly supported CPU chunk profiles never enter the QNN context loader. On a
Magic V5/SM8750 release build, QNN is the default request unless the user has
explicitly chosen another backend; the isolated service still selects CPU if
QNN cannot initialize or fails. The performance panel reports the backend
actually active for recognition.

The QNN API gets `provider="qnn"`, `modelType="nemo_transducer"`, absolute HTP
and System library paths, and the three comma-separated context paths. Every
stream receives `setOption("language", "nl-NL"/"en-US"/"zh-CN")`, also after reset.
[The upstream implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/qnn/online-recognizer-nemo-transducer-qnn-impl.h)
reads this option and passes its prompt ID to the encoder.
[The exporter](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/.github/workflows/export-nemotron-3.5-asr-streaming-0.6b-qnn.yaml)
uses QNN 2.40.

The APK packages QAIRT 2.40.0.251030 HTP, System and HTP-v79 Stub libraries; the
matching Hexagon v79 skeleton is an APK asset copied into private app storage.
ADSP_LIBRARY_PATH includes that directory. The manifest declares the optional
`libcdsprpc.so` system library so Android 12+ can expose the DSP RPC dependency. Host libraries are extracted by the
Android installer and checked for 16 KB ELF alignment. The DSP skeleton is not an
Android arm64 ELF and is not placed in the Android native-library directory.

Sherpa can call `_Exit` on native QNN failure. The app therefore confines QNN to a
non-exported `:qnn` service process. The parent owns the microphone, bounded audio
queue and captions. Initialization/decoding RPCs have deadlines; process death or
a deadline failure kills/unbinds only this dedicated worker and selects the
verified CPU models. A failure during speech marks a discontinuity. The UI shows
the actual chosen backend and fallback reason. This recovery implementation has
compiled/unit-test coverage, but still needs device fault-injection testing.

## Qualcomm provenance and redistribution

The SDK download endpoint returned HTTP 403 in this environment. We inspected the
same 2.40.0.251030 SDK archive referenced by sherpa's exporter, mirrored at
`csukuangfj/qnn-toolkit`, revision `d4ae84ce94aada9a0e553cedbd092e105e4a7ebc`.
Archive SHA-256:
`423ab04883150ab6c18de01f2adf23829fa9e8602a5a6b573473e3516921a3b9`.
All four packaged Qualcomm binaries are byte-identical to their matching entries
in that SDK. `package_qnn.py` prints build provenance hashes. No runtime libraries
or proprietary SDK headers are committed to Git.

The SDK's AI Stack License, section 1(iv), permits object-code distribution as
part of an application; standalone redistribution is excluded. The unmodified
LICENSE.pdf and QNN_NOTICE.txt are included in the APK. These components are
proprietary Qualcomm software, not relicensed under this repository's license.
[Qualcomm's own packaging notice](https://github.com/qualcomm/geniex-qairt-plugin/blob/main/THIRD_PARTY_NOTICES.md)
also identifies the SDK license as governing the QNN components.

## Hy-MT2 OpenCL path: built, device validation pending

The pinned llama.cpp revision explicitly lists Snapdragon 8 Elite / Adreno 830
and Q4_0, Q4_K, Q5_K, Q6_K and Q8_0 support. Android builds set
`GGML_OPENCL=ON` and `GGML_OPENCL_USE_ADRENO_KERNELS=ON`; this also compiles the
upstream Adreno XMEM kernels and uses llama.cpp's OpenCL program-binary cache.
`n_batch` / `n_ubatch` are selected per loaded context, never changed while a
context is resident. The model is loaded with all layers offered to the detected
Adreno 830. A runtime without that exact device, or a failed load/first decode,
uses a CPU model instead. A decode-time OpenCL failure reloads the same model on
CPU and retries the sentence. The UI reports OpenCL only after the model/context
load; any fallback changes the label to CPU.

CI pins and verifies the Khronos OpenCL headers and ICD loader. The APK contains
the Khronos dispatch layer only, not Qualcomm's proprietary driver. It asks
Android for the optional `libOpenCL.so` native library; the device vendor ICD
must be visible in the app's linker namespace and enumerate an Adreno 830. This
is a buildable path, not proof the Honor/MagicOS image exposes its driver to a
normal app. Models, contexts and the program cache remain resident/private to
the app while listening; the disk kernel cache is under app-private files.

The in-app autotuner compares CPU with Adreno OpenCL for pinned Q4_K_M, Q5_K_M,
Q6_K and Q8_0 bundles, and tries four batch/ubatch settings. It warms each
configuration and repeats each supplied sentence five times, reporting load,
average/p95 inference time, prefill/decode time, PSS and available RAM. It keeps
the fastest result only when its outputs exactly match the Q6_K CPU reference
for all supplied lines. That one-shot consistency gate is not a translation
accuracy test or a sustained listening benchmark. Q5_K_M is a pinned Apache-2.0
community quantization; Q8_0 and Q4_K_M/Q6_K are from Tencent's model repository.
The repository has no verified Q4_0 Hy-MT2 7B asset, so Q4_0 is not offered.

No phone benchmark has run in this environment. Adreno execution, CPU-vs-GPU
speed/quality, context residency under thermal load and ASR queue behavior remain
to be measured on the actual Magic V5. `GGML_OPENCL_USE_ADRENO_BIN_KERNELS` is
not enabled because the upstream binary package targets Snapdragon X2, not this
Adreno 830. The upstream defaults handle workgroups; no custom OpenCL kernels or
unmeasured kernel overrides are introduced.

The matching SDK's `qnn-context-binary-utility` successfully parsed all three
released contexts on the aarch64 Linux host. They report build
`v2.40.0.251030114326_189385`, core API 2.30.0, backend API 5.40.0, context blob
3.3.3, `socModel=69` (SM8750), and `dspArch=79`. The encoder has a `prompt_index`
input and 128-bin features (sherpa derives its feature configuration from the
context). Extracted graph signatures are recorded in
[validation/qnn-context-metadata.json](validation/qnn-context-metadata.json).
Context parsing validates metadata/runtime format compatibility; it does not
execute the NPU graphs.
