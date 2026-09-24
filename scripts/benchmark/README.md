# Reproducible Dutch benchmark

This harness freezes public, versioned data and model inputs outside Git. It
keeps training/tuning, validation and final test references in separate split
labels. Do not use `final_holdout` to choose parameters. The pinned Nemotron
runner exercises the same sherpa-onnx streaming graph, language prompt,
100 ms PCM callback size, endpoint rules, and stable-prefix/admission logic as
the app. It is a CPU component test: it does not instantiate Android's
`CaptionSession`, microphone capture, QNN service, OpenCL device, Compose UI,
or Android queue scheduling. The native translation runner calls the same C++
translation core used by the app, but does not run the Android orchestration.

## Setup

Use Python 3.12, `ffmpeg`, CMake, a C++17 compiler, and the pinned packages:

```sh
python3 -m venv .venv-benchmark
. .venv-benchmark/bin/activate
python -m pip install -r scripts/benchmark/requirements.txt
```

Create the stratified Dutch FLEURS set and aligned English references:

```sh
python scripts/benchmark/prepare_fleurs.py
```

The pinned FLEURS revision is `168de341b3db6859a9bac1c50a2ef5e3b47647e0`.
The script downloads full Dutch and English parquet shards only after checking
their exact byte counts and SHA-256 digests. It selects 30 official train
examples (tuning), 10 validation examples, and 10 official test examples
(final hold-out), stratified across duration deciles. FLEURS's official English
parallel transcripts provide independent translation references. The known
prior smoke-test row 1927 is excluded from hold-out. Audio files are converted
to mono 16 kHz s16le WAV and checked into the generated manifest by hash.

The requested video is a separate Dutch robustness corpus:

```sh
python scripts/benchmark/fetch_youtube.py
```

It saves the original audio, creates a mono 16 kHz WAV, attempts all available
subtitle tracks (requesting Dutch/English first), and records download failures,
provenance, and file hashes. This video's only retrieved Dutch captions are
automatic speech recognition and must be labelled **PSEUDO-GROUND-TRUTH**; they
are not a primary WER reference. YouTube currently rate-limits some tracks with
HTTP 429; the manifest records the incomplete track download.

After fetch, make a single full-video robustness case and run it only after
selecting tuning parameters. It is intentionally separate from all FLEURS
splits and must not be used to claim true WER or tune a model:

```sh
python scripts/benchmark/prepare_youtube_robustness.py
python scripts/benchmark/run_asr.py --root build/benchmark-data \
  --manifest build/benchmark-data/youtube-robustness.json \
  --out benchmark_results/robustness/youtube-560 \
  --split robustness --runs 1 --mode realtime --chunk-ms 560 --threads 6
```

## ASR baseline and controlled tuning

The frozen default is Nemotron 3.5 Streaming ASR 0.6B INT8, 560 ms exported
graph, Dutch `nl-NL`, six CPU threads, sherpa's Kotlin endpoint defaults
(2.4/1.4/20.0 seconds), 100 ms / 1,600-sample input frames. The runner verifies
the release archive checksum, warms a resident recognizer, and writes its
configuration, every utterance hypothesis, word/character error counts,
latency distributions, and host profiler information.

```sh
python scripts/benchmark/run_asr.py \
  --out benchmark_results/baseline/asr-maxspeed \
  --split tuning --runs 3 --mode maxspeed \
  --chunk-ms 560 --threads 6 --rule1-seconds 2.4 --rule2-seconds 1.4

python scripts/benchmark/run_asr.py \
  --out benchmark_results/baseline/asr-realtime \
  --split tuning --runs 3 --mode realtime \
  --chunk-ms 560 --threads 6 --rule1-seconds 2.4 --rule2-seconds 1.4

python scripts/benchmark/run_asr_sweep.py --out benchmark_results/postfix/sweep
```

Max-speed mode reports processing time and RTF; its wall clock is deliberately
not reported as user-perceived latency. Real-time mode schedules PCM against
source timestamps and reports first-partial, stable-source and endpoint delays.
The speech boundary in this dataset runner is an RMS energy estimate, not a
human-annotated phonetic boundary, so endpoint latency is approximate. A
translation/caption latency is not inferred from ASR-only timing.

The upstream export set has separate CPU graphs at 80, 160, 320, 560 and
1120 ms; chunk size is never a runtime scalar. The app only exposes the four
profiles already present in its catalog. The sweep changes one parameter
family at a time, writes a per-run experiment index, and never reads validation
or final hold-out samples. Run validation only after a tuning candidate is
chosen:

```sh
python scripts/benchmark/run_asr.py --out benchmark_results/validation/<candidate> \
  --split validation --allow-validation --runs 3 \
  --chunk-ms 1120 --threads 6 --rule1-seconds 2.4 --rule2-seconds 1.4
```

Do not run final hold-out until the configuration is locked. It is an explicit,
single attempt; the runner creates a lock marker under the generated data root:

```sh
python scripts/benchmark/run_asr.py --out benchmark_results/final_holdout \
  --split final_holdout --allow-final-holdout --runs 1 \
  --chunk-ms 1120 --threads 6 --rule1-seconds 2.4 --rule2-seconds 1.4
```

## Translation benchmark

Build the native runner against the repository's pinned llama.cpp,
SentencePiece, ONNX Runtime and C++ translation core:

```sh
ort=$(python -c 'import pathlib,onnxruntime; print(next((pathlib.Path(onnxruntime.__file__).parent/"capi").glob("libonnxruntime.so.*")))')
cmake -S app/src/main/cpp -B build/native-benchmark \
  -DCMAKE_BUILD_TYPE=Release -DORT_LIBRARY="$ort"
cmake --build build/native-benchmark --target translation-benchmark -j2
```

Run Hy-MT2 7B Q4_K_M against OPUS-MT Dutch→English on identical run-1
Nemotron hypotheses from the tuning split:

```sh
python scripts/benchmark/run_translation.py --mode hy \
  --binary build/native-benchmark/translation-benchmark \
  --out benchmark_results/translation/hy-q4
python scripts/benchmark/run_translation.py --mode opus \
  --binary build/native-benchmark/translation-benchmark \
  --out benchmark_results/translation/opus
```

Every model file is downloaded to ignored `build/`, checked against the pinned
revision, size and SHA-256, then atomically renamed. BLEU and chrF++ against
parallel FLEURS English are reference-based but approximate for ASR-conditioned
outputs; they are not a substitute for a professional human translation
assessment. OPUS and Hy see the same run-1 Nemotron hypotheses. The current C++
Hy implementation is non-streaming, so TTFT is the first generated token and
translation-complete time is measured per full utterance. This comparison does
not simulate Android's provisional queue or measure provisional-caption latency.

## What this runner can establish

It can compare the actual CPU Nemotron graphs and the actual local C++ Hy/OPUS
engines on this host. It cannot establish Honor Magic V5 QNN delegation,
Adreno execution, Android audio queue behavior, device thermals, sustained
15–30 minute operation, or offline behavior on the phone. Those require the
physical device and its runtime logs. No host result is presented as a Magic V5
measurement.
