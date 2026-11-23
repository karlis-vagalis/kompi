# Build the Android app

On x86-64 Linux, install a JDK compatible with Gradle 9.8 (tested with JDK 27), Android SDK platform 37, Build Tools 36.0.0, NDK 30.0.16248370 and SDK CMake 4.1.2. Set `ANDROID_HOME` to the SDK directory. The native build also needs Bash, GNU coreutils, curl, flock, patch, tar, Ninja and uv. The first build downloads pinned native inputs and Maven dependencies. No private fonts or signing credentials are required.

From this directory:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked --version 3.5.4
../scripts/prepare-android.sh
app/src/main/cpp/whistle/fetch-native.sh
./gradlew :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It supports x86_64 and arm64-v8a; the optional Whistle recognizer requires arm64-v8a. To run JVM tests, keep the repository's `protocol/test-vectors/` alongside `android/` and run `./gradlew :app:testDebugUnitTest`.

See [dependency and redistribution notes](dependencies.md) before distributing an APK.
