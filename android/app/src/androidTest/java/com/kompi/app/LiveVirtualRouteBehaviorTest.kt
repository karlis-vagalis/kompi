package com.kompi.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import org.junit.Assert.*
import org.junit.Test

/** Actual AudioManager/HAL route stress. Kept enabled but isolated after core audio acceptance. */
internal class LiveVirtualRouteBehaviorTest : NativeMicrophoneScenario() {
    @Test fun incomingEmulatedPhoneCallStopsCaptureAndEndingCallDoesNotRestartIt() {
        startListening()
        try { audio.incomingCall(); microphoneStopped() }
        finally { audio.endCall() }
        assertTrue(liveRecordings().isEmpty())
        assertEquals(1, h.inputs.size)
        assertTrue(h.submissions.isEmpty())
    }

    @Test fun lossOfRealCommunicationModeDuringPrivateReplyStopsCaptureAndOutput() {
        val mode = manager.mode
        try {
            startListening(); spoken("review", 1)
            val request = h.submissions.single().requestId
            h.reply("This answer must stop when another audio owner takes communication mode. It must not automatically continue on the speaker after the route becomes invalid.")
            h.await("Native reply must play before communication mode changes") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
            manager.mode = AudioManager.MODE_NORMAL
            microphoneStopped()
            h.await("Invalid communication mode must stop native output", allowFailure = true) { h.tts.requestStatuses.value[request]?.state == KokoroSpeechStatus.Cancelled }
            assertEquals(0, audio.quietOutputWindow().getInt("nonzeroAfter200ms"))
        } finally { manager.mode = mode }
    }

    @Test fun realWiredOutputAndBuiltInMicRestorePriorCommunicationRouteAfterEnd() {
        val mode = manager.mode
        EmulatedWiredHeadset(h.context).use { headset ->
            headset.connect()
            try {
                assertEquals(AudioManager.MODE_NORMAL, manager.mode)
                // Snapshot the real pre-existing route. Requests made in MODE_NORMAL may be
                // deferred by Android; do not invent a speaker precondition by forcing one.
                val previousDevice = manager.communicationDevice?.id
                startListening()
                assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, manager.communicationDevice?.type)
                assertTrue(liveRecordings().any { it.audioDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC })
                spoken("review", 1)
                h.main { h.controller.endLive() }; microphoneStopped()
                compose.waitUntil(3_000) { manager.mode == AudioManager.MODE_NORMAL }
                assertEquals(previousDevice, manager.communicationDevice?.id)
            } finally { manager.clearCommunicationDevice(); manager.mode = mode }
        }
    }

    @Test fun changingRealCommunicationRouteDuringPrivateReplyStopsCaptureAndOutput() {
        EmulatedWiredHeadset(h.context).use { headset ->
            headset.connect(); startListening(); spoken("review", 1)
            val request = h.submissions.single().requestId
            h.reply("This private answer must stop when the selected communication route changes. It must not continue automatically on the newly selected speaker.")
            h.await("Native private reply must play on the wired output") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
            val speaker = manager.availableCommunicationDevices.single { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            assertTrue(manager.setCommunicationDevice(speaker))
            microphoneStopped()
            h.await("A real framework route change must cancel native output", allowFailure = true) { h.tts.requestStatuses.value[request]?.state == KokoroSpeechStatus.Cancelled }
            assertEquals(0, audio.quietOutputWindow().getInt("nonzeroAfter200ms"))
            assertEquals(1, h.inputs.size)
        }
    }

    @Test fun unplugVirtualWiredOutputDuringRealPrivateReplyStopsCaptureAndOutput() {
        EmulatedWiredHeadset(h.context).use { headset ->
            audio.checkpoint("unplug-before-connect")
            headset.connect(); audio.checkpoint("unplug-connected")
            startListening(); audio.checkpoint("unplug-listening")
            spoken("review", 1); audio.checkpoint("unplug-recognized")
            val request = h.submissions.single().requestId
            h.reply("This private answer is playing on the selected virtual headset and must not keep playing on the speaker when that headset is unplugged during the answer.")
            h.await("Private native reply must be playing") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
            audio.checkpoint("unplug-native-playback-started")
            headset.disconnect(); audio.checkpoint("unplug-disconnected")
            microphoneStopped()
            h.await("Route loss must stop the actual answer", allowFailure = true) { h.tts.requestStatuses.value[request]?.state == KokoroSpeechStatus.Cancelled }
            assertEquals(0, audio.quietOutputWindow().getInt("nonzeroAfter200ms"))
            assertEquals(1, h.inputs.size)
        }
    }
}
