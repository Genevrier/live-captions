#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANDROID_NDK=${ANDROID_NDK:-${ANDROID_HOME:-}/ndk/28.2.13676358}
OUT="$ROOT/app/build/opencl"
DEPS="$OUT/deps"
BUILD="$OUT/loader-build"

if [[ ! -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found at $ANDROID_NDK" >&2
  exit 1
fi

mkdir -p "$DEPS" "$OUT/lib"
fetch() {
  local name=$1 url=$2 digest=$3 archive="$DEPS/$1.tar.gz"
  if [[ ! -f "$archive" ]] || ! echo "$digest  $archive" | sha256sum -c --status; then
    rm -f "$archive"
    curl --fail --location --retry 3 --silent --show-error "$url" -o "$archive"
  fi
  echo "$digest  $archive" | sha256sum -c -
  mkdir -p "$DEPS/$name"
  tar -xzf "$archive" --strip-components=1 -C "$DEPS/$name"
}

fetch headers \
  https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/e6060189f4ebe8b52d885c37af71b9a50c272154 \
  7425b23f33ed99fc1b39bc2f92db7db913d0a2f297e67a1e457594abd7b34ce8
fetch loader \
  https://codeload.github.com/KhronosGroup/OpenCL-ICD-Loader/tar.gz/5192c84f8059e5f703e5452929b613f9487f6e4c \
  c4ac098692a8b23827050cc10169aaae7b5d7578c4da6e2a9cd532964ef25583

WRAPPER="$OUT/CMakeLists.txt"
cat > "$WRAPPER" <<'CMAKE'
cmake_minimum_required(VERSION 3.22)
project(live_captions_opencl_loader LANGUAGES C)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OPENCL_ICD_LOADER_HEADERS_DIR "${HEADERS_DIR}" CACHE PATH "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/stage")
add_subdirectory("${LOADER_DIR}" loader)
set_target_properties(OpenCL PROPERTIES OUTPUT_NAME OpenCL_loader)
CMAKE

cmake -S "$OUT" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=29 -DANDROID_STL=c++_shared \
  -DHEADERS_DIR="$DEPS/headers" -DLOADER_DIR="$DEPS/loader"
cmake --build "$BUILD" --target OpenCL --parallel 2
install -m 644 "$BUILD/stage/libOpenCL_loader.a" "$OUT/lib/libOpenCL_loader.a"
echo "Built the static Khronos OpenCL ICD dispatch layer for arm64-v8a. The phone's vendor ICD must enumerate an Adreno 830 device at runtime."
