#!/usr/bin/env python3
"""Download pinned public test models and execute the same C++ core used by Android."""
import argparse, hashlib, json, os, pathlib, subprocess, urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--binary', required=True)
p.add_argument('--cache', default=str(root / 'build/model-smoke'))
a = p.parse_args()
cache = pathlib.Path(a.cache); cache.mkdir(parents=True, exist_ok=True)
lock = json.loads((root / 'app/src/main/assets/translation-models.json').read_text())
for bundle in lock['bundles']:
    if bundle['id'] not in ('hymt2-Q8_0', 'opus-nl-en'): continue
    directory = cache / bundle['id']; directory.mkdir(parents=True, exist_ok=True)
    for asset in bundle['files']:
        path = directory / asset['path']
        def valid(f):
            if not f.is_file() or f.stat().st_size != asset['size']: return False
            with f.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest() == asset['sha256']
        if not valid(path):
            part = path.with_suffix(path.suffix + '.part')
            urllib.request.urlretrieve(asset['url'], part)
            if not valid(part): raise RuntimeError('Model checksum mismatch: ' + str(part))
            part.replace(path)
        print('Verified', bundle['id'], asset['path'], flush=True)
subprocess.run([a.binary, str(cache / 'hymt2-Q8_0/model.gguf'), str(cache / 'opus-nl-en')], check=True)
