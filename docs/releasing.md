# Releases

Source publication and binary distribution are separate decisions. **Binary releases are disabled by default.** Before enabling them, resolve redistribution rights for every packaged dependency (including Android native libraries and transitive Rust dependencies), include all required notices and corresponding source, and review the exact release diff. The host audit also found outstanding RustSec advisories: see [host dependency review](../daemon/DEPENDENCIES.md). Keep the gate closed until these findings have been triaged and mitigated. Do not set `RELEASE_REDISTRIBUTION_APPROVED=true` until both the security and redistribution reviews are complete. Clear the variable when dependencies or terms change.

## Configure GitHub before tagging

- Protect `main` with required CI and review. Set an active tag ruleset for `v*`: restrict creation to release maintainers and prohibit updates and deletion. Protect workflow files with mandatory review. Never sign a tag targeting unreviewed code.
- Create the `android-signing` environment. Restrict deployments to `v*` tags, require a reviewer other than the initiator and disable administrator bypass. If these protections aren't available, do not store the production key there.
- Back up the Android release keystore offline. Add environment **secrets** `ANDROID_KEYSTORE_BASE64` (base64 of the complete keystore), `ANDROID_KEY_ALIAS`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`. Base64 is not encryption. Never add the keystore to Git or paste it into workflow logs. Put the expected public signing certificate SHA-256 fingerprint (64 lowercase hex characters, no separators) in the environment **variable** `ANDROID_CERT_SHA256`. Keep the same signing identity for app updates.
- Grant the workflow permission to create releases. Only the final job requests `contents: write`; the signing job has no checkout and neither the Gradle nor the Rust/JS builds receive credentials.

## Cut a release

1. Update the Android `versionName` and monotonically increasing `versionCode`, crate versions and lockfiles on a reviewed main commit. Verify the merged commit passed CI and matches the intended `vMAJOR.MINOR.PATCH` tag. Check licenses and notices, then set repository variable `RELEASE_REDISTRIBUTION_APPROVED` to `true`.
2. Push the protected version tag on that commit. The release workflow checks ancestry, reruns tests, builds the unsigned APK and builds the Linux x86-64 daemon on Ubuntu 24.04. Check the workflow revision, tag and build logs before approving signing.
3. The signing job verifies the APK signature and certificate fingerprint. The publishing job attaches `kompi.apk`, `kompi-linux-x86_64.tar.gz`, `rust-dependency-sources.tar.gz` (locked crate sources) and `SHA256SUMS` to a draft, then publishes it. It won't replace an existing release. If interrupted, inspect the draft and its files before manually finishing or discarding it; never move or reuse a published tag.

## Verify and unpack

Download the assets into one directory, run `sha256sum -c SHA256SUMS`, then `apksigner verify --verbose --print-certs kompi.apk` and compare the certificate fingerprint with a separately trusted copy. Checksums detect damaged downloads; they are not independent signatures on the Linux build.

On a compatible Linux x86-64 system, unpack the archive with `tar -xzf kompi-linux-x86_64.tar.gz`. Keep `kompi` and its sibling `worker/` together. Install Bun and Pi as described in the setup guide, then run `(cd kompi-linux-x86_64/worker && bun install --production --frozen-lockfile)` before starting the daemon. The archive includes compiled worker code and its locked dependency manifest, not Bun, Pi, or `node_modules`.
