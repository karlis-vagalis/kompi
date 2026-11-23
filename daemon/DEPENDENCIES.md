# Host dependency review

The Rust workspace and both Bun packages lock public registry dependencies. Kompi's license does not replace third-party licenses. The Linux x86-64 Cargo metadata includes permissive licenses and MPL-2.0 (`attohttpc`, `ece`, `option-ext`); preserve upstream notices and offer corresponding source when distributing binaries. Review licenses for each actual target and release, including vendored C/assembly in native crates. `cargo metadata --locked --format-version 1 --filter-platform x86_64-unknown-linux-gnu` reports package versions and declared licenses. Registry metadata alone is not a binary notice bundle.

The worker uses the MIT-licensed Pi packages and TypeBox, and the extension also uses cbor-x and Zod. Install locked dependencies rather than republishing an undocumented `node_modules` tree. The bundled Pi package contains example manifests covered by the parent package; they are not separate Kompi dependencies. Bun overrides for `undici` and `minimatch` address advisories in upstream pins. Run `bun audit` separately in `daemon/` and `pi-extension/`.

## Unresolved RustSec findings

Do not approve a binary release until the remaining findings have been reviewed for the actual target and either remediated or explicitly accepted. At review time `cargo audit` reported:

- [RUSTSEC-2026-0387](https://rustsec.org/advisories/RUSTSEC-2026-0387.html) and [RUSTSEC-2023-0071](https://rustsec.org/advisories/RUSTSEC-2023-0071.html): transitive cryptography in the Web Push dependency graph. Presence does not establish that an affected primitive is used at runtime.
- [RUSTSEC-2026-0459](https://rustsec.org/advisories/RUSTSEC-2026-0459.html): the pinned SQLx dependency bundles affected SQLite. Do not import databases and WAL files from untrusted sources; keep the daemon state directory private.
- Additional warnings flag unmaintained crates and unsoundness in `heapless`. Check `cargo audit` and `cargo tree -i <crate>` against the current lockfile before release.

These are unresolved findings, not waived checks. Neither a successful build nor a passing test suite establishes security.
