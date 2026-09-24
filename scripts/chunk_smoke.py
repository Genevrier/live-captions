#!/usr/bin/env python3
"""Verify distinct released Nemotron CPU graphs and execute recorded Dutch on each."""
import concurrent.futures, hashlib, io, pathlib, tarfile, time, urllib.request
import numpy as np
import pyarrow.parquet as pq
import sherpa_onnx
import soundfile as sf

ROOT = pathlib.Path('build/chunk-smoke')
ROOT.mkdir(parents=True, exist_ok=True)
ASSETS = {
    160: (475273363, 'a81909a1780d84cff16d73c15e13e67d9d81d8839faf14870d507d8499f7a61a'),
    320: (475272949, '5f311142337a5c161e92d49f7a3009d8607d3836f39d610bff5307c74d1d2c53'),
    560: (475271763, 'c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a'),
    1120: (475276334, 'adbdd5e9fef87300c37cebfcfc4f1ebe56845c860c8a760af0a1dd65ce9beed3'),
}

def fetch(url, path, digest, size=None):
    def valid():
        if not path.is_file() or (size and path.stat().st_size != size): return False
        with path.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest() == digest
    if not valid():
        temporary = path.with_suffix('.part')
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(path)
    assert valid(), path

def download(chunk):
    size, digest = ASSETS[chunk]
    name = f'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-{chunk}ms-int8-2026-06-11'
    archive = ROOT / (name + '.tar.bz2')
    fetch('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/' + archive.name, archive, digest, size)
    with tarfile.open(archive) as f: f.extractall(ROOT, filter='data')
    print('Verified model archive:', chunk, digest, flush=True)
    return chunk, ROOT / name

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
    models = list(executor.map(download, ASSETS))
corpus = ROOT / 'dutch.parquet'
fetch('https://huggingface.co/datasets/google/fleurs/resolve/168de341b3db6859a9bac1c50a2ef5e3b47647e0/nl_nl/test/0000.parquet', corpus,
      'a89456e5219b9cd311d1fedc9088a8fcaa4724c840de6db32df613a7968b15a1')
sample = pq.read_table(corpus).slice(0, 1).to_pylist()[0]['audio']['bytes']
assert hashlib.sha256(sample).hexdigest() == '4cfe25af0596ee2c709a9a375e1ac456d9821d91a067f7bfbf63ab07ba89e9cf'
audio, rate = sf.read(io.BytesIO(sample), dtype='float32')
for chunk, d in models:
    r = sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(d/'tokens.txt'),
        encoder=str(d/'encoder.int8.onnx'), decoder=str(d/'decoder.int8.onnx'),
        joiner=str(d/'joiner.int8.onnx'), num_threads=2)
    s = r.create_stream(); s.set_option('language', 'nl-NL')
    started = time.monotonic(); partials = set()
    for offset in range(0, len(audio), 1600):
        s.accept_waveform(rate, audio[offset:offset+1600])
        while r.is_ready(s): r.decode_stream(s)
        text = r.get_result(s)
        if text: partials.add(text)
    s.accept_waveform(rate, np.zeros(32000, np.float32)); s.input_finished()
    while r.is_ready(s): r.decode_stream(s)
    text = r.get_result(s)
    assert len(partials) >= 2 and 'organisaties' in text.lower() and 'beter' in text.lower(), (chunk, text)
    print(f'PASS CPU {chunk} ms: {text} | partials={len(partials)} seconds={time.monotonic()-started:.3f}', flush=True)
    r.reset(s); s.set_option('language', 'en-US')
    del s, r
print('PASS: all four distinct CPU chunk profiles loaded and executed. No QNN or physical-device claim.')
