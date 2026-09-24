#!/usr/bin/env python3
"""Execute released ASR models on CPU. This does not test an Android microphone."""
import hashlib, pathlib, tarfile, urllib.request, wave, time
import numpy as np
import sherpa_onnx
root = pathlib.Path('build/asr-smoke'); root.mkdir(parents=True, exist_ok=True)
assets = [
 ('sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8', '5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf'),
 ('sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25', '393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96'),
]
for name, digest in assets:
    path = root / (name + '.tar.bz2')
    urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/' + path.name, path)
    with path.open('rb') as f: assert hashlib.file_digest(f, 'sha256').hexdigest() == digest
    with tarfile.open(path) as archive: archive.extractall(root, filter='data')
    d = root/name
    if 'parakeet' in name:
        r = sherpa_onnx.OfflineRecognizer.from_transducer(encoder=str(d/'encoder.int8.onnx'), decoder=str(d/'decoder.int8.onnx'), joiner=str(d/'joiner.int8.onnx'), tokens=str(d/'tokens.txt'), model_type='nemo_transducer', num_threads=2)
        audio = d/'test_wavs/en.wav'
    else:
        r = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(d/'conv_frontend.onnx'), encoder=str(d/'encoder.int8.onnx'), decoder=str(d/'decoder.int8.onnx'), tokenizer=str(d/'tokenizer'), num_threads=2)
        audio = d/'test_wavs/fast1.wav'
    with wave.open(str(audio)) as w:
        rate = w.getframerate(); samples = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)/32768
    stream = r.create_stream()
    if 'qwen3' in name: stream.set_option('language', 'Chinese')
    stream.accept_waveform(rate, samples); started = time.monotonic(); r.decode_stream(stream)
    result = stream.result.text
    assert len(result)>10
    if 'qwen3' in name: assert any('\u4e00' <= c <= '\u9fff' for c in result)
    else: assert 'country' in result.lower()
    print(name, result, 'CPU seconds', round(time.monotonic()-started, 3), flush=True)
    del stream, r
print('PASS: Parakeet English, Qwen3 Mandarin and per-stream language prompting')
