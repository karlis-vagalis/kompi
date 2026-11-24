package com.kompi.app

import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.audio.KokoroModelInstaller
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.diagnostics.SpeechAudit
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the installed, pinned Kokoro model and normal AudioTrack playback, without a paired host. */
class KokoroToolCuePlaybackTest {
    @Test fun playingTheSameToolCueAfterEngineRecreationUsesDiskButThinkingAndReplyDoNot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = KokoroModelInstaller(File(context.filesDir, "kokoro-models"))
        assumeTrue("Install the pinned Kokoro model to exercise native cue playback", installer.installed() != null)
        val cacheDir = File(context.cacheDir, "live-tool-cue-pcm")
        fun entries() = cacheDir.listFiles()?.filter { it.extension == "pcm" }?.toSet().orEmpty()
        val existing = entries()
        val runId = UUID.randomUUID().toString().take(8)
        val message = "I'll check that now. Reference $runId."
        val observed = java.util.concurrent.ConcurrentLinkedQueue<KokoroPlaybackPhase.Playing>()
        fun engine() = KokoroTtsEngine(context) { phase ->
            if (phase is KokoroPlaybackPhase.Playing) observed += phase
        }
        suspend fun play(tts: KokoroTtsEngine, id: String, isToolCue: Boolean): List<String> {
            assertTrue(if (isToolCue) tts.enqueueLiveToolCue(id, message) else tts.enqueue(id, 0, message))
            tts.finishRequest(id)
            val status = withTimeout(30_000) {
                tts.requestStatuses.first { values ->
                    values[id]?.state in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Failed, KokoroSpeechStatus.Cancelled)
                }.getValue(id)
            }
            assertEquals(status.failureMessage, KokoroSpeechStatus.Completed, status.state)
            assertTrue(observed.any { it.requestId == id && it.phrase == message })
            return SpeechAudit.query(id).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        if (rows.getString(rows.getColumnIndexOrThrow("stage")) == "prepared_cue_used") {
                            add(rows.getString(rows.getColumnIndexOrThrow("detail")))
                        }
                    }
                }
            }
        }
        try {
            val original = engine()
            try {
                original.initialize().getOrThrow()
                assertTrue(play(original, "activity-cold-$runId", isToolCue = true).isEmpty())
                withTimeout(10_000) { while ((entries() - existing).isEmpty()) delay(25) }
                assertEquals(listOf("tier=Ram"), play(original, "activity-warm-$runId", isToolCue = true))
            } finally { original.closeAndJoin() }
            val reopened = engine()
            try {
                reopened.initialize().getOrThrow()
                assertEquals(listOf("tier=Disk"), play(reopened, "activity-disk-$runId", isToolCue = true))
                assertTrue(play(reopened, "activity-thinking-$runId", isToolCue = false).isEmpty())
                assertTrue(play(reopened, "reply-$runId", isToolCue = false).isEmpty())
                assertEquals(1, (entries() - existing).size)
            } finally { reopened.closeAndJoin() }
        } finally {
            (entries() - existing).forEach(File::delete)
        }
    }
}
