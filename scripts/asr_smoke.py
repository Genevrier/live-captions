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

# Recorded Dutch, FLEURS test split row 0 (CC-BY-4.0, Google FLEURS).
# Keep corpus/model weights outside Git and pin both archive and extracted sample.
name = 'sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11'
archive = root/(name+'.tar.bz2')
urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/'+archive.name, archive)
with archive.open('rb') as f: assert hashlib.file_digest(f, 'sha256').hexdigest() == 'c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a'
with tarfile.open(archive) as f: f.extractall(root, filter='data')
corpus = root/'dutch.parquet'
urllib.request.urlretrieve('https://huggingface.co/datasets/google/fleurs/resolve/168de341b3db6859a9bac1c50a2ef5e3b47647e0/nl_nl/test/0000.parquet', corpus)
with corpus.open('rb') as f: assert hashlib.file_digest(f, 'sha256').hexdigest() == 'a89456e5219b9cd311d1fedc9088a8fcaa4724c840de6db32df613a7968b15a1'
import pyarrow.parquet as pq, soundfile as sf, io
row = pq.read_table(corpus).slice(0,1).to_pylist()[0]
assert hashlib.sha256(row['audio']['bytes']).hexdigest() == '4cfe25af0596ee2c709a9a375e1ac456d9821d91a067f7bfbf63ab07ba89e9cf'
audio, rate = sf.read(io.BytesIO(row['audio']['bytes']), dtype='float32')
d=root/name
r=sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(d/'tokens.txt'),encoder=str(d/'encoder.int8.onnx'),decoder=str(d/'decoder.int8.onnx'),joiner=str(d/'joiner.int8.onnx'),num_threads=2)
stream=r.create_stream();stream.set_option('language','nl');partials=[];started=time.monotonic()
for offset in range(0,len(audio),1600):
    stream.accept_waveform(rate,audio[offset:offset+1600])
    while r.is_ready(stream): r.decode_stream(stream)
    text=r.get_result(stream)
    if text and (not partials or text!=partials[-1]): partials.append(text)
stream.accept_waveform(rate,np.zeros(16000,np.float32));stream.input_finished()
while r.is_ready(stream): r.decode_stream(stream)
result=r.get_result(stream)
assert len(partials)>=2 and 'organisaties' in result.lower() and 'beter' in result.lower()
print('Nemotron Dutch CPU:',result,'partials',len(partials),'seconds',round(time.monotonic()-started,3),flush=True)
# New stream / language option after endpoint reset must remain valid.
r.reset(stream);stream.set_option('language','en')
print('PASS: recorded Dutch streaming, language prompting and stream reset')

# Phrase ASR depends on this exact separately downloaded Silero model.
vad_path=root/'silero_vad.onnx'
urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx',vad_path)
assert hashlib.sha256(vad_path.read_bytes()).hexdigest() == '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'
config=sherpa_onnx.VadModelConfig();config.silero_vad.model=str(vad_path)
config.silero_vad.min_silence_duration=0.3;config.silero_vad.max_speech_duration=4;config.sample_rate=16000
vad=sherpa_onnx.VoiceActivityDetector(config,buffer_size_in_seconds=10)
for offset in range(0,len(audio),512): vad.accept_waveform(audio[offset:offset+512])
vad.flush();segments=0
while not vad.empty():
    assert len(vad.front.samples)>0
    segments+=1;vad.pop()
assert segments>0
print('PASS: pinned Silero VAD executed, detected phrase segments:',segments)
