#!/usr/bin/env python3
"""Check native ABI and ELF 16 KB LOAD alignment inside the final APK."""
import struct, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    assert apk.testzip() is None, 'Corrupt APK ZIP member'
    libraries = [n for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert libraries, 'No native libraries'
    for name in libraries:
        assert name.startswith('lib/arm64-v8a/'), name
        data = apk.read(name)
        assert data[:6] == b'\x7fELF\x02\x01', name
        assert struct.unpack_from('<H', data, 18)[0] == 183, name
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            kind, _, file_offset, virtual, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + i * size)
            if kind == 1:
                assert alignment >= 16384 and file_offset % 16384 == virtual % 16384, (name, alignment)
        print('arm64 / 16 KB ELF:', name)
    for required in ['libsherpa-onnx-jni.so', 'libonnxruntime.so', 'liblive-translator.so']:
        assert 'lib/arm64-v8a/' + required in libraries, required
