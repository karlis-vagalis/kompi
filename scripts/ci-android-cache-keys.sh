#!/usr/bin/env bash
# Run after SDK and Rust setup on the hosted Android builder.
set -euo pipefail

# Hash tracked paths AND file modes in the vendored workspaces, including Cargo
# configs, locks, patch sources, build scripts, and license/notice files.
: "${GRADLE_CONFIG:?}" "${NATIVE_CONFIG:?}" "${ANDROID_NDK_HOME:?}"
ndk_identity=$(sha256sum "$ANDROID_NDK_HOME/source.properties" | cut -d ' ' -f1)
clang_identity=$("$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version)
rust_identity=$(rustc -vV)
cargo_identity=$(cargo -V)
python_identity=$(python3 -V)
java_identity=$(java -version 2>&1)
third_party=$(git rev-parse HEAD:third-party)

hash() { printf '%s\0' "$@" | sha256sum | cut -d ' ' -f1; }
# Gradle also fingerprints each task's inputs; never restore project build/,
# config caches, generated transforms, credentials, or the Gradle user home.
gradle=$(hash "$RUNNER_OS/$RUNNER_ARCH" "$GRADLE_CONFIG" "$java_identity" "$ndk_identity" \
  "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" "$ANDROID_CMAKE_VERSION" "$UV_VERSION")
# prepare-android.sh supplies the exact ABI/API/profile and ONNX checksums;
# hashing the script means those values cannot drift independently of the key.
native=$(hash "$RUNNER_OS/$RUNNER_ARCH/${ImageOS:-}/${ImageVersion:-}" "$NATIVE_CONFIG" "$third_party" \
  "$ndk_identity" "$clang_identity" "$rust_identity" "$cargo_identity" "$python_identity" "$CARGO_NDK_VERSION")
printf 'gradle=%s\nnative=%s\nweek=%s\n' "$gradle" "$native" "$(date -u +%G-W%V)" >> "$GITHUB_OUTPUT"
