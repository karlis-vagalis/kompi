# Build the Android app

On x86-64 Linux, install a JDK compatible with Gradle 9.8 (tested with JDK 27), Android SDK platform 37, Build Tools 36.0.0, NDK 30.0.16248370 and SDK CMake 4.1.2. Set `ANDROID_HOME` to the SDK directory. The native build also needs Bash, GNU coreutils, curl, flock, patch, tar, Ninja and uv. The first build downloads pinned native inputs and Maven dependencies. No private fonts or signing credentials are required.

From this directory:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked --version 3.5.4
../scripts/prepare-android.sh
./gradlew :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It supports x86_64 and arm64-v8a. Public v0.1.0 uses the downloadable Parakeet model for offline dictation and Live. Whistle is not included; a saved Whistle selection switches to Parakeet. To run JVM tests, keep the repository's `protocol/test-vectors/` alongside `android/` and run `./gradlew :app:testDebugUnitTest`.

## Live tool narration

In **Settings → While waiting**, **Thinking title prefix** adds a lead-in to thinking titles. **Live tool-call message** is the full sentence spoken when Live uses tools, including in Android assistant sessions. A blank tool message uses the default; the thinking prefix never changes it.

Kokoro keeps one prepared tool cue in memory and can reuse its PCM after restarting the app. The app-private Android cache holds up to 16 tool cues or 8 MiB and prunes entries older than 24 hours on disk access. It does not store thinking, chat replies, or voice previews. Matching requires the complete synthesis input, voice, model and runtime configuration, and PCM format. Missing or invalid entries fall back to normal synthesis.

`LiveToolCueCacheTest` covers reuse, key changes, corruption, cancellation, and concurrent eviction. `LiveNarrationSettingsTest` and `LivePiActivityBehaviorTest` cover the preferences, the selected speech, and tool-only eligibility. `KokoroToolCuePlaybackTest` uses actual Kokoro and AudioTrack playback to check RAM and disk hits across engine instances and verifies that identical thinking and reply text bypasses the cache. It requires the pinned Kokoro voice model to be installed.

See [dependency and redistribution notes](dependencies.md) before distributing an APK.
