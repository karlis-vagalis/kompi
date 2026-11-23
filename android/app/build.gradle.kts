plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kompi.app"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.kompi.app"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "PI_FRAME_COALESCING", providers.gradleProperty("piFrameCoalescing").orElse("false").map { it.toBooleanStrict().toString() }.get())
        buildConfigField("boolean", "PI_EAGER_FIRST_PARSE", providers.gradleProperty("piEagerFirstParse").orElse("true").map { it.toBooleanStrict().toString() }.get())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Verified native inputs are already stripped; keep their exact bytes in the APK.
    // Also exclude stale CMake output from developer worktrees.
    packaging.jniLibs.excludes += "**/libkompi_whistle.so"
    packaging.jniLibs.keepDebugSymbols += setOf("**/libiroh_ffi.so", "**/libonnxruntime.so", "**/libonnxruntime4j_jni.so")
    sourceSets.getByName("main") {
        java.srcDir("../../third-party/iroh-ffi/kotlin")
        jniLibs.srcDir("../.native-build/jni")
        assets.srcDir("../.native-build/assets")
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "CHAT_STREAMS", providers.gradleProperty("chatStreams").orElse("false").get().toBooleanStrict().toString())
        }
        release {
            buildConfigField("boolean", "CHAT_STREAMS", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}


tasks.named("preBuild") {
    doFirst {
        val nativeDir = rootProject.file(".native-build")
        for (path in listOf("onnxruntime.aar", "assets/licenses/iroh-dependencies.zip",
            "jni/arm64-v8a/libiroh_ffi.so", "jni/x86_64/libiroh_ffi.so")) {
            check(nativeDir.resolve(path).length() > 0) { "Missing $path. Run scripts/prepare-android.sh first." }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.composables:composeunstyled:2.10.2")
    implementation("com.composables:icons-lucide-android:2.2.1")
    // CommonMark AST with maintained GFM strikethrough and tables extensions (BSD-2-Clause).
    implementation("org.commonmark:commonmark:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.32.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.core:core-ktx:1.19.1")
    // iroh Kotlin bindings are compiled from the same source revision as the JNI library.
    // Official UnifiedPush connector release; provides chooser/registration, endpoint keys and RFC8291 receive/decrypt.
    // Its Tink 1.23 core overlaps every class in AndroidX Security's transitive tink-android.
    // Use the matching Android artifact, which contains the 1.23 core plus Android Keystore APIs.
    implementation("org.unifiedpush.android:connector:3.3.5") {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.google.protobuf:protobuf-java:4.33.6")
    // The AAR and its notices are extracted from the same checksum-verified NuGet package.
    implementation(files("../.native-build/onnxruntime.aar"))
    // MediaPipe Tasks Audio provides Parakeet AudioTranscriber and Kokoro TextToSpeech.
    // Its lite protobuf runtime duplicates classes in Tink's full protobuf runtime; the newer
    // full runtime is a compatible superset for MediaPipe's lite-generated messages.
    implementation("com.google.mediapipe:tasks-audio:1.2.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.22.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.4")
    // Pin the Android AAR to replace iroh's older transitive JNA native payload.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // API 37 removed InputManager.getInstance used by Espresso 3.7's input synchronization.
    // Keep the instrumentation stack aligned with the preview emulator; test-only dependencies.
    androidTestImplementation("androidx.test.ext:junit:1.4.0-alpha04")
    androidTestImplementation("androidx.test:runner:1.8.0-alpha04")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha04")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
