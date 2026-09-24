#!/usr/bin/env python3
"""Check native ABI and ELF 16 KB LOAD alignment inside the final APK."""
import struct, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    assert apk.testzip() is None, 'Corrupt APK ZIP member'
    entries = apk.namelist()
    icon_entries = sorted(n for n in entries if 'mipmap' in n or 'launcher' in n.lower())
    print('Packaged launcher resources:', ', '.join(icon_entries))
    for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'):
        for icon in ('ic_launcher.xml', 'ic_launcher_round.xml'):
            assert any(n.startswith(f'res/mipmap-{density}') and n.endswith('/' + icon) for n in entries), (density, icon)
    assert any(n.startswith('res/mipmap-anydpi-v26/') and n.endswith('/ic_launcher.xml') for n in entries)
    assert any(n.startswith('res/mipmap-anydpi-v26/') and n.endswith('/ic_launcher_round.xml') for n in entries)
    for drawable in ('ic_launcher_foreground.xml', 'ic_launcher_background.xml', 'ic_launcher_monochrome.xml'):
        assert any(n.startswith('res/drawable') and n.endswith('/' + drawable) for n in entries), drawable
    libraries = [n for n in entries if n.startswith('lib/') and n.endswith('.so')]
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

    if 'assets/qnn/libQnnHtpV79Skel.so' in apk.namelist():
        import hashlib
        expected = {
            'lib/arm64-v8a/libQnnHtp.so': '328cf737ca8942c2dde5c6f3e32a113378df2750afb9af1aafdd1775bef59875',
            'lib/arm64-v8a/libQnnSystem.so': '2d42b6bb2710155fa963ee623ce3c320f8a9896b9cfd8dcc25506affb41ab8ab',
            'lib/arm64-v8a/libQnnHtpV79Stub.so': 'ea0a4eb083789edf0cbc7ea515553cb5d4d63efabc93edb8854edd14e9629450',
            'assets/qnn/libQnnHtpV79Skel.so': '24472a899716745ac90e42f8a4fa2538a6324c06f7cb9300be75a457bc1c9fa5',
        }
        # Android packaging may strip debug symbols from host libraries; compare DSP asset verbatim.
        assert hashlib.sha256(apk.read('assets/qnn/libQnnHtpV79Skel.so')).hexdigest() == expected['assets/qnn/libQnnHtpV79Skel.so']
        for name in expected: assert name in apk.namelist(), name
        for name in ['assets/licenses/QAIRT-LICENSE.pdf', 'assets/licenses/QAIRT-QNN-NOTICE.txt']:
            assert name in apk.namelist(), name
        print('QNN SM8750 / HTP v79 libraries, skeleton integrity and notices verified')
