#!/usr/bin/env bash
# Build and stage the runtime dependencies that are not resolved by Gradle.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/android/.native-build"
mkdir -p "$out/assets/licenses"
tmp=$(mktemp -d "$out/.stage.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
url=https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime/1.32.0/microsoft.ml.onnxruntime.1.32.0.nupkg
if ! printf '%s  %s\n' 23e074abd1d05d366ce01d80181c9cb9def3b626665fc88fe7fbff21ba2ce16e "$out/onnxruntime.aar" | sha256sum -c --status 2>/dev/null; then
  curl -fsSL --retry 3 "$url" -o ort.nupkg
  echo 'a9150bc2992cbbb6722070da9910e1ffa989a8bfc67bdd5bb8e745ca755641db  ort.nupkg' | sha256sum -c -
  unzip -p ort.nupkg runtimes/android/native/onnxruntime.aar > onnxruntime.aar
  echo '23e074abd1d05d366ce01d80181c9cb9def3b626665fc88fe7fbff21ba2ce16e  onnxruntime.aar' | sha256sum -c -
  mv onnxruntime.aar "$out/"
fi
cd "$root/third-party/iroh-ffi"
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_HOME}/ndk/30.0.16248370}
cargo ndk --platform 26 --output-dir "$tmp/jni" --target arm64-v8a --target x86_64 build --release --lib --locked
cargo metadata --locked --format-version 1 > "$tmp/metadata.json"
python3 "$root/scripts/android-rust-notices.py" "$tmp/metadata.json" "$tmp/iroh-dependencies.zip"
for abi in arm64-v8a x86_64; do
  mkdir -p "$out/jni/$abi"
  mv "$tmp/jni/$abi/libiroh_ffi.so" "$out/jni/$abi/"
done
mv "$tmp/iroh-dependencies.zip" "$out/assets/licenses/"
