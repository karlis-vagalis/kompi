package com.kompi.app.audio

/** App-owned startup diagnoses, not a channel for AudioRecord/model/vendor exception payloads. */
internal enum class LiveAudioStartIssue(val guidance: String) {
    PermissionRequired("Microphone permission is required. Allow microphone access in Android settings, then start Live again."),
    ForegroundRequired("Live needs an active screen or assistant service. Return to Kompi, then start Live again."),
    ModelUnavailable("The selected local speech model could not start. Check its installation and device support, then start Live again."),
    ClassifierUnavailable("Kompi's bundled local speech classifier could not start. Restart Live or install a compatible Kompi build."),
    RouteUnavailable("The selected microphone route is unavailable. Reconnect or select a headset, then start Live again."),
    EchoUnavailable("Speaker Live needs working echo cancellation. Connect headphones, or restore local echo processing, then start Live again."),
    PreprocessingUnavailable("Live could not control microphone processing. Close other apps using the microphone, then start Live again. If it continues, try another audio device."),
    CaptureUnavailable("Local voice stopped. Check microphone permission, the selected route and installed speech models, then start Live again."),
    CaptureTimingUnavailable("Live stopped because microphone timing could not be verified. Start Live again."),
}

internal class LiveAudioStartException(val issue: LiveAudioStartIssue, cause: Throwable? = null) : IllegalStateException(issue.guidance, cause)
