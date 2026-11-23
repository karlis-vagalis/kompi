# Releases

Kompi distributes its signed Android APK and native x86-64 Linux GNU and Windows MSVC daemon bundles through GitHub Releases. Android updates require the same application ID and signing identity, and a higher `versionCode`.

## One-time configuration

- Protect `main` with required reviews and CI checks. Use active rulesets for `v*` tags: restrict creation to release maintainers, and block updates and deletion even for those maintainers.
- Restrict the `android-signing` environment to `v*` tags. Do not configure required reviewers: publishing the release authorizes signing.
- Generate a dedicated release keystore offline. Keep encrypted offline backups of the key and its recovery credentials, and verify recovery before the first release. Never use a debug key, commit a keystore or paste its contents into logs.
- Set the environment secrets `ANDROID_KEYSTORE_BASE64` (base64 of the entire keystore), `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD` and `ANDROID_KEY_PASSWORD`. Base64 is not encryption. Set environment variable `ANDROID_CERT_SHA256` to the public signing certificate’s lowercase SHA-256 digest (64 hex characters, no separators). Share that fingerprint with users through a separately trusted channel.

## Cut a release

1. Set the Android `versionName` to `MAJOR.MINOR.PATCH`, increment `versionCode`, and update crate versions and lockfiles. Merge to `main` and confirm CI passes.
2. In GitHub Releases, publish `vMAJOR.MINOR.PATCH` at the tested commit on `main` using the repository owner account.
3. Actions runs CI, signs and verifies the APK, then uploads it, the shell and PowerShell installers, both daemon bundles, dependency sources and `SHA256SUMS` to that release. If a job fails, fix the issue before distributing the release. Never move a release tag.

## Verify and install

Download all assets into one directory and run `sha256sum -c SHA256SUMS`. Verify the APK with `apksigner verify --verbose --print-certs kompi.apk` and compare its fingerprint with your trusted copy before sideloading. Checksums detect damaged downloads; they are not independent signatures on the daemon.

See the [setup guide](../README.md) for installer commands and the archive fallback. Each installer downloads from its own release tag. `kompi setup-worker` prepares the matching worker and notices beside the installed executable and prints the worker directory. Install its locked dependencies there after each upgrade. Neither installer supplies Bun, Pi or `node_modules`. For redistribution, keep the notices, matching tagged source and `rust-dependency-sources.tar.gz` (including the locked Git dependencies).

## Build without publishing

On x86-64 GNU Linux, install dist 0.34.0, stable Rust, Python 3.11+, Bun 1.3.10 and just. Run `just daemon-dist`. The native Windows runner uses the same `python scripts/package-host.py` entry point with the Linux job’s matching `target/package-inputs/notices.tar.gz`. Each runner builds its own archive under `target/distrib/`, verifies its checksum, tests the archive and runs its generated installer against a local fixture release. The native smoke tests install the worker dependencies and send a snapshot request to the installed worker. Dist has no publishing workflow; only the release workflow uploads assets to the release you published.
