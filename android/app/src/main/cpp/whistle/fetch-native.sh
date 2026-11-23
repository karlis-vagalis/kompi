#!/usr/bin/env bash
set -euo pipefail

# Cactus-Compute/needle3 commit verified 2026-10-30; only arm64-v8a is published.
readonly base='https://huggingface.co/Cactus-Compute/needle3/resolve/d6c7d6a2e9655505e0f8e23339356dd1705900eb/android-arm64'
readonly out="$(cd "$(dirname "$0")" && pwd)/prebuilt/android-arm64"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl --fail --location --silent --show-error --max-time 20 "$base/libneedle.a" -o "$tmp/libneedle.a"
printf '%s  %s\n' '7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0' "$tmp/libneedle.a" | sha256sum --check --status || {
  echo 'libneedle.a checksum mismatch' >&2; exit 1;
}
curl --fail --location --silent --show-error --max-time 20 "$base/needle.h" -o "$tmp/needle.h"
printf '%s  %s\n' 'a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99' "$tmp/needle.h" | sha256sum --check --status || {
  echo 'needle.h checksum mismatch' >&2; exit 1;
}
install -m 0644 "$tmp/libneedle.a" "$out/libneedle.a"
install -m 0644 "$tmp/needle.h" "$out/needle.h"
echo "Installed checksum-verified Android arm64-v8a Needle artifacts at $out"
