#!/usr/bin/env bash
# Run on Linux from the workspace root. Keep vendor paths relative in Cargo's config.
set -euo pipefail
archive=true
if [[ ${1:-} == --notices-only && $# == 1 ]]; then
  archive=false
elif (( $# )); then
  echo 'Usage: prepare-sources.sh [--notices-only]' >&2
  exit 2
fi
mkdir -p target/package-inputs
root=$PWD
stage=$(mktemp -d "$root/target/sources.XXXXXX")
trap 'rm -rf "$stage"' EXIT
cd "$stage"
mkdir -p .cargo notices
mkdir -p android/.cargo
(cd android && cargo vendor --locked --manifest-path "$root/third-party/iroh-ffi/Cargo.toml" --versioned-dirs vendor > .cargo/config.toml)
cargo vendor --locked --manifest-path "$root/Cargo.toml" --versioned-dirs vendor > .cargo/config.toml
find vendor android/vendor -type f \( -iname 'license*' -o -iname 'licence*' -o -iname 'copying*' -o -iname 'notice*' -o -iname 'copyright*' \) -exec cp --parents -t notices '{}' +
tar czf notices.tar.gz notices
if [[ "$archive" == true ]]; then
  tar czf rust-dependency-sources.tar.gz vendor .cargo android
fi
mv -- ./*.tar.gz "$root/target/package-inputs/"
