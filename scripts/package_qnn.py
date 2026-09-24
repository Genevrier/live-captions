#!/usr/bin/env python3
import pathlib, sys, zipfile, hashlib
root = pathlib.Path(__file__).resolve().parents[1]
work = pathlib.Path(sys.argv[1])
libraries = ['libQnnHtp.so', 'libQnnSystem.so', 'libQnnHtpV79Stub.so']
# Only the matching HTP v79 stub/skeleton is shipped for SM8750.
replacements = {'jni/arm64-v8a/libsherpa-onnx-jni.so': next((work/'compiled').rglob('libsherpa-onnx-jni.so'))}
for name in libraries: replacements['jni/arm64-v8a/'+name] = work/'qnn-libs-2.40.0.251030'/name
replacements['assets/qnn/libQnnHtpV79Skel.so'] = work/'qnn-libs-2.40.0.251030/libQnnHtpV79Skel.so'
# Preserve third-party notices from the native dependency sources used in this build.
for directory in sorted((work/'compiled/_deps').glob('*-src')):
    for pattern in ['LICENSE*', 'COPYING*', 'NOTICE*']:
        for notice in directory.glob(pattern):
            if notice.is_file(): replacements['assets/licenses/native-'+directory.name+'/'+notice.name] = notice
with zipfile.ZipFile(root/'app/libs/sherpa-onnx-1.13.8.aar') as source, zipfile.ZipFile(root/'app/libs/sherpa-onnx-qnn-1.13.8.aar','w',zipfile.ZIP_DEFLATED) as out:
    for name in source.namelist():
        if name not in replacements: out.writestr(name,source.read(name))
    for name,path in replacements.items():
        out.write(path,name)
        print(name, hashlib.sha256(path.read_bytes()).hexdigest())
