#!/usr/bin/env bash
# Rebuild only sherpa JNI with QNN support; preserve the verified Kotlin API and ORT.
set -euo pipefail
project=$(pwd)
work="$project/build/qnn-runtime"
mkdir -p "$work"
fetch() {
  local url="$1" path="$2" digest="$3"
  if ! test -f "$path"; then curl -fL --retry 3 "$url" -o "$path.part"; mv "$path.part" "$path"; fi
  echo "$digest  $path" | sha256sum -c -
}
fetch https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v1.13.8 "$work/sherpa.tar.gz" b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-include-2.40.0.251030.tar.bz2 "$work/headers.tar.bz2" fafe1c9dc3f60393134f22f2c708a9ede287492fa20dedc22c9fabdcf5ebe70d
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-libs-2.40.0.251030.tar.bz2 "$work/libs.tar.bz2" 1078c0833b1e948518ff433e081eda511475c7fd9a82c7798a862f59b95a7ce5
mkdir -p "$work/source"
tar xf "$work/sherpa.tar.gz" -C "$work/source" --strip-components=1
tar xf "$work/headers.tar.bz2" -C "$work"
tar xf "$work/libs.tar.bz2" -C "$work"
export QNN_SDK_ROOT="$work/qnn-include-2.40.0.251030"
export SHERPA_ONNXRUNTIME_LIB_DIR="$project/app/build/ort/jni/arm64-v8a"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$project/app/src/main/cpp/ort"
cmake -S "$work/source" -B "$work/compiled" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_QNN=ON -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_CHECK=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_C_API=OFF \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
cmake --build "$work/compiled" --target sherpa-onnx-jni -j2
python3 scripts/package_qnn.py "$work"
