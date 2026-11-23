package com.kompi.app.live

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.audio.MicCaptureCoordinator
import com.kompi.app.audio.LiveAudioSession
import com.kompi.app.diagnostics.LocalDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object LiveVoiceLimits {
    const val MAX_TURN_CHARS = 16_000
    const val MAX_CAPTION_CHARS = 4_000
    const val MIC_HANDOFF_TIMEOUT_MS = 2_000L
    val SUPPORTED_WHISTLE_LANGUAGES = setOf("en", "de", "fr", "es", "it", "nl", "pl")
}

/** A finalized, non-empty local transcript. The same ID is reused for every durable-outbox retry. */
data class LiveTurn private constructor(val requestId: String, val text: String) {
    companion object {
        fun create(text: String, requestId: String = UUID.randomUUID().toString()): LiveTurn? {
            val finalized = text.trim()
            if (finalized.isEmpty() || finalized.length > LiveVoiceLimits.MAX_TURN_CHARS) return null
            if (requestId.isBlank() || requestId.length > 128) return null
            return LiveTurn(requestId, finalized)
        }
    }
}

/** Committed words are stable; the selected decoder's actual provisional tail is replaced in place. */
data class LiveCaption private constructor(
    val committedText: String,
    val pendingText: String,
    val language: String?,
) {
    val visibleText: String get() = listOf(committedText, pendingText).filter(String::isNotBlank).joinToString(" ")

    companion object {
        fun fromWhistle(committedText: String, pendingText: String, language: String?): LiveCaption = LiveCaption(
            committedText = committedText.take(LiveVoiceLimits.MAX_CAPTION_CHARS),
            pendingText = pendingText.take(LiveVoiceLimits.MAX_CAPTION_CHARS),
            language = language?.takeIf { it in LiveVoiceLimits.SUPPORTED_WHISTLE_LANGUAGES },
        )
        val Empty = LiveCaption("", "", null)
    }
}

enum class CapturePhase { Listening, Recognizing }

/** Actual selected-engine boundary. Implemented by the local audio worker using PCM only. */
interface LiveSpeechInput : Closeable {
    interface Listener {
        /** Local speech admission started; this alone does not authorize cancelling a reply. */
        fun onSpeechStarted(utteranceId: SpeechUtteranceId)
        /** Independent local hysteresis confirms this candidate should take the floor. */
        fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) {}
        fun onPartial(utteranceId: SpeechUtteranceId, committedText: String, pendingText: String, language: String?)
        fun onFinal(utteranceId: SpeechUtteranceId, text: String, language: String?)
        /** A confirmed candidate ended without text; never submit an empty request. */
        fun onEmptyFinal(utteranceId: SpeechUtteranceId) {}
        fun onTranscriptionStatus(status: LiveTranscriptionStatus) {}
        fun onMeter(rms: Float, phase: CapturePhase, waveform: AudioWaveform)
        /** Audio revocation precedes this callback; the receiving closure owns its generation. */
        fun onLocalStop(reason: LiveLocalStopReason)
    }
    fun start(listener: Listener)
    /**
     * Synchronously fence input callbacks and request actual recording stop, including delayed
     * startup. Never join a reader/decoder or dispose optional native cues on this privacy path.
     */
    fun requestStopAndFence()
    /** End discards unfinished words; pause flushes one finalized VAD utterance. */
    fun stop(finalizeCurrentUtterance: Boolean)
    /**
     * Fences callbacks and completes the bounded hardware-microphone stop/join. Native decoder
     * cleanup continues off-main under the app-scoped capture lease; callers must not block
     * navigation on that cleanup, but must not start an opposite capture until ownership is free.
     */
    suspend fun stopMicrophoneAndFence()
    /** Full recognizer join reserved for flows that explicitly need decoder completion. */
    suspend fun stopAndJoin(finalizeCurrentUtterance: Boolean)
    /** Full local teardown for explicit capture handoff; native close completes before returning. */
    suspend fun closeAndJoin() {
        stopAndJoin(finalizeCurrentUtterance = false)
        close()
    }
}

sealed interface LiveConversationState {
    data object Idle : LiveConversationState
    data object Preparing : LiveConversationState
    data object Listening : LiveConversationState
    data object Recognizing : LiveConversationState
    data class Waiting(val requestId: String) : LiveConversationState
    data class Speaking(val requestId: String) : LiveConversationState
    data object Interrupted : LiveConversationState
    data object Paused : LiveConversationState
    data class Failure(val message: String) : LiveConversationState
}

sealed interface LiveReadAlong {
    data class Reply(val sequence: Long, val phrase: String) : LiveReadAlong
    data class Thinking(val title: String) : LiveReadAlong
}

data class LiveConversationSnapshot(
    val state: LiveConversationState = LiveConversationState.Idle,
    val caption: LiveCaption = LiveCaption.Empty,
    val transcriptionStatus: LiveTranscriptionStatus = LiveTranscriptionStatus.Inactive,
    /** A real partial from the latest utterance; presentation only, never barge-in authority. */
    val inputCaptionActive: Boolean = false,
    val assistantText: String = "",
    val playingPhrase: String = "",
    val replyRequestId: String? = null,
    val thinkingTitles: List<String> = emptyList(),
    val latestActivity: String? = null,
    val readAlong: LiveReadAlong? = null,
    val micLevel: AudioLevel = AudioLevel.Quiet,
    val playbackLevel: AudioLevel = AudioLevel.Quiet,
    val micWaveform: AudioWaveform = AudioWaveform.Silence,
    val playbackWaveform: AudioWaveform = AudioWaveform.Silence,
    val microphoneMuted: Boolean = false,
    val outputMuted: Boolean = false,
    /** Full immutable delivery intent, separate from the deliberately bounded live caption. */
    val recoverableTurn: LiveTurn? = null,
) {
    val visualizerWaveform: AudioWaveform get() = when (state) {
        LiveConversationState.Listening, LiveConversationState.Recognizing -> micWaveform
        is LiveConversationState.Speaking -> playbackWaveform
        else -> AudioWaveform.Silence
    }
    val visualizerLevel: AudioLevel get() = when (state) {
        LiveConversationState.Listening, LiveConversationState.Recognizing -> micLevel
        is LiveConversationState.Speaking -> playbackLevel
        else -> AudioLevel.Quiet
    }
}

/**
 * Serialized owner of the screen- or assistant-service-scoped Live loop. It never owns or closes the persisted Pi session;
 * the caller routes each finalized turn through IrohTransport's durable, stable-ID outbox.
 */
class LiveConversationController(
    private val lifecycleOwner: LifecycleOwner,
    private val createSpeechInput: (LiveAudioSession?) -> LiveSpeechInput,
    private val sendTurn: suspend (requestId: String, text: String) -> Unit,
    private val stopLocalSpeech: (requestId: String?) -> Unit,
    private val cancelRemote: suspend (requestId: String) -> Unit,
    private val onLiveEnded: () -> Unit = {},
    private val openAudioSession: ((LiveLocalStopReason) -> Unit) -> LiveAudioSession? = { null },
    private val setOutputMuted: (Boolean) -> Unit = {},
) : Closeable, LifecycleEventObserver {
    private sealed interface Event {
        data class Start(val activation: Long) : Event
        data class End(val joinDecoder: Boolean, val completion: CompletableDeferred<Unit>? = null) : Event
        data class LocalStop(val reason: LiveLocalStopReason, val joinDecoder: Boolean = false, val completion: CompletableDeferred<Unit>? = null) : Event
        data object Interrupt : Event
        data object ToggleMicrophoneMute : Event
        data object ToggleOutputMute : Event
        data class SpeechStarted(val utteranceId: SpeechUtteranceId, val generation: Long) : Event
        data class BargeInConfirmed(val utteranceId: SpeechUtteranceId, val generation: Long) : Event
        data class Partial(val utteranceId: SpeechUtteranceId, val committed: String, val pending: String, val language: String?, val generation: Long, val publishedAtNanos: Long = System.nanoTime()) : Event
        data class Final(val utteranceId: SpeechUtteranceId, val text: String, val language: String?, val generation: Long, val publishedAtNanos: Long = System.nanoTime()) : Event
        data class Meter(val rms: Float, val phase: CapturePhase, val generation: Long, val waveform: AudioWaveform) : Event
        data object MeterAvailable : Event
        data class TranscriptionStatus(val status: LiveTranscriptionStatus, val generation: Long) : Event
        data class Failure(val reason: String, val generation: Long? = null) : Event
        data class ReplyText(val requestId: String, val text: String) : Event
        data class ThinkingTitle(val requestId: String, val title: String) : Event
        data class Activity(val requestId: String, val label: String) : Event
        data class ReplyPreparing(val requestId: String, val phrase: String) : Event
        data class ReplyPlaying(val requestId: String, val phrase: String, val rms: Float, val waveform: AudioWaveform, val readAlong: LiveReadAlong) : Event
        data class ReplyDraining(val requestId: String, val phrase: String) : Event
        data object ReplyIdle : Event
        data class ReplyDrained(val requestId: String) : Event
        data class Sent(val turn: LiveTurn, val session: Long, val attempt: Long) : Event
        data class SendFailed(val turn: LiveTurn, val reason: String, val session: Long, val attempt: Long) : Event
        data class CancellationFailed(val requestId: String, val session: Long, val owner: Long) : Event
        data class ContextChanged(val key: String) : Event
        data class TurnCommitted(val requestId: String) : Event
        data class RetryPending(val activation: Long) : Event
    }

    // Always dispatch actor work: publishing a control event must not run cleanup inline while
    // the short ownership/publication lock is held by a public privacy-stop entry point.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = Channel<Event>(Channel.UNLIMITED)
    // A waveform is replaceable presentation, unlike speech onset/final/cancel events. Keep at
    // most one meter notification queued so a stalled Main cannot accumulate PCM UI updates
    // ahead of a caption, endpoint or barge-in. This never drops capture/decoder samples.
    private val pendingMeter = AtomicReference<Event.Meter?>()
    private val mutableSnapshot = MutableStateFlow(LiveConversationSnapshot())
    val snapshot: StateFlow<LiveConversationSnapshot> = mutableSnapshot.asStateFlow()
    /** Publish/remove exact local owners under this short lock, never model construction or joins. */
    private val audioOwnershipLock = Any()
    private var speechInput: LiveSpeechInput? = null
    private var audioSession: LiveAudioSession? = null
    private data class OwnedAudio(val input: LiveSpeechInput?, val session: LiveAudioSession?)
    @Volatile private var liveRequested = false
    /** Capture generations change on mic mute; the duplex lease survives until the session ends. */
    @Volatile private var generation = 0L
    @Volatile private var sessionGeneration = 0L
    @Volatile private var activation = 0L
    private var turnOwnership = 0L
    private var deliveryAttempt = 0L
    private var activeRequestId: String? = null
    private var audibleRequestId: String? = null
    private val submittedTurnIds = LinkedHashSet<String>()
    @Volatile private var contextKey: String? = null
    private var requestedContextKey: String? = null
    private var pendingTurn: LiveTurn? = null
    private var latestUtterance: SpeechUtteranceId? = null
    private var pendingBargeInUtterance: SpeechUtteranceId? = null
    private var pendingBargeInCaption: LiveCaption? = null
    private var lastFinalUtterance: SpeechUtteranceId? = null
    private val deferredUserText = StringBuilder()
    @Volatile private var closed = false

    init {
        lifecycleOwner.lifecycle.addObserver(this)
        scope.launch {
            for (event in events) reduce(event)
        }
    }

    /** Must be invoked only by an explicit Live control after mic permission is granted. */
    fun startLive() {
        synchronized(audioOwnershipLock) {
            if (!closed && !liveRequested) events.trySend(Event.Start(++activation))
        }
    }
    fun endLive() { publishPrivacyStop(LiveLocalStopReason.Backgrounded, Event.End(joinDecoder = false)) }

    /**
     * Publish control ordering and callback fences atomically, then revoke the exact native owner
     * on this caller, independently of actor/Main progress. The actor alone joins/disposes it.
     * A revoked duplex lease stays registered until cleanup, so late PCM cannot acquire a new
     * standalone focus lease. No remote operation belongs on this path.
     */
    private fun publishPrivacyStop(reason: LiveLocalStopReason, event: Event, onlyWhenActive: Boolean = false): Boolean {
        val owned = synchronized(audioOwnershipLock) {
            if (closed) return false
            activation++ // Also invalidates a Start/Retry still queued before this stop.
            if (onlyWhenActive && !liveRequested && speechInput == null && audioSession == null) return false
            liveRequested = false
            generation++
            sessionGeneration++
            OwnedAudio(speechInput, audioSession).also { events.trySend(event) }
        }
        owned.session?.fenceHardware(reason)
        owned.input?.requestStopAndFence()
        return true
    }

    /**
     * Leave voice mode while preserving the active Pi request and its Chat stream. No remote
     * cancellation is authorized here. Native cleanup continues under exclusive app ownership.
     */
    suspend fun detachVoiceAndJoin() {
        if (closed) return
        val completion = CompletableDeferred<Unit>()
        if (!publishPrivacyStop(LiveLocalStopReason.Backgrounded,
            Event.LocalStop(LiveLocalStopReason.Backgrounded, completion = completion), onlyWhenActive = true)) return
        completion.await()
    }

    /** Compatibility name for the bounded, passive navigation handoff. */
    suspend fun stopMicrophoneAndFence() = detachVoiceAndJoin()

    /** Full passive PTT↔Live handoff; never cancel Pi work just to acquire the microphone. */
    suspend fun detachVoiceForCaptureAndJoin() {
        if (closed) return
        val completion = CompletableDeferred<Unit>()
        if (publishPrivacyStop(LiveLocalStopReason.Backgrounded,
            Event.LocalStop(LiveLocalStopReason.Backgrounded, joinDecoder = true, completion = completion), onlyWhenActive = true)) {
            completion.await()
        }
        // Even an already-detached controller may have uninterruptible native cleanup retaining
        // the app-wide lease. No extra global speech stop is authorized for an idle controller.
        val ownershipReleased = withContext(Dispatchers.IO) { MicCaptureCoordinator.awaitIdle(timeoutMillis = LiveVoiceLimits.MIC_HANDOFF_TIMEOUT_MS) }
        check(ownershipReleased) { "Microphone ownership is still closing; refusing opposite capture" }
    }

    /** Compatibility name: despite its old name, this is NOT explicit End/cancellation. */
    suspend fun endLiveAndJoin() = detachVoiceForCaptureAndJoin()

    /** Navigation-safe disposal; do not await a native decoder join here. */
    suspend fun closeAndJoin() {
        if (closed) return
        detachVoiceAndJoin()
        close()
    }

    /** Manual fallback uses the same cancellation path as speech onset, without restarting capture. */
    fun interruptAndListen() { events.trySend(Event.Interrupt) }
    fun pauseLive() = stopLocally(LiveLocalStopReason.Backgrounded)
    fun toggleMicrophoneMute() { events.trySend(Event.ToggleMicrophoneMute) }
    fun toggleOutputMute() { events.trySend(Event.ToggleOutputMute) }

    /** Passive safety stop. Only explicit End and Interrupt authorize a remote cancellation. */
    fun stopLocally(reason: LiveLocalStopReason) {
        publishPrivacyStop(reason, Event.LocalStop(reason), onlyWhenActive = true)
    }

    /** Parent forwards the actual streaming assistant text associated with the active request. */
    fun updateAssistantText(requestId: String, text: String) { events.trySend(Event.ReplyText(requestId, text)) }
    fun updateThinkingTitle(requestId: String, title: String) {
        events.trySend(Event.ThinkingTitle(requestId, com.kompi.app.stream.ActivityCueText.thinkingTitle(title)))
    }
    fun updateActivity(requestId: String, label: String) {
        events.trySend(Event.Activity(requestId, com.kompi.app.stream.ActivityCueText.activity(label)))
    }
    fun onAssistantPreparing(requestId: String, phrase: String) { events.trySend(Event.ReplyPreparing(requestId, phrase)) }
    /** Only call from Kokoro's actual PCM/AudioTrack state flow. */
    fun onAssistantPlaying(requestId: String, phrase: String, actualPcmRms: Float, waveform: AudioWaveform, readAlong: LiveReadAlong = LiveReadAlong.Reply(0, phrase)) {
        events.trySend(Event.ReplyPlaying(requestId, phrase, actualPcmRms, waveform, readAlong))
    }
    /** Adapter for the engine-owned phrase/audio-track state; no synthesized word timing is exposed. */
    fun onAssistantPlayback(phase: KokoroPlaybackPhase, thinkingOwner: com.kompi.app.stream.ActivitySpeech.PlaybackOwner? = null) {
        when (phase) {
            is KokoroPlaybackPhase.Preparing -> onAssistantPreparing(thinkingOwner?.requestId ?: phase.requestId, phase.phrase)
            is KokoroPlaybackPhase.Playing -> onAssistantPlaying(thinkingOwner?.requestId ?: phase.requestId, phase.phrase, phase.pcmRms, phase.waveform,
                thinkingOwner?.let { LiveReadAlong.Thinking(it.title) } ?: LiveReadAlong.Reply(phase.sequence, phase.phrase))
            is KokoroPlaybackPhase.Draining -> events.trySend(Event.ReplyDraining(thinkingOwner?.requestId ?: phase.requestId, phase.phrase))
            KokoroPlaybackPhase.Idle -> events.trySend(Event.ReplyIdle)
        }
    }
    /** Call only after Kokoro reports Completed (all queued phrases drained). */
    fun onAssistantPlaybackDrained(requestId: String) { events.trySend(Event.ReplyDrained(requestId)) }
    fun onConnectionLost() = stopLocally(LiveLocalStopReason.ConnectionLost)

    /** Project/peer changes invalidate callbacks; the caller decides when the new context is active. */
    fun onContextChanged(newContextKey: String) {
        synchronized(audioOwnershipLock) {
            val previous = requestedContextKey
            requestedContextKey = newContextKey
            if (previous == null || previous == newContextKey) {
                events.trySend(Event.ContextChanged(newContextKey))
                return
            }
        }
        publishPrivacyStop(LiveLocalStopReason.Backgrounded, Event.ContextChanged(newContextKey))
    }
    /** Parent confirms durable chat ownership; keep the Live caption until the next capture begins. */
    fun onTurnCommitted(requestId: String) { events.trySend(Event.TurnCommitted(requestId)) }
    fun retryPendingTurn() { events.trySend(Event.RetryPending(activation)) }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) stopLocally(LiveLocalStopReason.Backgrounded)
    }

    private suspend fun reduce(event: Event) {
        if (closed) return
        when (event) {
            is Event.Start -> {
                if (liveRequested || event.activation != activation) return
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    update { it.copy(state = LiveConversationState.Failure("Live voice requires an active screen or assistant service.")) }
                    return
                }
                // A new gesture owns a fresh session; never borrow an ended input or old output.
                invalidateAndStop(joinDecoder = false, terminal = LiveConversationState.Idle)
                val session = synchronized(audioOwnershipLock) {
                    // An End/connection stop may have arrived while old cleanup was suspended.
                    if (closed || event.activation != activation) return
                    liveRequested = true
                    generation++
                    ++sessionGeneration
                }
                turnOwnership++
                setOutputMuted(false)
                update { LiveConversationSnapshot(state = LiveConversationState.Preparing) }
                try {
                    if (openOwnedAudioSession(session)) startInput(generation)
                } catch (error: Throwable) {
                    events.trySend(Event.Failure(error.message ?: "Could not prepare local audio. Check your route and start Live again.", generation))
                }
            }
            is Event.End -> {
                liveRequested = false
                val failure = invalidateAndStop(joinDecoder = event.joinDecoder, terminal = LiveConversationState.Idle, cancelAcceptedTurn = true)
                onLiveEnded()
                if (failure == null) event.completion?.complete(Unit)
                else event.completion?.completeExceptionally(failure)
            }
            is Event.LocalStop -> {
                liveRequested = false
                val terminal = when (val reason = event.reason) {
                    LiveLocalStopReason.Backgrounded -> LiveConversationState.Idle
                    LiveLocalStopReason.ConnectionLost -> LiveConversationState.Paused
                    LiveLocalStopReason.FocusLost -> LiveConversationState.Failure("Audio focus was lost. Start Live again when other audio has finished.")
                    LiveLocalStopReason.RouteLost -> LiveConversationState.Failure("The audio route changed. Check your headphones or speaker route, then start Live again.")
                    is LiveLocalStopReason.EngineFailure -> LiveConversationState.Failure(reason.message)
                }
                val failure = invalidateAndStop(event.joinDecoder, terminal, clearPending = false)
                if (failure == null) event.completion?.complete(Unit)
                else event.completion?.completeExceptionally(failure)
            }
            Event.ToggleMicrophoneMute -> {
                if (!liveRequested) return
                if (!snapshot.value.microphoneMuted) {
                    // Publish explicit user intent BEFORE our own stop can make Android report
                    // an inactive communication owner. Logical Live/focus/APM remain retained;
                    // physical mode ownership may become quiescent only after actual cleanup.
                    synchronized(audioOwnershipLock) { audioSession }?.setMicPermission(
                        LiveAudioSession.MicPermission.UserMuted(generation + 1))
                    val failure = stopCapture(joinDecoder = false)
                    if (failure != null) {
                        liveRequested = false
                        invalidateAndStop(false, LiveConversationState.Failure("Microphone teardown failed. Start Live again after capture releases."))
                    } else update { it.copy(microphoneMuted = true,
                        transcriptionStatus = LiveTranscriptionStatus.Inactive,
                        caption = if (activeRequestId == null || it.inputCaptionActive) LiveCaption.Empty else it.caption,
                        inputCaptionActive = false,
                        state = if (activeRequestId == null) LiveConversationState.Listening else it.state,
                        micLevel = AudioLevel.Quiet, micWaveform = AudioWaveform.Silence) }
                } else {
                    update { it.copy(microphoneMuted = false) }
                    startInput(++generation)
                }
            }
            Event.ToggleOutputMute -> {
                if (!liveRequested) return
                val muted = !snapshot.value.outputMuted
                if (muted) audibleRequestId = null // Unmuting never readmits this turn's old audio.
                setOutputMuted(muted)
                update { it.copy(outputMuted = muted,
                    state = if (muted && activeRequestId != null && it.state is LiveConversationState.Speaking) LiveConversationState.Waiting(activeRequestId!!) else it.state,
                    playingPhrase = "", readAlong = null, playbackLevel = AudioLevel.Quiet,
                    playbackWaveform = AudioWaveform.Silence) }
            }
            is Event.SpeechStarted -> {
                if (!liveRequested || event.generation != generation || snapshot.value.microphoneMuted) return
                if (latestUtterance?.let { event.utteranceId <= it } == true) return
                latestUtterance = event.utteranceId
                pendingBargeInUtterance = event.utteranceId.takeIf { activeRequestId != null }
                pendingBargeInCaption = null
                if (activeRequestId == null) {
                    turnOwnership++ // Fence any retired output before this new local point.
                    update { it.copy(state = LiveConversationState.Listening, caption = LiveCaption.Empty, inputCaptionActive = false, playingPhrase = "", readAlong = null, playbackLevel = AudioLevel.Quiet) }
                }
            }
            is Event.BargeInConfirmed -> {
                if (!liveRequested || event.generation != generation || snapshot.value.microphoneMuted ||
                    event.utteranceId != latestUtterance || event.utteranceId != pendingBargeInUtterance) return
                pendingBargeInUtterance = null
                val pendingCaption = pendingBargeInCaption
                    ?: snapshot.value.caption.takeIf { snapshot.value.state == LiveConversationState.Recognizing }
                    ?: LiveCaption.Empty
                pendingBargeInCaption = null
                turnOwnership++ // The new point cannot be stopped by the retired turn's cancellation.
                interruptCurrentReply("speech")
                update { it.copy(state = if (pendingCaption.committedText.isNotBlank() || pendingCaption.pendingText.isNotBlank())
                    LiveConversationState.Recognizing else LiveConversationState.Listening,
                    caption = pendingCaption, inputCaptionActive = pendingCaption.visibleText.isNotBlank(),
                    playingPhrase = "", readAlong = null, playbackLevel = AudioLevel.Quiet) }
            }
            Event.Interrupt -> {
                if (!liveRequested) return
                pendingBargeInUtterance = null
                pendingBargeInCaption = null
                turnOwnership++
                interruptCurrentReply("manual")
                update { it.copy(state = LiveConversationState.Listening, caption = LiveCaption.Empty, inputCaptionActive = false, playingPhrase = "", readAlong = null, playbackLevel = AudioLevel.Quiet) }
            }
            is Event.Partial -> {
                if (!liveRequested || event.generation != generation || snapshot.value.microphoneMuted || event.utteranceId != latestUtterance) return
                if (lastFinalUtterance?.let { event.utteranceId <= it } == true) return
                val caption = LiveCaption.fromWhistle(event.committed, event.pending, event.language)
                android.util.Log.i("KompiLiveAsr", "phase=caption_published utterance=${event.utteranceId.value} main_ms=${(System.nanoTime() - event.publishedAtNanos) / 1_000_000} committed_characters=${caption.committedText.length} pending_characters=${caption.pendingText.length}")
                if (event.utteranceId == pendingBargeInUtterance && activeRequestId != null) {
                    // Actual partial words replace the reply visually, without cancelling it.
                    // Independent barge-in hysteresis remains the only early cancel authority.
                    val shown = caption.takeIf { it.visibleText.isNotBlank() } ?: LiveCaption.Empty
                    pendingBargeInCaption = shown
                    update { it.copy(caption = shown, inputCaptionActive = shown.visibleText.isNotBlank()) }
                    return
                }
                update {
                    if (caption.visibleText.isBlank()) it.copy(
                        state = if (activeRequestId == null) LiveConversationState.Listening else it.state,
                        caption = LiveCaption.Empty,
                        inputCaptionActive = false,
                    ) else it.copy(state = LiveConversationState.Recognizing, caption = caption, inputCaptionActive = true)
                }
            }
            is Event.Final -> {
                if (!liveRequested || event.generation != generation || snapshot.value.microphoneMuted) return
                if (lastFinalUtterance?.let { event.utteranceId <= it } == true) return
                lastFinalUtterance = event.utteranceId
                val inputStatus = snapshot.value.transcriptionStatus
                if (inputStatus is LiveTranscriptionStatus.Utterance && inputStatus.id == event.utteranceId) {
                    update { it.copy(transcriptionStatus = LiveTranscriptionStatus.Listening(inputStatus.engine)) }
                }
                if (event.utteranceId == pendingBargeInUtterance) {
                    pendingBargeInUtterance = null
                    pendingBargeInCaption = null
                }
                if (deferredUserText.length + event.text.length + (if (deferredUserText.isEmpty() || event.text.isEmpty()) 0 else 1) > LiveVoiceLimits.MAX_TURN_CHARS) {
                    liveRequested = false
                    invalidateAndStop(false, LiveConversationState.Failure("That speech segment is too long. Try a shorter point."))
                    return
                }
                if (event.utteranceId != latestUtterance) {
                    // Another point started before JNI finished this one. Preserve its words, but
                    // do not dispatch an already-obsolete agent turn while the user is still talking.
                    if (event.text.isNotEmpty()) {
                        if (deferredUserText.isNotEmpty()) deferredUserText.append('\n')
                        deferredUserText.append(event.text)
                    }
                    return
                }
                val text = when {
                    deferredUserText.isEmpty() -> event.text
                    event.text.isEmpty() -> deferredUserText.toString()
                    else -> "$deferredUserText\n${event.text}"
                }
                deferredUserText.setLength(0)
                val turn = LiveTurn.create(text)
                if (turn == null) {
                    // Empty follow-up candidates still release utterance ownership; otherwise
                    // a previous real point deferred behind them could remain stranded forever.
                    update { it.copy(
                        state = if (activeRequestId == null) LiveConversationState.Listening else it.state,
                        caption = if (it.inputCaptionActive) LiveCaption.Empty else it.caption,
                        inputCaptionActive = false,
                    ) }
                    return
                }
                if (!submittedTurnIds.add(turn.requestId)) return
                interruptCurrentReply("speech")
                while (submittedTurnIds.size > 64) submittedTurnIds.remove(submittedTurnIds.first())
                turnOwnership++
                activeRequestId = turn.requestId
                audibleRequestId = turn.requestId.takeUnless { snapshot.value.outputMuted }
                pendingTurn = turn
                update { it.copy(state = LiveConversationState.Waiting(turn.requestId), caption = LiveCaption.fromWhistle(text, "", event.language), inputCaptionActive = false, assistantText = "", playingPhrase = "", replyRequestId = turn.requestId, thinkingTitles = emptyList(), latestActivity = "Sending…", readAlong = null, playbackLevel = AudioLevel.Quiet, recoverableTurn = null) }
                // The same echo-controlled microphone remains open across every reply.
                android.util.Log.i("KompiLiveAsr", "phase=final_dispatch utterance=${event.utteranceId.value} main_ms=${(System.nanoTime() - event.publishedAtNanos) / 1_000_000} characters=${turn.text.length}")
                enqueueTurn(turn)
            }
            is Event.TranscriptionStatus -> {
                if (!liveRequested || event.generation != generation || snapshot.value.microphoneMuted) return
                val status = event.status
                if (status is LiveTranscriptionStatus.Utterance && (status.id != latestUtterance ||
                        lastFinalUtterance?.let { status.id <= it } == true)) return
                update { it.copy(transcriptionStatus = status,
                    state = if (status is LiveTranscriptionStatus.Listening && activeRequestId == null && it.state == LiveConversationState.Preparing)
                        LiveConversationState.Listening else it.state) }
            }
            Event.MeterAvailable -> pendingMeter.getAndSet(null)?.let { reduce(it) }
            is Event.Meter -> {
                if (event.phase == CapturePhase.Recognizing) return
                if (liveRequested && event.generation == generation && !snapshot.value.microphoneMuted) {
                    update { it.copy(
                        // Metering never turns a real Recognizing/Finalizing state back into
                        // Listening, nor conflates reply preparation with input readiness.
                        state = if (activeRequestId == null && it.state == LiveConversationState.Preparing)
                            LiveConversationState.Listening else it.state,
                        micLevel = AudioLevel.normalized(event.rms),
                        micWaveform = event.waveform,
                    ) }
                }
            }
            is Event.Failure -> {
                if (!liveRequested || (event.generation != null && event.generation != generation)) return
                liveRequested = false
                invalidateAndStop(joinDecoder = false, terminal = LiveConversationState.Failure(event.reason.take(240)), clearPending = false)
            }
            is Event.ReplyText -> if (event.requestId == activeRequestId) update { it.copy(assistantText = event.text) }
            is Event.ThinkingTitle -> if (event.requestId == activeRequestId) update { it.copy(thinkingTitles = (it.thinkingTitles + event.title).takeLast(32), latestActivity = event.title) }
            is Event.Activity -> if (event.requestId == activeRequestId) update { it.copy(latestActivity = event.label) }
            is Event.ReplyPreparing -> if (liveRequested && event.requestId == audibleRequestId && event.requestId == activeRequestId) update { it.copy(state = LiveConversationState.Preparing, playingPhrase = "", readAlong = null, playbackLevel = AudioLevel.Quiet) }
            is Event.ReplyPlaying -> if (liveRequested && event.requestId == audibleRequestId && event.requestId == activeRequestId) update { it.copy(state = LiveConversationState.Speaking(event.requestId), playingPhrase = event.phrase.take(220), readAlong = event.readAlong, playbackLevel = AudioLevel.normalized(event.rms), playbackWaveform = event.waveform) }
            is Event.ReplyDraining -> if (liveRequested && event.requestId == audibleRequestId && event.requestId == activeRequestId) update { it.copy(state = LiveConversationState.Speaking(event.requestId), playingPhrase = event.phrase.take(220), playbackLevel = AudioLevel.Quiet) }
            // Idle has no request identity. A late global idle must not reset a replacement turn;
            // request-owned drain callbacks clear progress below.
            Event.ReplyIdle -> Unit
            is Event.ReplyDrained -> if (event.requestId == activeRequestId && liveRequested) {
                activeRequestId = null
                audibleRequestId = null
                update {
                    if (it.inputCaptionActive) it.copy(
                        state = LiveConversationState.Recognizing,
                        playingPhrase = "",
                        readAlong = null,
                        playbackLevel = AudioLevel.Quiet,
                    ) else it.copy(
                        state = LiveConversationState.Listening,
                        caption = LiveCaption.Empty,
                        inputCaptionActive = false,
                        playingPhrase = "",
                        readAlong = null,
                        playbackLevel = AudioLevel.Quiet,
                    )
                }
            }
            is Event.Sent -> if (event.session == sessionGeneration && event.attempt == deliveryAttempt && event.turn.requestId == activeRequestId) {
                // Durable admission is not playback progress. Speaking/recognition already owns
                // the visual state; a delayed acknowledgement may update only delivery feedback.
                update { it.copy(latestActivity = if (it.latestActivity == "Sending…") "Waiting for agent…" else it.latestActivity) }
            }
            is Event.SendFailed -> if (event.session == sessionGeneration && event.attempt == deliveryAttempt && event.turn.requestId == activeRequestId) {
                liveRequested = false
                val failure = invalidateAndStop(false, LiveConversationState.Failure("Not sent · ${event.reason.take(200)}"), clearPending = false)
                update { it.copy(state = if (failure == null) it.state else LiveConversationState.Failure("Microphone teardown failed. Capture remains disabled."),
                    caption = LiveCaption.fromWhistle(event.turn.text, "", null), recoverableTurn = pendingTurn) }
            }
            is Event.CancellationFailed -> if (liveRequested && event.session == sessionGeneration) {
                if (event.owner == turnOwnership && activeRequestId == null) {
                    liveRequested = false
                    invalidateAndStop(false, LiveConversationState.Failure("Cancellation was not confirmed. The command may already have run. Check the conversation before continuing."))
                } else {
                    // Preserve the replacement's capture, words and playback. Uncertainty about
                    // an earlier request is not authorization to revoke this one.
                    update { it.copy(latestActivity = "Cancellation of the previous turn was not confirmed; its command may already have run.") }
                }
            }
            is Event.ContextChanged -> when {
                contextKey == null -> contextKey = event.key // Bind the initial project without implying an interruption.
                contextKey != event.key -> {
                    contextKey = event.key
                    liveRequested = false
                    invalidateAndStop(joinDecoder = false, terminal = LiveConversationState.Interrupted)
                }
            }
            is Event.TurnCommitted -> if (pendingTurn?.requestId == event.requestId) {
                pendingTurn = null
                update { it.copy(recoverableTurn = null) }
            }
            is Event.RetryPending -> snapshot.value.recoverableTurn?.let { turn ->
                if (event.activation != activation || turn != pendingTurn || liveRequested || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                val session = synchronized(audioOwnershipLock) {
                    if (closed || event.activation != activation) return
                    liveRequested = true
                    ++sessionGeneration
                }
                turnOwnership++
                activeRequestId = turn.requestId
                audibleRequestId = turn.requestId.takeUnless { snapshot.value.outputMuted }
                update { it.copy(state = LiveConversationState.Waiting(turn.requestId), recoverableTurn = null) }
                try {
                    if (!openOwnedAudioSession(session)) return
                    startInput(++generation)
                    if (liveRequested && session == sessionGeneration) enqueueTurn(turn)
                } catch (error: Throwable) {
                    events.trySend(Event.Failure(error.message ?: "Could not prepare local audio. Start Live again.", generation))
                }
            }
        }
    }

    private fun interruptCurrentReply(source: String) {
        val request = activeRequestId ?: return
        com.kompi.app.diagnostics.SpeechAudit.record(request, stage = "turn_interrupted", detail = "source=$source")
        val started = System.nanoTime()
        activeRequestId = null // Fence late text, PCM and completion before cancelling anything.
        audibleRequestId = null
        if (pendingTurn?.requestId == request) pendingTurn = null
        update { it.copy(recoverableTurn = null) }
        stopLocalSpeech(request)
        val session = sessionGeneration
        val owner = turnOwnership
        scope.launch {
            runCatching { cancelRemote(request) }.onFailure {
                events.trySend(Event.CancellationFailed(request, session, owner))
            }
        }
        android.util.Log.i("KompiLiveVoice", "Interruption: source=$source stopDispatchMs=${(System.nanoTime() - started) / 1_000_000}")
    }

    private fun enqueueTurn(turn: LiveTurn) {
        // The parent adapter persists this exact ID before network transmission; retryPendingTurn reuses it.
        val session = sessionGeneration
        val attempt = ++deliveryAttempt
        scope.launch {
            val started = System.nanoTime()
            try {
                sendTurn(turn.requestId, turn.text)
                android.util.Log.i("KompiLiveAsr", "phase=durable_admission elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
                events.trySend(Event.Sent(turn, session, attempt))
            } catch (error: Throwable) { events.trySend(Event.SendFailed(turn, error.message ?: "Turn could not be queued", session, attempt)) }
        }
    }

    private suspend fun openOwnedAudioSession(session: Long): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        // OS route/focus setup can use Binder; neither it nor disposal holds the publication lock.
        // Publish-or-close INSIDE the non-cancellable owner task: disposal during Binder startup
        // must not discard an opened lease at a cancellable dispatcher-return boundary.
        val opened = openAudioSession { reason ->
            if (session == sessionGeneration && liveRequested) stopLocally(reason)
        }
        val installed = synchronized(audioOwnershipLock) {
            if (closed || !liveRequested || session != sessionGeneration) false
            else { audioSession = opened; true }
        }
        if (!installed) {
            opened?.fenceHardware(LiveLocalStopReason.Backgrounded)
            opened?.close()
        }
        installed
    }

    private fun startInput(token: Long) {
        val speechMetricKey = token.toString()
        LocalDiagnostics.begin(LocalDiagnostics.Metric.SpeechStartup, speechMetricKey)
        val session = sessionGeneration
        val lease = synchronized(audioOwnershipLock) { audioSession }
        lease?.setMicPermission(LiveAudioSession.MicPermission.Allowed(token))
        val input = try { createSpeechInput(lease) }
        catch (error: Throwable) {
            events.trySend(Event.Failure(error.message ?: "Speech recognition is unavailable on this device", token)); return
        }
        val installed = synchronized(audioOwnershipLock) {
            if (closed || !liveRequested || token != generation || session != sessionGeneration) false
            else { speechInput = input; true }
        }
        if (!installed) {
            input.requestStopAndFence()
            input.close()
            return
        }
        try {
            input.start(object : LiveSpeechInput.Listener {
                override fun onSpeechStarted(utteranceId: SpeechUtteranceId) {
                    if (token == generation && liveRequested) events.trySend(Event.SpeechStarted(utteranceId, token))
                }
                override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) {
                    if (token == generation && liveRequested) events.trySend(Event.BargeInConfirmed(utteranceId, token))
                }
                override fun onPartial(utteranceId: SpeechUtteranceId, committedText: String, pendingText: String, language: String?) {
                    LocalDiagnostics.finish(LocalDiagnostics.Metric.SpeechStartup, speechMetricKey)
                    if (token == generation && liveRequested) events.trySend(Event.Partial(utteranceId, committedText, pendingText, language, token))
                }
                override fun onFinal(utteranceId: SpeechUtteranceId, text: String, language: String?) {
                    LocalDiagnostics.finish(LocalDiagnostics.Metric.SpeechStartup, speechMetricKey)
                    if (token == generation && liveRequested) events.trySend(Event.Final(utteranceId, text, language, token))
                }
                override fun onEmptyFinal(utteranceId: SpeechUtteranceId) {
                    if (token == generation && liveRequested) events.trySend(Event.Final(utteranceId, "", null, token))
                }
                override fun onTranscriptionStatus(status: LiveTranscriptionStatus) {
                    if (token == generation && liveRequested) events.trySend(Event.TranscriptionStatus(status, token))
                }
                override fun onMeter(rms: Float, phase: CapturePhase, waveform: AudioWaveform) {
                    if (token == generation && liveRequested && phase == CapturePhase.Listening &&
                        pendingMeter.getAndSet(Event.Meter(rms, phase, token, waveform)) == null) {
                        events.trySend(Event.MeterAvailable)
                    }
                }
                override fun onLocalStop(reason: LiveLocalStopReason) {
                    LocalDiagnostics.finish(LocalDiagnostics.Metric.SpeechStartup, speechMetricKey)
                    if (token == generation && session == sessionGeneration && liveRequested) stopLocally(reason)
                }
            })
        } catch (error: Throwable) {
            LocalDiagnostics.finish(LocalDiagnostics.Metric.SpeechStartup, speechMetricKey)
            events.trySend(Event.Failure(error.message ?: "Could not start local microphone", token))
        }
    }

    private suspend fun stopCapture(joinDecoder: Boolean): Throwable? {
        val input = synchronized(audioOwnershipLock) {
            generation++
            speechInput.also { speechInput = null }
        }
        latestUtterance = null
        pendingBargeInUtterance = null
        pendingBargeInCaption = null
        lastFinalUtterance = null
        deferredUserText.setLength(0)
        return try {
            input?.requestStopAndFence()
            withContext(NonCancellable) {
                if (joinDecoder) input?.closeAndJoin() else input?.stopMicrophoneAndFence()
            }
            null
        } catch (error: Throwable) { error }
    }

    private suspend fun invalidateAndStop(
        joinDecoder: Boolean,
        terminal: LiveConversationState,
        cancelAcceptedTurn: Boolean = false,
        clearPending: Boolean = true,
    ): Throwable? {
        val owned = synchronized(audioOwnershipLock) {
            sessionGeneration++
            OwnedAudio(speechInput, audioSession)
        }
        owned.session?.fenceHardware(LiveLocalStopReason.Backgrounded)
        owned.input?.requestStopAndFence()
        turnOwnership++
        val request = activeRequestId
        activeRequestId = null
        audibleRequestId = null
        // Also fence optional cues and unowned prefetched audio when there was no active reply.
        stopLocalSpeech(request)
        setOutputMuted(false)
        if (cancelAcceptedTurn && request != null) scope.launch { runCatching { cancelRemote(request) } }
        if (clearPending) pendingTurn = null
        val failure = stopCapture(joinDecoder)
        val sessionToClose = synchronized(audioOwnershipLock) { audioSession.also { audioSession = null } }
        withContext(NonCancellable + Dispatchers.IO) { sessionToClose?.close() }
        val recoverable = pendingTurn
        update {
            LiveConversationSnapshot(
                state = failure?.let { LiveConversationState.Failure("Microphone teardown failed. Capture remains disabled until it releases.") } ?: terminal,
                caption = recoverable?.let { LiveCaption.fromWhistle(it.text, "", null) } ?: LiveCaption.Empty,
                recoverableTurn = recoverable,
            )
        }
        return failure
    }

    private inline fun update(transform: (LiveConversationSnapshot) -> LiveConversationSnapshot) {
        mutableSnapshot.value = transform(mutableSnapshot.value)
    }

    override fun close() {
        val owned = synchronized(audioOwnershipLock) {
            if (closed) return
            closed = true
            activation++
            liveRequested = false
            generation++
            sessionGeneration++
            OwnedAudio(speechInput, audioSession).also { speechInput = null; audioSession = null }
        }
        owned.session?.fenceHardware(LiveLocalStopReason.Backgrounded)
        owned.input?.requestStopAndFence()
        lifecycleOwner.lifecycle.removeObserver(this)
        // Disposal/background is a local privacy stop, not an explicit remote cancellation.
        val request = activeRequestId
        activeRequestId = null
        audibleRequestId = null
        pendingTurn = null
        stopLocalSpeech(request)
        setOutputMuted(false)
        runCatching { owned.input?.close() }
        deferredUserText.setLength(0)
        pendingBargeInUtterance = null
        pendingBargeInCaption = null
        events.close()
        if (owned.session == null) scope.cancel()
        else scope.launch(NonCancellable + Dispatchers.IO) {
            try { owned.session.close() } finally { scope.cancel() }
        }
        onLiveEnded()
    }

}

