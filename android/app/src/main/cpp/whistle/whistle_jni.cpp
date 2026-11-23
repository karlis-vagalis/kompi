#include <jni.h>
#include <needle.h>

#include <cmath>
#include <cstdint>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex g_api_mutex;
std::vector<unsigned char> g_model_bytes;
std::string g_model_path;
bool g_model_loaded = false;
constexpr int kJsonCapacity = 64 * 1024;
constexpr std::uint64_t kMaxModelBytes = 128ULL * 1024ULL * 1024ULL;

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string needle_error(const char* fallback) {
    const char* detail = needle_last_error();
    return detail != nullptr && detail[0] != '\0' ? detail : fallback;
}

std::string to_utf8(JNIEnv* env, jstring value) {
    // GetStringUTFChars uses modified UTF-8; vocabulary hints need standard UTF-8,
    // including names containing supplementary Unicode characters.
    jclass string_type = env->GetObjectClass(value);
    if (string_type == nullptr) return {};
    jmethodID get_bytes = env->GetMethodID(string_type, "getBytes", "(Ljava/lang/String;)[B");
    env->DeleteLocalRef(string_type);
    if (get_bytes == nullptr) return {};
    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) return {};
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, get_bytes, charset));
    env->DeleteLocalRef(charset);
    if (env->ExceptionCheck() || bytes == nullptr) return {};
    const jsize size = env->GetArrayLength(bytes);
    std::string result(static_cast<std::size_t>(size), '\0');
    if (size > 0) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(result.data()));
    env->DeleteLocalRef(bytes);
    return result;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_kompi_app_audio_whistle_WhistleBackend_nativeLoad(
    JNIEnv* env, jclass, jstring model_path) {
    if (model_path == nullptr) {
        throw_illegal_state(env, "Whistle model path is null");
        return;
    }
    const std::string path = to_utf8(env, model_path);
    if (env->ExceptionCheck()) return;

    std::lock_guard<std::mutex> lock(g_api_mutex);
    if (g_model_loaded) {
        if (path == g_model_path) return;
        throw_illegal_state(env, "Needle's C API has no unload/reset-model operation; Whistle is already loaded");
        return;
    }

    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) {
        throw_illegal_state(env, "Cannot open Whistle model file");
        return;
    }
    const std::streamoff length = input.tellg();
    if (length <= 0 || static_cast<std::uint64_t>(length) > kMaxModelBytes) {
        throw_illegal_state(env, "Whistle model has an invalid or excessive file size");
        return;
    }
    std::vector<unsigned char> bytes(static_cast<std::size_t>(length));
    input.seekg(0, std::ios::beg);
    if (!input.read(reinterpret_cast<char*>(bytes.data()), length)) {
        throw_illegal_state(env, "Could not read the complete Whistle model file");
        return;
    }

    const int loaded = needle_load(bytes.data(), static_cast<unsigned long long>(bytes.size()));
    if (loaded < 0 || (needle_models() & NEEDLE_SPEECH) == 0) {
        throw_illegal_state(env, needle_error("Needle failed to load a speech model"));
        return;
    }
    g_model_bytes = std::move(bytes);  // Keep backing bytes alive for runtimes that retain the input view.
    g_model_path = path;
    g_model_loaded = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kompi_app_audio_whistle_WhistleBackend_nativeStreamTranscribe(
    JNIEnv* env, jclass, jfloatArray pcm, jstring language, jstring keywords) {
    if (pcm == nullptr) {
        throw_illegal_state(env, "PCM array is null");
        return nullptr;
    }
    const jsize samples = env->GetArrayLength(pcm);
    if (samples <= 0 || samples > 32'000) {
        throw_illegal_state(env, "Live PCM chunk must contain 1..32000 samples");
        return nullptr;
    }

    const std::string keyword_string = keywords != nullptr ? to_utf8(env, keywords) : "";
    if (env->ExceptionCheck()) return nullptr;
    const char* keyword_chars = keyword_string.empty() ? nullptr : keyword_string.c_str();
    std::string language_string;
    const char* language_chars = nullptr;
    if (language != nullptr) {
        language_string = to_utf8(env, language);
        if (env->ExceptionCheck()) return nullptr;
        language_chars = language_string.c_str();
    }

    jfloat* pcm_data = env->GetFloatArrayElements(pcm, nullptr);
    if (pcm_data == nullptr) return nullptr;
    for (jsize i = 0; i < samples; ++i) {
        if (!std::isfinite(pcm_data[i]) || pcm_data[i] < -1.0f || pcm_data[i] > 1.0f) {
            env->ReleaseFloatArrayElements(pcm, pcm_data, JNI_ABORT);
            throw_illegal_state(env, "PCM samples must be finite floats in [-1, 1]");
            return nullptr;
        }
    }

    std::string result;
    {
        std::lock_guard<std::mutex> lock(g_api_mutex);
        if (!g_model_loaded) {
            env->ReleaseFloatArrayElements(pcm, pcm_data, JNI_ABORT);
            throw_illegal_state(env, "Whistle speech model has not been loaded");
            return nullptr;
        }
        std::vector<char> output(kJsonCapacity, '\0');
        const int status = needle_stream_transcribe_process(
            pcm_data, static_cast<int>(samples), language_chars, keyword_chars,
            output.data(), static_cast<int>(output.size()));
        result = status < 0 ? needle_error("Whistle live transcription failed") : output.data();
    }
    env->ReleaseFloatArrayElements(pcm, pcm_data, JNI_ABORT);
    if (result.empty() || result.front() != '{') {
        throw_illegal_state(env, result.empty() ? "Whistle returned an empty live response" : result);
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kompi_app_audio_whistle_WhistleBackend_nativeStreamStop(
    JNIEnv* env, jclass) {
    std::string result;
    {
        std::lock_guard<std::mutex> lock(g_api_mutex);
        if (!g_model_loaded) {
            throw_illegal_state(env, "Whistle speech model has not been loaded");
            return nullptr;
        }
        std::vector<char> output(kJsonCapacity, '\0');
        const int status = needle_stream_transcribe_stop(output.data(), static_cast<int>(output.size()));
        result = status < 0 ? needle_error("Whistle live transcription stop failed") : output.data();
    }
    if (result.empty() || result.front() != '{') {
        throw_illegal_state(env, result.empty() ? "Whistle returned an empty live stop response" : result);
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kompi_app_audio_whistle_WhistleBackend_nativeTranscribe(
    JNIEnv* env, jclass, jfloatArray pcm, jstring language, jstring keywords) {
    if (pcm == nullptr) {
        throw_illegal_state(env, "PCM array is null");
        return nullptr;
    }
    const jsize samples = env->GetArrayLength(pcm);
    if (samples <= 0) {
        throw_illegal_state(env, "PCM array is empty");
        return nullptr;
    }

    const char* language_chars = nullptr;
    std::string language_string;
    if (language != nullptr) {
        language_chars = env->GetStringUTFChars(language, nullptr);
        if (language_chars == nullptr) return nullptr;
        language_string.assign(language_chars);
        env->ReleaseStringUTFChars(language, language_chars);
        language_chars = language_string.c_str();
    }

    const std::string keyword_string = keywords != nullptr ? to_utf8(env, keywords) : "";
    if (env->ExceptionCheck()) return nullptr;
    const char* keyword_chars = keyword_string.empty() ? nullptr : keyword_string.c_str();
    jboolean is_copy = JNI_FALSE;
    jfloat* pcm_data = env->GetFloatArrayElements(pcm, &is_copy);
    if (pcm_data == nullptr) return nullptr;
    for (jsize i = 0; i < samples; ++i) {
        if (!std::isfinite(pcm_data[i]) || pcm_data[i] < -1.0f || pcm_data[i] > 1.0f) {
            env->ReleaseFloatArrayElements(pcm, pcm_data, JNI_ABORT);
            throw_illegal_state(env, "PCM samples must be finite floats in [-1, 1]");
            return nullptr;
        }
    }

    std::string result;
    {
        std::lock_guard<std::mutex> lock(g_api_mutex);
        if (!g_model_loaded) {
            env->ReleaseFloatArrayElements(pcm, pcm_data, JNI_ABORT);
            throw_illegal_state(env, "Whistle speech model has not been loaded");
            return nullptr;
        }
        std::vector<char> output(kJsonCapacity, '\0');
        const int status = needle_transcribe(
            reinterpret_cast<const float*>(pcm_data), static_cast<int>(samples), language_chars,
            keyword_chars, 0, output.data(), static_cast<int>(output.size()));
        if (status < 0) {
            result = needle_error("Whistle transcription failed");
        } else {
            result.assign(output.data());
        }
    }
    env->ReleaseFloatArrayElements(pcm, pcm_data, JNI_ABORT);
    if (result.empty() || result.front() != '{') {
        throw_illegal_state(env, result.empty() ? "Whistle returned an empty response" : result);
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}
