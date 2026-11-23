# Show common development commands without building or installing anything.
default:
    @just --list

# Build the Android debug APK.
apk:
    cd android && ./gradlew --no-daemon :app:assembleDebug

# Build the release APK locally (not a publication step).
apk-release:
    cd android && ./gradlew --no-daemon :app:assembleRelease

# List connected Android devices and emulators.
devices:
    adb devices -l

# Install the existing debug APK on the explicitly selected device.
install serial:
    adb -s {{quote(serial)}} install -r android/app/build/outputs/apk/debug/app-debug.apk

# Launch Kompi on the explicitly selected device.
launch serial:
    adb -s {{quote(serial)}} shell am start -n com.kompi.app/.MainActivity

# Build the native daemon in release mode.
daemon-build:
    cargo build --locked --release -p kompi-daemon

# Build the Pi CLI worker with locked dependencies.
sdk-build:
    cd daemon && bun install --frozen-lockfile && bun run build

# Build and smoke-test the Linux bundle, including fresh dependency notices/sources.
daemon-dist:
    bash scripts/prepare-sources.sh
    python3 scripts/package-host.py

# Regenerate protocol schemas and clients after changing the wire contract.
protocol-generate:
    cd protocol && bun run generate

# Typecheck the Pi extension.
extension-typecheck:
    cd pi-extension && bun install --frozen-lockfile && bun run typecheck

# Run Rust workspace tests.
rust-test:
    cargo test --locked --workspace
