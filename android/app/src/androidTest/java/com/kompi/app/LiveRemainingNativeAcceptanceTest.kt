package com.kompi.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.media.AudioTrack
import android.os.Debug
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.audio.*
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.diagnostics.SpeechAudit
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** SoundPool's resource load fails for the optional earcon only. This does not replace AudioRecord,
 * decoder, AEC, route, controller, outbox, Kokoro or AudioTrack with scripted success callbacks. */
class LiveReadyCueFailureAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: NativeLiveAudioHarness
    private lateinit var audio: EmulatorAudioBridge
    private val rejectedLoads = AtomicInteger()

    @Suppress("DEPRECATION")
    @Before fun mount() {
        val base = compose.activity.applicationContext
        val cueResources = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun openRawResourceFd(id: Int): AssetFileDescriptor {
                if (id == R.raw.live_ready) {
                    rejectedLoads.incrementAndGet()
                    throw Resources.NotFoundException("Test-only optional ready-cue resource failure")
                }
                return super.openRawResourceFd(id)
            }
        }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getResources(): Resources = cueResources
        }
        val model = runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(base) }
        audio = EmulatorAudioBridge()
        h = NativeLiveAudioHarness(compose) { session ->
            LocalLiveSpeechInput(context, compose.activity, SpeechRecognitionModel.Parakeet(model), audioSession = session)
        }
        h.mount()
    }
    @After fun cleanup() { if (::h.isInitialized) h.close(); if (::audio.isInitialized) audio.close() }

    @Test fun l07_failedOptionalReadyCueDoesNotDisableActualRecognitionAndEndReleasesCapture() {
        audio.startCapture()
        h.start()
        h.await("Actual capture must remain usable after the optional SoundPool load fault") {
            h.snapshot.state == LiveConversationState.Listening || h.snapshot.state is LiveConversationState.Failure
        }
        audio.inject("silence")
        assertEquals("The intended production resource load must actually fail", 1, rejectedLoads.get())
        assertEquals("A failed earcon must not play or retry on subsequent input frames", 0, audio.stopCapture().getInt("nonzero"))
        audio.inject("review")
        h.await("Real selected-model recognition must submit despite the cue failure") { h.submissions.size == 1 }
        h.assertWords(LiveAudioCorpus.fixtures.single { it.id == "review" }, h.submissions.single().text)
        h.reply("The optional sound did not disable this answer.")
        h.drained(h.submissions.single().requestId)
        h.main { h.controller.endLive() }
        runBlocking { withTimeout(15_000) { h.inputs.single().closeAndJoin() } }
        assertTrue(compose.activity.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertTrue("The process-wide native capture lease must be free", MicCaptureCoordinator.awaitIdle(2_000))
        assertEquals(1, rejectedLoads.get())
        assertEquals(0, audio.quietOutputWindow().getInt("nonzeroAfter200ms"))
    }
}

/** Opt-in native/model suite. Requires the prepared corpus, authenticated SDK virtual-audio bridge
 * and configured API-35 AEC guest. Generated English sources are not human/physical certification. */
internal class LiveRemainingNativeAcceptanceTest : NativeMicrophoneScenario() {
    // Read-only observation of the actual engine's pinned request metadata, which is not exposed in
    // its public status flow. No native object, voice, queue, timing or completion is mutated.
    private fun pinnedVoice(request: String): KokoroVoiceId {
        val type = KokoroTtsEngine::class.java
        val lock = type.getDeclaredField("stateLock").apply { isAccessible = true }.get(h.tts)
        return synchronized(lock) {
            val states = type.getDeclaredField("requestStates").apply { isAccessible = true }.get(h.tts) as Map<*, *>
            val state = states[request] ?: error("Actual engine did not admit the request")
            when (val voice = state.javaClass.getDeclaredField("voiceId").apply { isAccessible = true }.get(state)) {
                is KokoroVoiceId -> voice
                is String -> KokoroVoiceId.fromStored(voice) ?: error("Invalid native request voice metadata")
                else -> error("Actual request has no pinned voice")
            }
        }
    }

    @Test fun l33_generatedOtherVoiceAndNonSpeechBurstMeasureOnsetsWithoutSpeakerAuthenticationClaim() {
        startListening(); spoken("review", 1)
        val first = h.submissions.single().requestId
        h.reply("This long answer continues while the virtual room receives a short non speech burst, followed by another synthetic voice asking to review the changes.")
        h.await("Native answer must be playing before the measured stimuli") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        audio.inject("tone-burst")
        assertEquals("Specified sub-onset burst is not an instruction", 1, onsets.size)
        assertEquals(1, h.submissions.size)
        assertTrue(h.cancellations.isEmpty())
        spoken("review-male", 2)
        h.await("A different generated speaker can interrupt; Live does not authenticate speakers") {
            h.tts.requestStatuses.value[first]?.state == KokoroSpeechStatus.Cancelled
        }
        assertEquals(2, onsets.size)
        assertEquals(2, h.submissions.map { it.requestId }.toSet().size)
        assertEquals(listOf(first), h.cancellations)
        h.reply("The second point is retained."); h.drained(h.submissions.last().requestId)
        println("LIVE_L33 stimuli=specified_80ms_tone_then_generated_male_voice onsets=${onsets.size} submissions=${h.submissions.size} cancellations=${h.cancellations.size} coughFanTvOverlap=not_measured speakerAuthentication=false")
    }

    @Test fun l50_realPrefetchedRequestPinsVoiceWhileNewSelectionAppliesToNextRequest() {
        runBlocking { withTimeout(90_000) { h.tts.initialize().getOrThrow() } }
        val initial = h.tts.selectedVoice.value ?: error("Pinned Kokoro catalog did not initialize")
        val alternate = h.tts.availableVoices.value.firstOrNull { it.id != initial.id }
            ?: error("Acceptance needs the pinned bundle's second voice; no fallback or skip")
        startListening(); spoken("continue", 1)
        val first = h.submissions.single().requestId
        h.reply("The first phrase uses the admitted voice. The second phrase must keep that voice even when a new voice is selected while this answer plays.")
        h.await("Actual first request must enter PCM playback") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        assertEquals(initial.id, pinnedVoice(first))
        assertTrue(h.tts.selectVoice(alternate.id))
        assertEquals(alternate.id, h.tts.selectedVoice.value?.id)
        assertEquals("Settings cannot retarget admitted/prefetched speech", initial.id, pinnedVoice(first))
        h.drained(first)
        spoken("continue", 2)
        val second = h.submissions.last().requestId
        h.reply("The next deliberate answer uses the newly selected voice.")
        h.await("Next real output admission must own the selected voice") { h.tts.requestStatuses.value.containsKey(second) }
        assertEquals(alternate.id, pinnedVoice(second))
        h.drained(second)
        assertTrue(h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == first && it.sequence > 0 })
        assertTrue(h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == second })
        println("LIVE_L50 firstVoice=${initial.id.value} subsequentVoice=${alternate.id.value} observation=real_engine_pinned_metadata_plus_native_playout localeSwitch=not_separately_measured")
    }

    @Test fun l71_actualLiveDictationLiveHandoffJoinsNativeOwnersAndPreservesNewWords() {
        runNativeDictationHandoff(compose, h, audio, NativeDictationHandoffTiming.AwaitNativeEndpoint,
            ::startListening, ::spoken, ::microphoneStopped)
    }

    @Test fun l96_realColdAndWarmStagesReportMonotonicResourcesUnderBoundedCpuPressure() {
        val start = SystemClock.elapsedRealtimeNanos()
        startListening()
        val ready = SystemClock.elapsedRealtimeNanos()
        val pressure = AtomicBoolean(true)
        val workers = List(2) {
            Thread({ var value = 0.1; while (pressure.get()) { repeat(2000) { value = kotlin.math.sin(value + it) }; Thread.yield() } }, "live-acceptance-cpu-pressure").apply { start() }
        }
        try {
            repeat(3) { index ->
                val injection = SystemClock.elapsedRealtimeNanos()
                spoken("continue", index + 1)
                val observedSubmission = SystemClock.elapsedRealtimeNanos()
                val request = h.submissions.last().requestId
                val replyStart = SystemClock.elapsedRealtimeNanos()
                h.reply("A short measured response for turn ${index + 1}.")
                h.await("Real native PCM must be observed under bounded pressure") {
                    h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == request }
                }
                val observedPcm = SystemClock.elapsedRealtimeNanos()
                h.drained(request)
                val observedDrain = SystemClock.elapsedRealtimeNanos()
                val onset = onsets[index]
                assertTrue(start <= ready && injection <= onset && onset <= observedSubmission && observedSubmission <= replyStart && replyStart <= observedPcm && observedPcm <= observedDrain)
                assertEquals(1, liveRecordings().size)
                recordLiveNumericObservation("L96", "two_bounded_cpu_workers",
                    "sample" to index, "startNanos" to start, "readyNanos" to ready,
                    "injectionNanos" to injection, "onsetNanos" to onset,
                    "observedSubmissionNanos" to observedSubmission, "replyStartNanos" to replyStart,
                    "observedPcmNanos" to observedPcm, "observedDrainNanos" to observedDrain,
                    "startToReadyMs" to (ready - start) / 1_000_000,
                    "injectToObservedSubmissionMs" to (observedSubmission - injection) / 1_000_000,
                    "onsetToObservedSubmissionMs" to (observedSubmission - onset) / 1_000_000,
                    "replyCallToObservedPcmMs" to (observedPcm - replyStart) / 1_000_000,
                    "pcmToObservedDrainMs" to (observedDrain - observedPcm) / 1_000_000,
                    "pssKb" to Debug.getPss(), "nativeHeapBytes" to Debug.getNativeHeapAllocatedSize(),
                    "processCpuMs" to android.os.Process.getElapsedCpuTime(), "threads" to Thread.getAllStackTraces().size,
                    "thermal" to compose.activity.getSystemService(android.os.PowerManager::class.java).currentThermalStatus,
                    "networkPressureInjected" to 0, "batteryCertification" to 0)
            }
        } finally { pressure.set(false); workers.forEach { it.join(3_000); assertFalse("Bounded pressure worker must stop", it.isAlive) } }
    }

    @Test fun l98_lastRealPhraseReportsCompletionOnlyAfterPlaybackHeadAndDrain() {
        SpeechAudit.initialize(h.context)
        startListening(); spoken("continue", 1)
        val request = h.submissions.single().requestId
        h.reply("The first sentence is admitted. The last spoken sentence must reach the actual playback head and drain before completion is reported.")
        h.await("Actual output must enter playback") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        val activeTrackField = KokoroTtsEngine::class.java.getDeclaredField("activeTrack").apply { isAccessible = true }
        val track = activeTrackField.get(h.tts) as AudioTrack
        val referenceField = runCatching { KokoroTtsEngine::class.java.getDeclaredField("activeRenderReference").apply { isAccessible = true } }.getOrNull()
        val initializedOwnerField = runCatching { KokoroTtsEngine::class.java.getDeclaredField("runtime").apply { isAccessible = true } }.getOrNull()
        fun pointer(field: java.lang.reflect.Field?): Any? = checkNotNull(field).get(h.tts)
        var initialPointerDiagnosticCode = 0
        val initialReference = runCatching { pointer(referenceField) }.onFailure { initialPointerDiagnosticCode = initialPointerDiagnosticCode or 1 }.getOrNull()
        // Identity of the initialized synthesis runtime only, NOT an AudioTrack native epoch.
        val initialInitializedOwner = runCatching { pointer(initializedOwnerField) }.onFailure { initialPointerDiagnosticCode = initialPointerDiagnosticCode or 2 }.getOrNull()
        // Retain this real producer's counters across retirement; no callback/hardware mutation.
        val referenceWrapper = checkNotNull(initialReference)
        val nativeReference = checkNotNull(referenceWrapper.javaClass.declaredFields.single {
            DuplexEchoProcessor.RenderLease::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }.get(referenceWrapper))
        val render = checkNotNull(nativeReference.javaClass.getDeclaredField("render").apply { isAccessible = true }.get(nativeReference))
        val sourceRate = render.javaClass.getDeclaredField("rate").apply { isAccessible = true }.getInt(render)
        val acceptedSourceFrames = render.javaClass.getDeclaredField("accepted").apply { isAccessible = true }
        data class OwnerMetadata(val beganNanos: Long, val endedNanos: Long, val requestTerminalCode: Int,
            val phaseKind: Int, val phaseSequence: Long, val phaseRequestMatches: Int,
            val activeTrackIdentity: Int, val activeTrackSame: Int,
            val referencePresent: Int, val referenceIdentity: Int, val referenceSameInitial: Int,
            val initializedOwnerPresent: Int, val initializedOwnerIdentity: Int, val initializedOwnerSame: Int,
            val diagnosticCode: Int) {
            fun metrics(prefix: String): List<Pair<String, Number>> = listOf(
                "${prefix}BeganNanos" to beganNanos, "${prefix}EndedNanos" to endedNanos,
                "${prefix}RequestTerminalCode" to requestTerminalCode, "${prefix}PhaseKind" to phaseKind,
                "${prefix}PhaseSequence" to phaseSequence, "${prefix}PhaseRequestMatches" to phaseRequestMatches,
                "${prefix}ActiveTrackIdentity" to activeTrackIdentity, "${prefix}ActiveTrackSame" to activeTrackSame,
                "${prefix}ReferencePresent" to referencePresent, "${prefix}ReferenceIdentity" to referenceIdentity,
                "${prefix}ReferenceSameInitial" to referenceSameInitial,
                "${prefix}InitializedOwnerPresent" to initializedOwnerPresent,
                "${prefix}InitializedOwnerIdentity" to initializedOwnerIdentity,
                "${prefix}InitializedOwnerSame" to initializedOwnerSame, "${prefix}DiagnosticCode" to diagnosticCode,
            )
        }
        fun ownerMetadata(): OwnerMetadata {
            val began = System.nanoTime()
            return runCatching {
                val activeTrack = activeTrackField.get(h.tts)
                val reference = pointer(referenceField)
                val initializedOwner = pointer(initializedOwnerField)
                // Closed numeric categories only; never retain/emit phase text, request IDs,
                // waveforms or backend objects in the observation. These reads are sequential.
                val terminal = when (h.tts.requestStatuses.value[request]?.state) {
                    KokoroSpeechStatus.Completed -> 1
                    KokoroSpeechStatus.Cancelled -> 2
                    KokoroSpeechStatus.Failed -> 3
                    KokoroSpeechStatus.Queued, KokoroSpeechStatus.Playing -> 0
                    null -> -1
                }
                val phase = when (val value = h.tts.playbackPhase.value) {
                    KokoroPlaybackPhase.Idle -> Triple(0, -1L, -1)
                    is KokoroPlaybackPhase.Preparing -> Triple(1, value.sequence, if (value.requestId == request) 1 else 0)
                    is KokoroPlaybackPhase.Playing -> Triple(2, value.sequence, if (value.requestId == request) 1 else 0)
                    is KokoroPlaybackPhase.Draining -> Triple(3, value.sequence, if (value.requestId == request) 1 else 0)
                }
                OwnerMetadata(began, System.nanoTime(), terminal, phase.first, phase.second, phase.third,
                    activeTrack?.let { System.identityHashCode(it) } ?: 0, if (activeTrack === track) 1 else 0,
                    if (reference != null) 1 else 0, reference?.let { System.identityHashCode(it) } ?: 0,
                    if ((initialPointerDiagnosticCode and 1) != 0) -1 else if (initialReference != null && reference === initialReference) 1 else 0,
                    if (initializedOwner != null) 1 else 0, initializedOwner?.let { System.identityHashCode(it) } ?: 0,
                    if ((initialPointerDiagnosticCode and 2) != 0) -1 else if (initialInitializedOwner != null && initializedOwner === initialInitializedOwner) 1 else 0,
                    initialPointerDiagnosticCode)
            }.getOrElse { OwnerMetadata(began, System.nanoTime(), -1, -1, -1L, -1, -1, -1, -1, -1, -1, -1, -1, -1, 4) }
        }
        data class HeadObservation(val index: Int, val head: Long, val readBeganNanos: Long, val readEndedNanos: Long,
            val beforeState: Int, val beforePlayState: Int, val afterState: Int, val afterPlayState: Int,
            val beforeOwner: OwnerMetadata, val afterOwner: OwnerMetadata) {
            fun metrics(prefix: String): List<Pair<String, Number>> = listOf<Pair<String, Number>>(
                "${prefix}Index" to index, "${prefix}Head" to head, "${prefix}ReadBeganNanos" to readBeganNanos,
                "${prefix}ReadEndedNanos" to readEndedNanos, "${prefix}BeforeState" to beforeState,
                "${prefix}BeforePlayState" to beforePlayState, "${prefix}AfterState" to afterState,
                "${prefix}AfterPlayState" to afterPlayState,
            ) + beforeOwner.metrics("${prefix}BeforeOwner") + afterOwner.metrics("${prefix}AfterOwner")
        }
        val heads = mutableListOf<Long>() // Existing complete measurement and every original oracle remain intact.
        var previousObservation: HeadObservation? = null
        var firstRegression: Pair<HeadObservation, HeadObservation>? = null // O(1) extra retained observations.
        val observationStarted = SystemClock.elapsedRealtimeNanos()
        try {
            h.await("Observe actual playback-head advancement through terminal drain") {
                // Preserve both existing Java-state guards. Sequential getters do NOT prove an
                // atomic native epoch; metadata records cleanup crossings, never filters them.
                runCatching {
                    val beforeOwner = ownerMetadata()
                    val beforeState = track.state
                    val beforePlayState = if (beforeState == AudioTrack.STATE_INITIALIZED) track.playState else -1
                    if (beforeState == AudioTrack.STATE_INITIALIZED && beforePlayState == AudioTrack.PLAYSTATE_PLAYING) {
                        val readBegan = System.nanoTime()
                        val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
                        val readEnded = System.nanoTime()
                        val afterState = track.state
                        val afterPlayState = if (afterState == AudioTrack.STATE_INITIALIZED) track.playState else -1
                        if (afterState == AudioTrack.STATE_INITIALIZED && afterPlayState == AudioTrack.PLAYSTATE_PLAYING) {
                            heads += head // Append BEFORE diagnostic work; metadata failure cannot discard an accepted read.
                            val current = HeadObservation(heads.lastIndex, head, readBegan, readEnded,
                                beforeState, beforePlayState, afterState, afterPlayState, beforeOwner, ownerMetadata())
                            previousObservation?.let { previous ->
                                if (head < previous.head && firstRegression == null) firstRegression = previous to current
                            }
                            previousObservation = current
                        }
                    }
                }
                h.tts.requestStatuses.value[request]?.state in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Failed, KokoroSpeechStatus.Cancelled)
            }
        } finally {
            // Emission is OUTSIDE the observation callback and all audio/model/native locks.
            // At most one first regression and one 16+16 endpoint window; errors cannot mask the test.
            firstRegression?.let { (previous, current) -> runCatching {
                recordLiveNumericObservation("L98", "playback_head_first_regression",
                    *(previous.metrics("previous") + current.metrics("current")).toTypedArray())
            } }
            runCatching {
                val window = listOf<Pair<String, Number>>(
                    "observedHeadSamples" to heads.size, "firstRegressionAvailable" to if (firstRegression != null) 1 else 0,
                    "initialTrackIdentity" to System.identityHashCode(track),
                    "initialReferencePresent" to if (initialReference != null) 1 else 0,
                    "initialReferenceIdentity" to (initialReference?.let { System.identityHashCode(it) } ?: 0),
                    "initialInitializedOwnerPresent" to if (initialInitializedOwner != null) 1 else 0,
                    "initialInitializedOwnerIdentity" to (initialInitializedOwner?.let { System.identityHashCode(it) } ?: 0),
                    "initialPointerDiagnosticCode" to initialPointerDiagnosticCode,
                ) + heads.take(16).mapIndexed { index, head -> "firstHead$index" to head } +
                    heads.takeLast(16).mapIndexed { index, head -> "lastHead$index" to head }
                recordLiveNumericObservation("L98", "playback_head_observation_window", *window.toTypedArray())
            }
        }
        val headFacts = "samples=${heads.size} first16=${heads.take(16)} last16=${heads.takeLast(16)} firstRegression=$firstRegression"
        assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[request]?.state)
        assertTrue("A nonzero real playback head must advance, not only a declared Playing status; $headFacts", heads.distinct().size > 1 && heads.last() > heads.first())
        assertTrue("Playback head must remain monotonic under both active Java-state guards; $headFacts", heads.zipWithNext().all { (a, b) -> b >= a })
        val receiptStatus = h.tts.playbackPhaseReceiptStatus
        val receiptCode = when (receiptStatus) {
            KokoroPlaybackReceiptStatus.Unavailable -> 0
            KokoroPlaybackReceiptStatus.Available -> 1
            KokoroPlaybackReceiptStatus.Faulted -> 2
        }
        runCatching { recordLiveNumericObservation("L98", "playback_phase_receipt", "status" to receiptCode) }
        assertEquals("Actual phase-write receipt must be available and unfaulted", KokoroPlaybackReceiptStatus.Available, receiptStatus)
        val played = h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().filter { it.requestId == request }
        val draining = h.phases.filterIsInstance<KokoroPlaybackPhase.Draining>().filter { it.requestId == request }
        val terminalPhase = when (val phase = h.tts.playbackPhase.value) {
            KokoroPlaybackPhase.Idle -> Triple(0, -1L, -1)
            is KokoroPlaybackPhase.Preparing -> Triple(1, phase.sequence, if (phase.requestId == request) 1 else 0)
            is KokoroPlaybackPhase.Playing -> Triple(2, phase.sequence, if (phase.requestId == request) 1 else 0)
            is KokoroPlaybackPhase.Draining -> Triple(3, phase.sequence, if (phase.requestId == request) 1 else 0)
        }
        val allPhaseKinds = IntArray(4)
        h.phases.forEach { phase -> allPhaseKinds[when (phase) {
            KokoroPlaybackPhase.Idle -> 0
            is KokoroPlaybackPhase.Preparing -> 1
            is KokoroPlaybackPhase.Playing -> 2
            is KokoroPlaybackPhase.Draining -> 3
        }]++ }
        val phaseCount = h.phases.size
        val playedEmpty = if (played.isEmpty()) 1 else 0
        val drainingEmpty = if (draining.isEmpty()) 1 else 0
        val phaseFacts = "observedPhases=$phaseCount playedCount=${played.size} drainingCount=${draining.size} playedEmpty=$playedEmpty drainingEmpty=$drainingEmpty allKinds=${allPhaseKinds.toList()} terminalPhaseKind=${terminalPhase.first} terminalPhaseSequence=${terminalPhase.second} terminalPhaseRequestMatches=${terminalPhase.third}"
        runCatching {
            recordLiveNumericObservation("L98", "playback_phase_presence",
                "observedPhaseCount" to phaseCount, "playedCount" to played.size, "drainingCount" to draining.size,
                "playedEmpty" to playedEmpty, "drainingEmpty" to drainingEmpty,
                "allIdleCount" to allPhaseKinds[0], "allPreparingCount" to allPhaseKinds[1],
                "allPlayingCount" to allPhaseKinds[2], "allDrainingCount" to allPhaseKinds[3],
                "terminalPhaseKind" to terminalPhase.first, "terminalPhaseSequence" to terminalPhase.second,
                "terminalPhaseRequestMatches" to terminalPhase.third)
        }
        assertTrue("The actual request must have an observed Playing phase; $phaseFacts", played.isNotEmpty())
        assertTrue("The actual request must have an observed Draining phase; $phaseFacts", draining.isNotEmpty())
        val lastSequence = played.maxOf { it.sequence }
        assertEquals(lastSequence, draining.last().sequence)
        val stages = mutableListOf<Pair<Long, String>>()
        SpeechAudit.query(request).use { rows ->
            val sequenceColumn = rows.getColumnIndexOrThrow("phrase_sequence")
            val stageColumn = rows.getColumnIndexOrThrow("stage")
            val timeColumn = rows.getColumnIndexOrThrow("elapsed_ms")
            while (rows.moveToNext()) if (!rows.isNull(sequenceColumn) && rows.getLong(sequenceColumn) == lastSequence)
                stages += rows.getLong(timeColumn) to rows.getString(stageColumn)
        }
        val head = stages.firstOrNull { it.second == "playback_head_advanced" } ?: error("Last phrase lacks actual head evidence")
        val drained = stages.lastOrNull { it.second == "phrase_drained" } ?: error("Last phrase lacks drain evidence")
        assertTrue(head.first <= drained.first)
        val accepted = acceptedSourceFrames.getLong(render)
        val eofPartialFrames = accepted % (sourceRate / 100)
        assertTrue("The real reply must exercise a partial source-rate 10 ms EOF write; accepted=$accepted rate=$sourceRate partial=$eofPartialFrames",
            eofPartialFrames > 0)
        recordLiveNumericObservation("L98", "actual_playback_head_drain",
            "lastSequence" to lastSequence, "observedHeadSamples" to heads.size,
            "eofCounterAvailable" to 1, "acceptedSourceFrames" to accepted,
            "sourceRateHz" to sourceRate, "eofPartialFrames" to eofPartialFrames,
            "observedFirstHead" to heads.first(), "observedLastHead" to heads.last(),
            "headElapsedMs" to head.first, "drainElapsedMs" to drained.first,
            "headToDrainMs" to drained.first - head.first,
            "observationStartedNanos" to observationStarted,
            "observationMs" to (SystemClock.elapsedRealtimeNanos() - observationStarted) / 1_000_000,
            "acousticIntelligibilityClaim" to 0)
    }

    @Test fun l99_boundedEightTurnNativeSoakReleasesThreadsTracksAndMicrophoneLease() {
        val beforeThreads = Thread.getAllStackTraces().keys.map { it.name }.groupingBy { it }.eachCount()
        startListening()
        repeat(8) { index ->
            spoken("continue", index + 1)
            val request = h.submissions.last().requestId
            h.reply("Turn ${index + 1} completed. Native resources remain owned until explicit End.")
            h.drained(request)
            assertEquals(1, liveRecordings().size)
            assertTrue(h.tts.requestStatuses.value.size <= KokoroTtsEngine.MAX_TRACKED_REQUESTS)
            recordLiveNumericObservation("L99", "turn_completed",
                "turn" to index + 1, "elapsedNanos" to SystemClock.elapsedRealtimeNanos(),
                "pssKb" to Debug.getPss(), "nativeHeapBytes" to Debug.getNativeHeapAllocatedSize(),
                "threads" to Thread.getAllStackTraces().size, "trackedStatuses" to h.tts.requestStatuses.value.size)
        }
        assertEquals(8, h.submissions.map { it.requestId }.toSet().size)
        h.main { h.controller.endLive() }; microphoneStopped()
        runBlocking { withTimeout(30_000) { h.inputs.forEach { it.closeAndJoin() }; h.tts.closeAndJoin() } }
        assertTrue(MicCaptureCoordinator.awaitIdle(2_000))
        assertEquals(KokoroPlaybackPhase.Idle, h.tts.playbackPhase.value)
        compose.waitUntil(5_000) {
            Thread.getAllStackTraces().keys.filter { it.isAlive && (it.name.startsWith("kompi-local-live") || it.name.startsWith("Kokoro")) }
                .groupingBy { it.name }.eachCount().all { (name, count) -> count <= (beforeThreads[name] ?: 0) }
        }
        assertEquals(0, audio.quietOutputWindow().getInt("nonzeroAfter200ms"))
        recordLiveNumericObservation("L99", "native_teardown",
            "completedTurns" to 8, "elapsedNanos" to SystemClock.elapsedRealtimeNanos(),
            "ownedRecordingsAfterEnd" to liveRecordings().size, "nativeWorkersReturnedToBaseline" to 1,
            "batteryCertification" to 0, "longDurationCertification" to 0)
    }
}
