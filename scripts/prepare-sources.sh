#!/usr/bin/env bash
# Run on Linux from the workspace root. Keep vendor paths relative in Cargo's config.
set -euo pipefail
mkdir -p target/package-inputs
root=$PWD
stage=$(mktemp -d "$root/target/sources.XXXXXX")
trap 'rm -rf "$stage"' EXIT
cd "$stage"
mkdir -p .cargo notices
cargo vendor --locked --manifest-path "$root/Cargo.toml" --sync "$root/third-party/iroh-ffi/Cargo.toml" --versioned-dirs vendor > .cargo/config.toml
find vendor -type f \( -iname 'license*' -o -iname 'licence*' -o -iname 'copying*' -o -iname 'notice*' -o -iname 'copyright*' \) -exec cp --parents -t notices '{}' +
tar czf notices.tar.gz notices
tar czf rust-dependency-sources.tar.gz vendor .cargo
mv *.tar.gz "$root/target/package-inputs/"
