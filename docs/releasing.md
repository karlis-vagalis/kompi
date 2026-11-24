# Releases

Starting with v0.1.2, GitHub Releases carry two standalone signed Android APKs: `kompi.apk` for ARM64 phones (`arm64-v8a`) and `kompi-x86_64.apk` for x86-64 devices and emulators. Keep these names stable: direct downloads and Obtainium filename filters depend on them. The host bundles target x86-64 Linux GNU and Windows MSVC. Android updates require the same application ID and signing identity, and a higher `versionCode`.

## One-time configuration

- Protect `main` with required reviews and CI checks. Use active rulesets for `v*` tags: restrict creation to release maintainers, and block updates and deletion even for those maintainers.
- Restrict the `android-signing` environment to `v*` tags. The repository owner’s protected tag push authorizes the build; retain any environment approval gates.
- Generate a dedicated release keystore offline. Keep encrypted offline backups of the key and its recovery credentials, and verify recovery before the first release. Never use a debug key, commit a keystore or paste its contents into logs.
- Set the environment secrets `ANDROID_KEYSTORE_BASE64` (base64 of the entire keystore), `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD` and `ANDROID_KEY_PASSWORD`. Base64 is not encryption. Set environment variable `ANDROID_CERT_SHA256` to the public signing certificate’s lowercase SHA-256 digest (64 hex characters, no separators). Share that fingerprint with users through a separately trusted channel.

## Cut a release

1. Set the Android `versionName` to `MAJOR.MINOR.PATCH`, increment `versionCode`, and update crate versions and lockfiles. Merge to `main` and confirm CI passes.
2. As repository owner, run `git tag vMAJOR.MINOR.PATCH <tested-main-commit>` and `git push origin refs/tags/vMAJOR.MINOR.PATCH`. Do **not** publish a release manually. Prepare the release notes in a draft and confirm the [corresponding source](#corresponding-source) directions before pushing the tag.
3. Actions builds both hosts and the Android app, checks the versions and signs and verifies both APKs. It uploads both APKs, both installers, both daemon archives and `SHA256SUMS` to the draft. For `v0.1.0` it selects existing draft ID `455244515` via the Releases API. Later tags reuse a matching draft or create one. The workflow compares every uploaded asset’s GitHub digest with its local SHA-256 and verifies the tag still resolves to the tested commit before making the release public.
4. On transient failure, rerun failed jobs as owner. Matching assets are reused, mismatches replaced only in a draft. Unexpected extra assets block publication: inspect them before retrying. Do not edit the draft while the job runs, publish it manually or move an existing tag. If a source fix is required, release it under a new version.

## Verify and install

Download all assets into one directory and run `sha256sum -c SHA256SUMS`. Run `apksigner verify --verbose --print-certs` on each APK and compare each certificate fingerprint with your trusted copy before sideloading. Checksums detect damaged downloads; they are not independent signatures on the daemon.

See the [setup guide](../README.md) for installer commands and the archive fallback. Each installer downloads from its own release tag. `kompi serve` extracts the matching bundled worker and notices beside the installed executable. The installer does not supply Bun or Pi. Only v0.1.0 requires a separate worker dependency install.

## Corresponding source

Put a **Source** section next to the downloads in the release notes. Link to the matching tagged checkout (including the build scripts) and to this section at that tag for the pinned Rust dependencies. GitHub's automatically generated source links contain the Kompi tree but not the separately fetched Rust dependencies. AGPL §6(d) allows the required corresponding source to be hosted elsewhere with clear directions next to the downloads; the distributor remains responsible for keeping it accessible. Preserve the license and third-party notices in the daemon bundles and APKs separately.

From the matching tagged checkout, fetch the pinned Rust sources for the released targets:

```sh
cargo fetch --locked --target x86_64-unknown-linux-gnu --target x86_64-pc-windows-msvc
(cd third-party/iroh-ffi && cargo fetch --locked --target aarch64-linux-android --target x86_64-linux-android)
```

The root `Cargo.lock` pins the host's patched `iroh-tickets` commit. Android's separate lockfile pins a crates.io release with the same crate version, not the fork. The lockfiles record registry checksums and Git commit IDs; for direct links to published crate source, use `https://static.crates.io/crates/<name>/<name>-<version>.crate`. The pinned Cargo sources include the MPL-covered Rust crates (for example, `attohttpc` on both platforms, `option-ext` on the daemon, and `uniffi_core` on Android). If a required upstream source goes away while the binaries are still offered, keep the exact source available at another public location and update the release directions. See [Android dependencies and redistribution](../android/dependencies.md) for the APK inputs that are not fetched by Cargo.

## Build without publishing

Use `just apk` for local Android development. Use `just apk-release` to assemble the release variants without publishing; sideloading a local build over an official installation requires the official signing key.

On x86-64 GNU Linux, install dist 0.34.0, stable Rust, Python 3.11+, Bun 1.3.10 and just. Run `just daemon-dist`. The native Windows runner uses the same `python scripts/package-host.py` entry point with the Linux job’s matching `target/package-inputs/notices.tar.gz`. Each runner builds its own archive under `target/distrib/`, verifies its checksum, tests the archive and runs its generated installer against a local fixture release. Each smoke test installs twice, starts the installed daemon with disposable state, checks its status, and sends a snapshot request to its bundled worker without installing dependencies or starting a Pi session. Dist has no publishing workflow; only the release workflow uploads assets and publishes the verified draft.
