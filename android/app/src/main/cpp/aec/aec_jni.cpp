#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <limits>
#include <mutex>
#include <unordered_map>

#include "api/scoped_refptr.h"
#include "common_audio/resampler/push_sinc_resampler.h"
#include "modules/audio_processing/include/audio_processing.h"
#include "modules/audio_processing/aec3/echo_canceller3.h"

namespace {
constexpr std::size_t kFrame = 160;  // Exactly 10 ms, 16 kHz, mono.
constexpr std::size_t kMaxSessions = 8;
constexpr std::size_t kMaxRenderStreams = 8;
constexpr int kBadHandle = -1001;
constexpr int kBadFrame = -1002;
constexpr int kNativeFailure = -1004;

struct RenderRateConverter {
  explicit RenderRateConverter(int rate) : sample_rate(rate) {
    if (rate != 16000) sinc = std::make_unique<webrtc::PushSincResampler>(rate / 100, kFrame);
  }
  const int sample_rate;
  std::unique_ptr<webrtc::PushSincResampler> sinc;
};

struct EchoState {
  webrtc::EchoCanceller3* controller = nullptr;  // APM-owned; fixed format/config; mutex below.
  bool capture_suspended = true;  // Latches until the FIRST REAL ProcessStream, not lease creation.
  std::uint64_t suspended_render_frames = 0;
  webrtc::AudioProcessingStats latest_stats;
  rtc::scoped_refptr<webrtc::AudioProcessing> apm;
  std::mutex mutex;
  std::unordered_map<std::int64_t, std::unique_ptr<RenderRateConverter>> render;
  std::int64_t next_render_stream = 1;
};

// Same official mono AEC3/default configuration, merely retaining its APM-owned pointer to call
// the narrowly pinned, externally serialized render-history maintenance hook. No deferred analysis.
class KompiEchoControlFactory final : public webrtc::EchoControlFactory {
 public:
  explicit KompiEchoControlFactory(EchoState* owner) : owner_(owner) {}
  std::unique_ptr<webrtc::EchoControl> Create(int rate, int render_channels,
                                              int capture_channels) override {
    auto controller = std::make_unique<webrtc::EchoCanceller3>(
        webrtc::EchoCanceller3Config(), rate, render_channels, capture_channels);
    owner_->controller = controller.get();
    return controller;
  }
 private:
  EchoState* const owner_;
};

// No JNI caller ever dereferences a raw jlong pointer. Closing removes registry ownership; an
// in-flight operation retains its own shared_ptr until it has finished with APM/resampler memory.
std::mutex registry_mutex;
std::unordered_map<std::int64_t, std::shared_ptr<EchoState>> registry;
std::atomic<std::int64_t> next_handle{1};

std::shared_ptr<EchoState> Find(jlong handle) {
  std::lock_guard<std::mutex> guard(registry_mutex);
  auto found = registry.find(handle);
  return found == registry.end() ? nullptr : found->second;
}

bool ValidFrame(JNIEnv* env, jfloatArray samples, std::size_t frames) {
  return samples != nullptr && env->GetArrayLength(samples) == static_cast<jsize>(frames);
}

bool Finite(const float* samples, std::size_t frames) {
  for (std::size_t i = 0; i < frames; ++i) {
    if (!std::isfinite(samples[i]) || samples[i] < -1.f || samples[i] > 1.f) return false;
  }
  return true;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_create(JNIEnv*, jobject) {
  try {
    auto state = std::make_shared<EchoState>();
    webrtc::AudioProcessingBuilder builder;
    builder.SetEchoControlFactory(std::make_unique<KompiEchoControlFactory>(state.get()));
    state->apm = builder.Create();
    if (!state->apm) return 0;
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false;  // Official APM selects EchoCanceller3, never AECM.
    config.gain_controller1.enabled = false;
    config.gain_controller2.enabled = false;
    // Software APM owns suppression when this processor is active. Android capture effects are
    // deliberately disabled on this path, so this cannot stack an OS suppressor with APM NS.
    config.noise_suppression.enabled = true;
    config.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
    state->apm->ApplyConfig(config);
    webrtc::ProcessingConfig streams;
    streams.input_stream() = webrtc::StreamConfig(16000, 1);
    streams.output_stream() = webrtc::StreamConfig(16000, 1);
    streams.reverse_input_stream() = webrtc::StreamConfig(16000, 1);
    streams.reverse_output_stream() = webrtc::StreamConfig(16000, 1);
    if (state->apm->Initialize(streams) != 0) return 0;
    std::lock_guard<std::mutex> guard(registry_mutex);
    if (registry.size() >= kMaxSessions) return 0;
    const auto handle = next_handle.fetch_add(1);
    registry.emplace(handle, std::move(state));
    return handle;
  } catch (...) { return 0; }
}

// Converter slots describe ACTIVE physical render streams, not the lifetime number of requests.
// Register lazily when played PCM is first converted; retire after the last played block below.
// Monotonic owner-issued IDs cannot resurrect a retired converter or silently reuse a changed rate.
extern "C" JNIEXPORT jlong JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_registerRenderStream(
    JNIEnv*, jobject, jlong handle, jint rate) {
  try {
    auto state = Find(handle);
    if (!state || rate < 8000 || rate > 96000 || rate % 100 != 0) return 0;
    std::lock_guard<std::mutex> guard(state->mutex);
    if (state->render.size() >= kMaxRenderStreams ||
        state->next_render_stream == std::numeric_limits<std::int64_t>::max()) return 0;
    const auto stream = state->next_render_stream++;
    state->render.emplace(stream, std::make_unique<RenderRateConverter>(rate));
    return stream;
  } catch (...) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_resampleRender(
    JNIEnv* env, jobject, jlong handle, jlong stream, jint rate,
    jfloatArray input, jfloatArray output) {
  try {
    auto state = Find(handle);
    if (!state) return kBadHandle;
    if (rate < 8000 || rate > 96000 || rate % 100 != 0 ||
        !ValidFrame(env, input, static_cast<std::size_t>(rate / 100)) ||
        !ValidFrame(env, output, kFrame)) return kBadFrame;
    std::array<float, 960> source{};
    std::array<float, kFrame> converted{};
    env->GetFloatArrayRegion(input, 0, rate / 100, source.data());
    if (env->ExceptionCheck() || !Finite(source.data(), rate / 100)) return kBadFrame;
    std::lock_guard<std::mutex> guard(state->mutex);
    auto found = state->render.find(stream);
    if (found == state->render.end()) return kBadHandle;
    if (found->second->sample_rate != rate) return kBadFrame;
    if (!found->second->sinc) {
      std::copy_n(source.begin(), kFrame, converted.begin());
    } else if (found->second->sinc->Resample(source.data(), rate / 100, converted.data(), kFrame) != kFrame) {
      return kNativeFailure;
    }
    for (auto& sample : converted) sample = std::clamp(sample, -1.f, 1.f);
    env->SetFloatArrayRegion(output, 0, kFrame, converted.data());
    return env->ExceptionCheck() ? kNativeFailure : 0;
  } catch (...) { return kNativeFailure; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_reverse(
    JNIEnv* env, jobject, jlong handle, jfloatArray input, jboolean capture_suspended) {
  try {
    auto state = Find(handle);
    if (!state) return kBadHandle;
    if (!ValidFrame(env, input, kFrame)) return kBadFrame;
    std::array<float, kFrame> source{}, unused{};
    env->GetFloatArrayRegion(input, 0, kFrame, source.data());
    if (env->ExceptionCheck() || !Finite(source.data(), kFrame)) return kBadFrame;
    const float* in[] = {source.data()};
    float* out[] = {unused.data()};
    std::lock_guard<std::mutex> guard(state->mutex);
    if (state->controller &&
        state->controller->KompiNormalDiagnostics().reference_only.phase_faults != 0) {
      return kNativeFailure;
    }
    if (capture_suspended) state->capture_suspended = true;
    const auto status = state->apm->ProcessReverseStream(in, webrtc::StreamConfig(16000, 1),
                                                       webrtc::StreamConfig(16000, 1), out);
    if (status != 0) return status;
    if (!state->controller) return kNativeFailure;
    // Render analysis is already complete BEFORE HAL write. Consume its transfer queue now;
    // when mic is closed, maintain ONLY render-ring time/history, never manufacture capture PCM.
    state->controller->KompiDrainRenderQueue(state->capture_suspended);
    if (state->capture_suspended) ++state->suspended_render_frames;
    return state->controller->KompiNormalDiagnostics().reference_only.phase_faults == 0
               ? 0 : kNativeFailure;
  } catch (...) { return kNativeFailure; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_capture(
    JNIEnv* env, jobject, jlong handle, jfloatArray input, jint delay_ms, jfloatArray output) {
  try {
    auto state = Find(handle);
    if (!state) return kBadHandle;
    if (!ValidFrame(env, input, kFrame) || !ValidFrame(env, output, kFrame) ||
        delay_ms < 0 || delay_ms > 500) return kBadFrame;
    std::array<float, kFrame> source{}, processed{};
    env->GetFloatArrayRegion(input, 0, kFrame, source.data());
    if (env->ExceptionCheck() || !Finite(source.data(), kFrame)) return kBadFrame;
    const float* in[] = {source.data()};
    float* out[] = {processed.data()};
    std::lock_guard<std::mutex> guard(state->mutex);
    state->capture_suspended = false;
    if (!state->controller) return kNativeFailure;
    state->controller->KompiDrainRenderQueue(false);
    // End validates the SAME inherited physical FIR phase before any real adaptation.
    if (state->controller->KompiNormalDiagnostics().reference_only.phase_faults != 0) {
      return kNativeFailure;
    }
    const auto delay_status = state->apm->set_stream_delay_ms(delay_ms);
    if (delay_status != 0) return delay_status;
    const auto status = state->apm->ProcessStream(in, webrtc::StreamConfig(16000, 1),
                                               webrtc::StreamConfig(16000, 1), out);
    if (status != 0) return status;
    // The public reporter has ONE slot and drops NEW statistics while unread. Drain EVERY
    // actual capture call; later numeric sampling reads this exact latest immutable value.
    state->latest_stats = state->apm->GetStatistics();
    env->SetFloatArrayRegion(output, 0, kFrame, processed.data());
    return env->ExceptionCheck() ? kNativeFailure : 0;
  } catch (...) { return kNativeFailure; }
}

// Numeric-only snapshots; no PCM/debug dump. delay_ms is AEC3's selected internal buffer delay,
// not the application's coarse pipeline estimate and not an assertion of measured acoustic ERLE.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_diagnostics(JNIEnv* env, jobject, jlong handle) {
  try {
    auto state = Find(handle);
    if (!state) return nullptr;
    std::lock_guard<std::mutex> guard(state->mutex);
    if (!state->controller) return nullptr;
    const auto& stats = state->latest_stats;
    const auto absent = std::numeric_limits<double>::quiet_NaN();
    const int present = (stats.delay_ms ? 1 : 0) | (stats.echo_return_loss ? 2 : 0) |
                        (stats.echo_return_loss_enhancement ? 4 : 0);
    // Append-only schema: 0..9 retain the existing statistics/suspension contract.
    // 10..20 are exact normal-path counts and 4 ms render-ring latency snapshots;
    // Subtractor counts its actual full-reset lambda once, not once per filter/channel.
    // 21..44 retain reference-only origin/full-FIR geometry and separate/latched clocks.
    // 45..75 observe actual EchoRemover decisions; units/presence are in its typed snapshot.
    const auto normal = state->controller->KompiNormalDiagnostics();
    const auto& phase = normal.reference_only;
    const auto& decision = normal.decision;
    // A reported delay of zero does NOT itself assert correlation lock.
    const jdouble values[] = {stats.delay_ms ? static_cast<double>(*stats.delay_ms) : absent,
        stats.echo_return_loss.value_or(absent), stats.echo_return_loss_enhancement.value_or(absent),
        static_cast<double>(state->suspended_render_frames),
        static_cast<double>(state->controller->KompiCaptureSuspendedBufferFaults()),
        static_cast<double>(present),
        static_cast<double>(state->controller->KompiCaptureSuspendedEventMask()),
        static_cast<double>(state->controller->KompiCaptureSuspendedTransitionLatencyBlocks()),
        static_cast<double>(state->controller->KompiCaptureSuspendedAdvancedBlocks()),
        static_cast<double>(state->controller->KompiCaptureSuspendedRecoveryLatencyBlocks()),
        static_cast<double>(normal.insert_overruns),
        static_cast<double>(normal.prepare_excess_resets),
        static_cast<double>(normal.prepare_underruns),
        static_cast<double>(normal.last_insert_latency_before),
        static_cast<double>(normal.last_insert_latency_after),
        static_cast<double>(normal.last_excess_latency_before),
        static_cast<double>(normal.last_excess_latency_after),
        static_cast<double>(normal.last_underrun_latency_before),
        static_cast<double>(normal.last_underrun_latency_after),
        static_cast<double>(normal.current_render_latency_blocks),
        static_cast<double>(normal.subtractor_full_resets),
        static_cast<double>(phase.state),
        static_cast<double>(phase.inherited_latency_blocks),
        static_cast<double>(phase.inherited_full_rate_lag_blocks),
        static_cast<double>(phase.pair_before_latency_blocks),
        static_cast<double>(phase.pair_after_latency_blocks),
        static_cast<double>(phase.pair_before_full_rate_lag_blocks),
        static_cast<double>(phase.pair_after_full_rate_lag_blocks),
        static_cast<double>(phase.ticks),
        static_cast<double>(phase.phase_faults),
        static_cast<double>(phase.canonical_minimum_latency_blocks),
        static_cast<double>(phase.detector_clock),
        static_cast<double>(phase.normal_detector_clock_at_entry),
        static_cast<double>(phase.normal_detector_clock),
        static_cast<double>(phase.normal_minimum_latency_at_entry),
        static_cast<double>(phase.normal_minimum_latency),
        static_cast<double>(phase.capture_clock_at_entry),
        static_cast<double>(phase.capture_clock),
        static_cast<double>(phase.low_rate_capacity_blocks),
        static_cast<double>(phase.maximum_full_rate_lag_blocks),
        static_cast<double>(phase.render_clock_at_entry),
        static_cast<double>(phase.render_clock),
        static_cast<double>(phase.ticks_at_entry),
        static_cast<double>(phase.resume_latency_blocks),
        static_cast<double>(phase.resume_full_rate_lag_blocks),
        static_cast<double>(decision.presence_mask),
        static_cast<double>(decision.processed_blocks),
        static_cast<double>(decision.processed_samples),
        static_cast<double>(decision.selected_linear_blocks),
        static_cast<double>(decision.selected_raw_blocks),
        static_cast<double>(decision.usable_linear_blocks),
        static_cast<double>(decision.transparent_blocks),
        static_cast<double>(decision.nearend_blocks),
        static_cast<double>(decision.capture_saturated_blocks),
        static_cast<double>(decision.echo_saturated_blocks),
        static_cast<double>(decision.initial_state_blocks),
        static_cast<double>(decision.residual_usable_saturated_blocks),
        static_cast<double>(decision.residual_linear_uncertainty_blocks),
        static_cast<double>(decision.residual_linear_spectrum_blocks),
        static_cast<double>(decision.residual_unusable_saturated_blocks),
        static_cast<double>(decision.residual_render_power_blocks),
        static_cast<double>(decision.capture_input_time_energy_q30),
        static_cast<double>(decision.linear_time_energy_q30),
        static_cast<double>(decision.selected_time_energy_q30),
        static_cast<double>(decision.output_time_energy_q30),
        static_cast<double>(decision.pre_gain_spectral_energy_q30),
        static_cast<double>(decision.post_gain_spectral_energy_q30),
        static_cast<double>(decision.gain_squared_sum_q20),
        static_cast<double>(decision.gain_bin_count),
        static_cast<double>(decision.last_gain_min_q15),
        static_cast<double>(decision.last_gain_max_q15),
        static_cast<double>(decision.last_flag_mask),
        static_cast<double>(decision.last_residual_branch),
        static_cast<double>(decision.invalid_numeric_blocks),
        static_cast<double>(decision.last_observed_block_sequence),
        static_cast<double>(decision.echo_remover_calls)};
    constexpr jsize count = sizeof(values) / sizeof(values[0]);
    auto result = env->NewDoubleArray(count);
    if (result) env->SetDoubleArrayRegion(result, 0, count, values);
    return result;
  } catch (...) { return nullptr; }
}

// A PushSincResampler has half a kernel of state (at most 2 ms at the minimum 8 kHz rate).
// One 10 ms zero block emits its entire final tail. The caller timestamps that output at the end
// of the final padded source block minus AlgorithmicDelaySeconds(rate), retains the resulting
// played-reference PCM, and then frees this slot. This NEVER resets APM or its acoustic echo tail.
extern "C" JNIEXPORT jint JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_retireRenderStream(
    JNIEnv* env, jobject, jlong handle, jlong stream, jfloatArray tail_output) {
  try {
    auto state = Find(handle);
    if (!state) return kBadHandle;
    if (!ValidFrame(env, tail_output, kFrame)) return kBadFrame;
    std::array<float, 960> silence{};
    std::array<float, kFrame> tail{};
    std::lock_guard<std::mutex> guard(state->mutex);
    auto found = state->render.find(stream);
    if (found == state->render.end()) return kBadHandle;
    if (found->second->sinc &&
        found->second->sinc->Resample(silence.data(), found->second->sample_rate / 100,
                                    tail.data(), kFrame) != kFrame) {
      state->render.erase(found);
      return kNativeFailure;
    }
    for (auto& sample : tail) sample = std::clamp(sample, -1.f, 1.f);
    state->render.erase(found);  // Return slot ownership BEFORE the next request registers.
    env->SetFloatArrayRegion(tail_output, 0, kFrame, tail.data());
    return env->ExceptionCheck() ? kNativeFailure : 0;
  } catch (...) { return kNativeFailure; }
}

// Explicit discontinuity only: discard the converter, not APM. A replacement must register a NEW
// monotonic stream ID; even a delayed call with an old 16 kHz passthrough ID is rejected.
extern "C" JNIEXPORT void JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_dropRenderStream(
    JNIEnv*, jobject, jlong handle, jlong stream) {
  auto state = Find(handle);
  if (!state) return;
  std::lock_guard<std::mutex> guard(state->mutex);
  state->render.erase(stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kompi_app_audio_WebRtcAec3Native_destroy(JNIEnv*, jobject, jlong handle) {
  std::shared_ptr<EchoState> disposing;
  {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto found = registry.find(handle);
    if (found == registry.end()) return;
    disposing = std::move(found->second);
    registry.erase(found);
  }
  // The Kotlin owner dispatches native disposal off the hardware/privacy path.
}
