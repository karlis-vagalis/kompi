# Android dependencies and redistribution

Gradle versions are pinned in [`app/build.gradle.kts`](app/build.gradle.kts). The table lists direct versioned app and test dependencies and their published license families. Unversioned AndroidX Compose modules follow the listed BOM. These declarations are not a complete transitive notice bundle or a legal determination.

| Artifact | Version | Declared license |
|---|---|---|
| `androidx.activity:activity-compose` | 1.13.0 | The Apache Software License, Version 2.0 |
| `androidx.camera:camera-camera2` | 1.6.2 | Apache-2.0 |
| `androidx.camera:camera-lifecycle` | 1.6.2 | Apache-2.0 |
| `androidx.camera:camera-view` | 1.6.2 | Apache-2.0 |
| `androidx.compose:compose-bom` | 2026.09.00 | The Apache Software License, Version 2.0 |
| `androidx.core:core-ktx` | 1.19.1 | The Apache Software License, Version 2.0 |
| `androidx.datastore:datastore-preferences` | 1.3.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.room:room-compiler` | 2.8.4 | The Apache Software License, Version 2.0 |
| `androidx.room:room-ktx` | 2.8.4 | The Apache Software License, Version 2.0 |
| `androidx.room:room-runtime` | 2.8.4 | The Apache Software License, Version 2.0 |
| `androidx.security:security-crypto` | 1.1.0 | The Apache Software License, Version 2.0 |
| `androidx.test:rules` | 1.7.0 | The Apache Software License, Version 2.0 |
| `androidx.test:runner` | 1.8.0-alpha04 | The Apache Software License, Version 2.0 |
| `androidx.test.espresso:espresso-core` | 3.8.0-alpha04 | The Apache Software License, Version 2.0 |
| `androidx.test.ext:junit` | 1.4.0-alpha04 | The Apache Software License, Version 2.0 |
| `com.composables:composeunstyled` | 2.10.2 | MIT License |
| `com.composables:icons-lucide-android` | 2.2.1 | MIT License |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-cbor` | 2.22.4 | The Apache Software License, Version 2.0 |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.22.4 | The Apache Software License, Version 2.0 |
| `com.google.crypto.tink:tink-android` | 1.23.0 | Apache License, Version 2.0 |
| `com.google.mediapipe:tasks-audio` | 1.2.0 | The Apache Software License, Version 2.0 |
| `com.google.zxing:core` | 3.5.4 | Apache License, Version 2.0 |
| `com.google.protobuf:protobuf-java` | 4.33.6 | BSD-3-Clause |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.32.0 | MIT License |
| `computer.iroh:iroh-android` | 1.2.0 | MIT, Apache-2.0 |
| `junit:junit` | 4.13.2 | EPL-1.0 |
| `net.java.dev.jna:jna` | 5.19.1 | LGPL-2.1-or-later, Apache-2.0 |
| `org.commonmark:commonmark` | 0.32.0 | BSD-2-Clause |
| `org.commonmark:commonmark-ext-gfm-strikethrough` | 0.32.0 | BSD-2-Clause |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.32.0 | BSD-2-Clause |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 |
| `org.json:json` | 20260814 | Public Domain |
| `org.unifiedpush.android:connector` | 3.3.5 | The Apache License, Version 2.0 |

## Native libraries and assets

- The bundled Silero VAD model carries its [MIT license](app/src/main/assets/vad/LICENSE-SILERO.txt).
- WebRTC AEC3 and Abseil notices are shipped in [APK assets](app/src/main/assets/licenses/webrtc-aec3.txt). The patched source inputs are pinned in `app/src/main/cpp/aec/prepare-native.sh`.
- Whistle uses precompiled Needle from [the pinned fetch recipe](app/src/main/cpp/whistle/fetch-native.sh). The upstream library is downloaded at build time and statically linked on arm64. The [upstream Apache-2.0 license](https://huggingface.co/Cactus-Compute/needle3/blob/d6c7d6a2e9655505e0f8e23339356dd1705900eb/LICENSE) is [bundled in the APK](app/src/main/assets/licenses/needle.txt).
- The listening-ready cue is an original synthesized sine sweep, not a recording.
- Speech recognition and synthesis models downloaded by the app are separate from the APK and subject to the corresponding model cards and terms. Code uses Android's system monospace font.
- Synthetic instrumentation speech fixtures have [documented provenance](app/src/androidTest/assets/live-speech-fixtures/provenance.md).

## Notices in the APK

[`assets/licenses/runtime-index.tsv`](app/src/main/assets/licenses/runtime-index.tsv) maps notices found in resolved release AARs and JARs (including nested JARs) to their packaged copies. Identical texts are stored once. Some artifacts do not contain a notice file; absence from this index is not a license grant. Use `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` to inspect the full dependency graph.

- MediaPipe Tasks Audio and Tasks Core 1.2.0 contain the same `META-INF/NOTICE`. The APK preserves it as `licenses/mediapipe-NOTICE`.
- ONNX Runtime's license and third-party notices come from the [official 1.32.0 NuGet distribution](https://www.nuget.org/packages/Microsoft.ML.OnnxRuntime/1.32.0), which also ships an Android AAR. The 1DS license is from the Maven AAR's classes.jar. The two AARs are not byte-identical.
- Additional asset notices cover Compose Unstyled, Compose Icons and the underlying Lucide/Feather artwork, protobuf, and iroh-ffi. The iroh-ffi license covers the bindings, not every crate linked into its native libraries. The [upstream 1.2.0 source and Cargo.lock](https://github.com/n0-computer/iroh-ffi/tree/66a8d619a4d791d458d582f312df6e0ab42bc541) are separate from the host workspace.

