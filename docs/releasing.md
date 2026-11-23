# Releases

Kompi distributes its signed Android APK and native x86-64 Linux GNU and Windows MSVC daemon bundles through GitHub Releases. Android updates require the same application ID and signing identity, and a higher `versionCode`.

## One-time configuration

- Protect `main` with required reviews and CI checks. Use active rulesets for `v*` tags: restrict creation to release maintainers, and block updates and deletion even for those maintainers.
- Restrict the `android-signing` environment to `v*` tags. Require an independent reviewer, prevent self-review and disable administrator bypass.
- Generate a dedicated release keystore offline. Keep encrypted offline backups of the key and its recovery credentials, and verify recovery before the first release. Never use a debug key, commit a keystore or paste its contents into logs.
- Set the environment secrets `ANDROID_KEYSTORE_BASE64` (base64 of the entire keystore), `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD` and `ANDROID_KEY_PASSWORD`. Base64 is not encryption. Set environment variable `ANDROID_CERT_SHA256` to the public signing certificate’s lowercase SHA-256 digest (64 hex characters, no separators). Share that fingerprint with users through a separately trusted channel.

## Cut a release

1. Set the Android `versionName` to `MAJOR.MINOR.PATCH`, increment `versionCode`, and update crate versions and lockfiles. Merge to `main` and confirm CI passes.
2. Review the exact commit, including workflows, dependencies, security advisories, redistribution rights, notices and corresponding sources for both packages. Set repository Actions variable `RELEASE_REVIEWED_COMMIT` to the full approved commit SHA. An approval does not carry over to later commits.
3. Create and push `vMAJOR.MINOR.PATCH` at that SHA. The workflow checks tag protection and main ancestry, then runs CI and packages the APK and daemon. Review the tag, workflow and build before approving the signing deployment.
4. The signing job checks the APK version and verifies its signature and certificate. The publish job attaches `kompi.apk`, `kompi-daemon-x86_64-unknown-linux-gnu.tar.gz`, `kompi-daemon-x86_64-pc-windows-msvc.zip`, `rust-dependency-sources.tar.gz` and `SHA256SUMS` to a draft and publishes it. If interrupted, inspect any existing draft and verify its assets before finishing or discarding it. Never move a release tag.

## Verify and install

Download all assets into one directory and run `sha256sum -c SHA256SUMS`. Verify the APK with `apksigner verify --verbose --print-certs kompi.apk` and compare its fingerprint with your trusted copy before sideloading. Checksums detect damaged downloads; they are not independent signatures on the daemon.

See the [setup guide](../README.md) for extracting the daemon and installing its worker dependencies. The archives include the compiled worker and lockfile, not Bun, Pi or `node_modules`. For redistribution, keep the notices, matching tagged source and `rust-dependency-sources.tar.gz` (including the locked Git dependencies).

## Build without publishing

On x86-64 GNU Linux, install dist 0.34.0, stable Rust, Python 3.11+, Bun 1.3.10 and just. Run `just daemon-dist`. The native Windows runner uses the same `python scripts/package-host.py` entry point with the Linux job’s matching `target/package-inputs/notices.tar.gz`. Each runner builds its own archive under `target/distrib/`, verifies its checksum, extracts it, runs the packaged executable and installs and starts the packaged worker. Dist has no publishing workflow; only the reviewed tag workflow can create the release.
