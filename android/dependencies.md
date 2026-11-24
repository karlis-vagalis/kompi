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
- Live uses Android’s session-bound acoustic echo cancellation and noise suppression.
- Public v0.1.0 does not fetch or link Whistle/Needle. The optional integration source is retained, but corresponding source for the pinned Needle archive has not been verified.
- The listening-ready cue is an original synthesized sine sweep, not a recording.
- Speech recognition and synthesis models downloaded by the app are separate from the APK and subject to the corresponding model cards and terms. Code uses Android's system monospace font.
- Synthetic instrumentation speech fixtures have [documented provenance](app/src/androidTest/assets/live-speech-fixtures/provenance.md).

## Notices in the APK

[`assets/licenses/runtime-index.tsv`](app/src/main/assets/licenses/runtime-index.tsv) maps notices found in resolved release AARs and JARs (including nested JARs) to their packaged copies. Identical texts are stored once. For artifacts without embedded notices, `jvm-source-notices.txt` preserves distinct license headers and notices from the matching published source JARs, with their download URLs. Additional upstream license texts cover Ripple Indication, Checker Framework annotations and Kotlin coroutines. The empty Guava `listenablefuture` conflict-avoidance artifact contains no classes. Use `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` to inspect the full dependency graph.

- Google Maven's [`com.google.mediapipe:tasks-audio:1.2.0`](https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-audio/1.2.0/tasks-audio-1.2.0.pom) and its transitive [`com.google.mediapipe:tasks-core:1.2.0`](https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/1.2.0/tasks-core-1.2.0.pom) supply the two MediaPipe JNI libraries. Both POMs declare Apache-2.0, which requires preserving applicable license and notice material but does not itself require a source download. The APK preserves their identical `META-INF/NOTICE` as `licenses/mediapipe-NOTICE` with line endings normalized. The POMs don't identify a source revision, and the upstream Git tag `v1.2.0` is not a verified source match for these Android binaries. The NOTICE separately identifies Eigen 3.3.90 (primarily MPL-2.0) and a Mozilla-derived `googleurl` file (MPL-1.1). These components have their own terms; the NOTICE's upstream repository links are not pinned to the revisions used by the AAR build.
- The ONNX Runtime Android AAR, license and third-party notices all come from [Microsoft.ML.OnnxRuntime 1.32.0](https://www.nuget.org/packages/Microsoft.ML.OnnxRuntime/1.32.0). [The preparation script](../scripts/prepare-android.sh) verifies both package and AAR SHA-256 values. The NuGet manifest identifies source commit [fab30a46491a40f5e4c968b80e8a59acbbb91b53](https://github.com/raymondxyang/onnxruntime/tree/fab30a46491a40f5e4c968b80e8a59acbbb91b53).
- Additional notices cover Compose Unstyled, Compose Icons and the Lucide/Feather artwork, protobuf, and iroh-ffi. Kompi builds its Android iroh JNI libraries and Kotlin bindings [from source](../third-party/iroh-ffi/KOMPI.md); neither the upstream Android binary nor the desktop binaries from its JVM package are included. `assets/licenses/iroh-dependencies.zip` contains a package index and notices from the locked Cargo sources.
