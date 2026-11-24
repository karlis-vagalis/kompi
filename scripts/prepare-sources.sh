#!/usr/bin/env bash
# Collect dependency notices on Linux from the workspace root.
set -euo pipefail
if (( $# )); then
  echo 'Usage: prepare-sources.sh' >&2
  exit 2
fi
mkdir -p target/package-inputs
root=$PWD
stage=$(mktemp -d "$root/target/sources.XXXXXX")
trap 'rm -rf "$stage"' EXIT
cd "$stage"
# Keep the lockfiles separate: they select different sources for iroh-tickets 1.0.0.
mkdir -p notices android
(cd android && cargo vendor --locked --manifest-path "$root/third-party/iroh-ffi/Cargo.toml" --versioned-dirs vendor >/dev/null)
cargo vendor --locked --manifest-path "$root/Cargo.toml" --versioned-dirs vendor >/dev/null
find vendor android/vendor -type f \( -iname 'license*' -o -iname 'licence*' -o -iname 'copying*' -o -iname 'notice*' -o -iname 'copyright*' \) -exec cp --parents -t notices '{}' +
tar czf notices.tar.gz notices
mv -- notices.tar.gz "$root/target/package-inputs/"
