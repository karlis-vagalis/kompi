cmake_minimum_required(VERSION 3.22.1)
project(kompi_aec LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64)$")
    message(FATAL_ERROR "Kompi AEC3 supports Android arm64-v8a and x86_64")
endif()
get_filename_component(KOMPI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../.." ABSOLUTE)
set(AEC_CACHE "${KOMPI_ROOT}/target/live-aec-native")
# Re-evaluate content identities when a recipe/overlay pin changes, not only CMakeLists itself.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_LIST_DIR}/prepare-native.sh"
    "${CMAKE_CURRENT_LIST_DIR}/capture-suspend.patch"
    "${CMAKE_ANDROID_NDK}/source.properties")
# Match prepare-native.sh exactly. A changed pin/recipe or NDK cannot silently reuse an old .a.
file(SHA256 "${CMAKE_CURRENT_LIST_DIR}/prepare-native.sh" AEC_RECIPE_CODE_HASH)
file(SHA256 "${CMAKE_CURRENT_LIST_DIR}/capture-suspend.patch" AEC_OVERLAY_HASH)
string(SHA256 AEC_RECIPE_HASH "${AEC_RECIPE_CODE_HASH}\n${AEC_OVERLAY_HASH}\n")
file(SHA256 "${CMAKE_ANDROID_NDK}/source.properties" AEC_NDK_HASH)
string(SHA256 AEC_IDENTITY "${AEC_RECIPE_HASH}\n${AEC_NDK_HASH}\n${ANDROID_ABI}\n")
set(AEC_SOURCE "${AEC_CACHE}/source-${AEC_RECIPE_HASH}/webrtc-audio-processing-1.3")
set(AEC_ARCHIVE "${AEC_CACHE}/build-${ANDROID_ABI}-${AEC_IDENTITY}/webrtc/modules/audio_processing/libwebrtc-audio-processing-1.a")
if(NOT EXISTS "${AEC_ARCHIVE}")
    # Build-time acquisition only: HTTPS archives are content-verified; APK/runtime stays offline.
    # The helper serializes shared extraction and builds only the pinned APM dependency graph.
    execute_process(COMMAND "${CMAKE_COMMAND}" -E env "ANDROID_NDK_HOME=${CMAKE_ANDROID_NDK}"
        bash "${CMAKE_CURRENT_LIST_DIR}/prepare-native.sh" "${ANDROID_ABI}"
        RESULT_VARIABLE AEC_PREPARE_RESULT)
    if(NOT AEC_PREPARE_RESULT EQUAL 0 OR NOT EXISTS "${AEC_ARCHIVE}")
        message(FATAL_ERROR "Could not prepare pinned AEC3 for ${ANDROID_ABI}; see prepare-native.sh output")
    endif()
endif()
add_library(webrtc_apm STATIC IMPORTED)
set_target_properties(webrtc_apm PROPERTIES IMPORTED_LOCATION "${AEC_ARCHIVE}")
add_library(kompi_aec SHARED aec_jni.cpp)
target_compile_features(kompi_aec PRIVATE cxx_std_17)
target_compile_definitions(kompi_aec PRIVATE WEBRTC_LIBRARY_IMPL WEBRTC_POSIX WEBRTC_ANDROID WEBRTC_LINUX
    WEBRTC_APM_DEBUG_DUMP=0) # Explicitly DISABLED; concrete AEC3 headers require this definition.
target_include_directories(kompi_aec SYSTEM PRIVATE
    "${AEC_SOURCE}/webrtc"
    "${AEC_SOURCE}/subprojects/abseil-cpp-20230125.1")
target_compile_options(kompi_aec PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(kompi_aec PRIVATE
    "-Wl,--no-undefined" "-Wl,--exclude-libs,ALL"
    "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_libraries(kompi_aec PRIVATE webrtc_apm log android m)
