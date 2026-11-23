cmake_minimum_required(VERSION 3.22.1)
project(kompi_whistle LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "Whistle JNI is an Android-only target")
endif()

# Needle currently publishes Android arm64-v8a, armv7, and riscv64 builds, but no Android
# x86_64 library. Keep the Whistle JNI runtime arm64-only; other APK ABIs (notably the emulator)
# still build for Iroh and MediaPipe/Kokoro. WhistleBackend checks the runtime ABI before loading.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "Whistle JNI disabled for ${ANDROID_ABI}; no verified Android Needle artifact")
    return()
endif()

set(NEEDLE_ROOT "${CMAKE_CURRENT_LIST_DIR}/prebuilt/android-arm64")
if(NOT EXISTS "${NEEDLE_ROOT}/libneedle.a" OR NOT EXISTS "${NEEDLE_ROOT}/needle.h")
    message(FATAL_ERROR "Missing verified Needle Android artifacts. Run android/app/src/main/cpp/whistle/fetch-native.sh")
endif()

add_library(needle_prebuilt STATIC IMPORTED GLOBAL)
set_target_properties(needle_prebuilt PROPERTIES IMPORTED_LOCATION "${NEEDLE_ROOT}/libneedle.a")

add_library(kompi_whistle SHARED whistle_jni.cpp)
target_include_directories(kompi_whistle PRIVATE "${NEEDLE_ROOT}")
target_compile_features(kompi_whistle PRIVATE cxx_std_17)
target_compile_options(kompi_whistle PRIVATE -Wall -Wextra -Werror)
target_link_options(kompi_whistle PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
target_link_libraries(kompi_whistle PRIVATE needle_prebuilt log android dl m)
