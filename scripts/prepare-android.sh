#!/usr/bin/env bash
# Build and stage the runtime dependencies that are not resolved by Gradle.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/android/.native-build"
abis=(arm64-v8a x86_64)
platform=26
ort_sha=23e074abd1d05d366ce01d80181c9cb9def3b626665fc88fe7fbff21ba2ce16e
outputs=(onnxruntime.aar assets/licenses/iroh-dependencies.zip)
for abi in "${abis[@]}"; do outputs+=("jni/$abi/libiroh_ffi.so"); done
manifest() (
  cd "$out"
  for file in "${outputs[@]}"; do
    [[ -f "$file" && ! -L "$file" && -s "$file" ]] || { echo "Invalid staged input: $file" >&2; return 1; }
  done
  sha256sum "${outputs[@]}"
)
verify_ort() {
  printf '%s  %s\n' "$ort_sha" "$out/onnxruntime.aar" | sha256sum -c --status
}
if [[ ${1:-} == --verify && $# == 1 ]]; then
  verify_ort
  expected=$(manifest)
  [[ -f "$out/sha256sums" && ! -L "$out/sha256sums" && $(<"$out/sha256sums") == "$expected" ]]
  exit
fi
if (( $# )); then echo 'Usage: prepare-android.sh [--verify]' >&2; exit 2; fi
mkdir -p "$out/assets/licenses"
tmp=$(mktemp -d "$out/.stage.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
url=https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime/1.32.0/microsoft.ml.onnxruntime.1.32.0.nupkg
if ! verify_ort 2>/dev/null; then
  curl -fsSL --retry 3 "$url" -o ort.nupkg
  echo 'a9150bc2992cbbb6722070da9910e1ffa989a8bfc67bdd5bb8e745ca755641db  ort.nupkg' | sha256sum -c -
  unzip -p ort.nupkg runtimes/android/native/onnxruntime.aar > onnxruntime.aar
  echo "$ort_sha  onnxruntime.aar" | sha256sum -c -
  mv onnxruntime.aar "$out/"
fi
cd "$root/third-party/iroh-ffi"
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_HOME}/ndk/30.0.16248370}
targets=()
for abi in "${abis[@]}"; do targets+=(--target "$abi"); done
cargo ndk --platform "$platform" --output-dir "$tmp/jni" "${targets[@]}" build --release --lib --locked
cargo metadata --locked --format-version 1 > "$tmp/metadata.json"
python3 "$root/scripts/android-rust-notices.py" "$tmp/metadata.json" "$tmp/iroh-dependencies.zip"
for abi in "${abis[@]}"; do
  mkdir -p "$out/jni/$abi"
  mv "$tmp/jni/$abi/libiroh_ffi.so" "$out/jni/$abi/"
done
mv "$tmp/iroh-dependencies.zip" "$out/assets/licenses/"
manifest > "$tmp/sha256sums"
mv "$tmp/sha256sums" "$out/"
