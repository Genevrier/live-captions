#!/usr/bin/env bash
# Runs a Gradle task for this project on an aarch64 host.
#
# The Android SDK build tools (aidl, aapt2, the NDK toolchain) are published for
# linux-x86_64 only, so on an ARM64 workstation they can only run under emulation.
# This wraps Gradle in an amd64 container; register the emulator once with:
#
#   docker run --privileged --rm tonistiigi/binfmt --install amd64
#
# Usage: scripts/build_in_amd64_container.sh :app:testDebugUnitTest [more gradle args]
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-$HOME/.local/toolchain/android-sdk}"
gradle_home="${GRADLE_USER_HOME:-$HOME/.local/toolchain/gradle-home}"
image="${LIVE_CAPTIONS_BUILD_IMAGE:-live-captions-build:jdk17}"

test -d "$sdk_root" || { echo "Android SDK not found at $sdk_root" >&2; exit 1; }
mkdir -p "$gradle_home"

# The sentencepiece CMake build clones abseil-cpp, so the image needs git as well as a JDK.
if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "Building $image"
    docker build --platform linux/amd64 -t "$image" - <<'DOCKERFILE'
FROM eclipse-temurin:17-jdk
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
DOCKERFILE
fi

docker run --rm --platform linux/amd64 \
  -u "$(id -u):$(id -g)" \
  -e HOME=/work/.container-home \
  -e GRADLE_USER_HOME=/gradle-home \
  -e ANDROID_HOME=/android-sdk \
  -e ANDROID_SDK_ROOT=/android-sdk \
  -v "$project_root:/work" \
  -v "$sdk_root:/android-sdk" \
  -v "$gradle_home:/gradle-home" \
  -w /work \
  "$image" \
  ./gradlew --no-daemon "$@"
