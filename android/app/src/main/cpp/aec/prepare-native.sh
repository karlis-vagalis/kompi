#!/usr/bin/env bash
set -euo pipefail

# AEC3, NOT WebRTC's legacy mobile AECM. Sources and the Abseil Meson overlay are pinned
# independently. No distro/host Abseil, generated RTC bundle or runtime network dependency.
readonly root="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
readonly cache="$root/target/live-aec-native"
# The recipe contains every source/overlay/tool/API/flag pin. A future change gets BOTH a fresh
# extracted source tree and a fresh build directory, never an old matching-ABI build.ninja.
readonly recipe_code_hash="$(sha256sum "${BASH_SOURCE[0]}" | cut -d ' ' -f1)"
readonly overlay="$(dirname "${BASH_SOURCE[0]}")/capture-suspend.patch"
readonly overlay_hash="$(sha256sum "$overlay" | cut -d ' ' -f1)"
readonly recipe_hash="$(printf '%s\n' "$recipe_code_hash" "$overlay_hash" | sha256sum | cut -d ' ' -f1)"
readonly ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
readonly abi="${1:?Usage: bash prepare-native.sh <arm64-v8a|x86_64>}"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android; cpu_family=aarch64; cpu=armv8-a ;;
  x86_64) triple=x86_64-linux-android; cpu_family=x86_64; cpu=x86_64 ;;
  *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
esac
readonly toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$toolchain/${triple}26-clang++" || { echo "Missing pinned Android NDK: $ndk" >&2; exit 1; }
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*30\.0\.16248370[[:space:]]*$' "$ndk/source.properties" || {
  echo "Kompi AEC3 requires Android NDK 30.0.16248370" >&2; exit 1;
}
readonly ndk_hash="$(sha256sum "$ndk/source.properties" | cut -d ' ' -f1)"
readonly identity="$(printf '%s\n' "$recipe_hash" "$ndk_hash" "$abi" | sha256sum | cut -d ' ' -f1)"
# Do not let ambient host compiler/include/linker overrides change a supposedly identical artifact.
unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS CC CXX AR LD CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH LIBRARY_PATH
export SOURCE_DATE_EPOCH=0
command -v ninja >/dev/null
command -v uv >/dev/null
mkdir -p "$cache/downloads"
# Share verified source extraction safely between ABI invocations; builds remain independently keyed.
exec 9>"$cache/prepare.lock"
flock 9
fetch() {
  local name="$1" hash="$2" url="$3" destination="$cache/downloads/$1"
  if ! printf '%s  %s\n' "$hash" "$destination" | sha256sum --check --status 2>/dev/null; then
    curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error --max-time 120 "$url" -o "$destination.pending"
    printf '%s  %s\n' "$hash" "$destination.pending" | sha256sum --check --status
    mv "$destination.pending" "$destination"
  fi
}
fetch webrtc-audio-processing-1.3.tar.xz 2365e93e778d7b61b5d6e02d21c47d97222e9c7deff9e1d0838ad6ec2e86f1b9 \
  https://www.freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-1.3.tar.xz
fetch abseil-cpp-20230125.1.tar.gz 81311c17599b3712069ded20cca09a62ab0bf2a89dfa16993786c8782b7ed145 \
  https://github.com/abseil/abseil-cpp/archive/20230125.1.tar.gz
fetch abseil-cpp_20230125.1-4_patch.zip 112ee72052049d930396c2778fc1c6e184137905dd75d60a97dcfc386426610d \
  https://wrapdb.mesonbuild.com/v2/abseil-cpp_20230125.1-4/get_patch
readonly source_root="$cache/source-$recipe_hash"
readonly source="$source_root/webrtc-audio-processing-1.3"
if ! test -f "$source/.kompi-capture-suspend-overlay"; then
  mkdir -p "$source_root"
  rm -rf "$source" # Only this identity-keyed generated dependency, never application/user state.
  tar -xJf "$cache/downloads/webrtc-audio-processing-1.3.tar.xz" -C "$source_root"
  patch --directory="$source" --strip=1 --fuzz=0 < "$overlay"
  printf '%s\n' "$overlay_hash" > "$source/.kompi-capture-suspend-overlay"
fi
test "$(head -n 1 "$source/.kompi-capture-suspend-overlay")" = "$overlay_hash"
mkdir -p "$source/subprojects/packagecache" "$cache/empty-pkgconfig"
cp "$cache/downloads/abseil-cpp-20230125.1.tar.gz" "$cache/downloads/abseil-cpp_20230125.1-4_patch.zip" "$source/subprojects/packagecache/"
readonly cross="$cache/$abi-$identity.cross"
printf '%s\n' \
  '[binaries]' \
  "c = '$toolchain/${triple}26-clang'" \
  "cpp = '$toolchain/${triple}26-clang++'" \
  "ar = '$toolchain/llvm-ar'" \
  "strip = '$toolchain/llvm-strip'" \
  "pkg-config = 'pkg-config'" \
  '[host_machine]' \
  "system = 'android'" "cpu_family = '$cpu_family'" "cpu = '$cpu'" "endian = 'little'" \
  '[properties]' 'needs_exe_wrapper = true' > "$cross"
export PKG_CONFIG_LIBDIR="$cache/empty-pkgconfig"
export PKG_CONFIG_PATH=''
readonly build="$cache/build-$abi-$identity"
# Meson is a host BUILD TOOL only. Runtime sources above have exact version/content pins.
meson() { uv tool run --from meson==1.12.1 meson "$@"; }
if ! test -f "$build/build.ninja"; then
  meson setup "$build" "$source" --cross-file "$cross" --wrap-mode=nodownload \
    --buildtype=release --default-library=static -Dgnustl=disabled -Dcpp_std=c++17 -Db_staticpic=true
fi
# Build only APM and its actual dependencies, not the unused audio-coding package or any tests.
ninja -C "$build" -j "${KOMPI_NATIVE_JOBS:-2}" webrtc/modules/audio_processing/libwebrtc-audio-processing-1.a
printf 'recipe=%s\noverlay=%s\nndk=%s\nabi=%s\nidentity=%s\n' "$recipe_hash" "$overlay_hash" "$ndk_hash" "$abi" "$identity" > "$build/kompi-inputs.txt"
printf '\nPrepared pinned AEC3 for %s in %s\n' "$abi" "$build"
