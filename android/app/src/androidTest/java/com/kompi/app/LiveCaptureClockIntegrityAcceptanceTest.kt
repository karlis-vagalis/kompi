package com.kompi.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import com.kompi.app.audio.CaptureFrameTiming
import com.kompi.app.audio.ForegroundMicCapture
import com.kompi.app.audio.Pcm16kConsumer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual framework AudioRecord, lifecycle and production capture reader. No injected PCM, clock,
 * recognized text, or synthesized success callbacks. The only fault is a consumer deliberately
 * held behind a barrier, like a stalled native processor. Run with real RECORD_AUDIO permission
 * on the explicitly selected private virtual-microphone emulator; no model assets are required.
 */
class LiveCaptureClockIntegrityAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class BlockedConsumer : Pcm16kConsumer {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<Pair<Int, CaptureFrameTiming>>()
        val failures = CopyOnWriteArrayList<String>()
        override fun onAudio(samples: FloatArray) = error("Actual capture must supply observed timing")
        override fun onAudio(samples: FloatArray, timing: CaptureFrameTiming) {
            frames += samples.size to timing
            entered.countDown()
            // Deliberately emulate an uninterruptible processor. Stop must close actual recording
            // without freeing the record/effects underneath this still-running reader.
            var interrupted = false
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        override fun onCaptureError(message: String) { failures += message }
    }

    private fun capture(consumer: Pcm16kConsumer? = null, factory: (() -> Pcm16kConsumer)? = null): ForegroundMicCapture {
        assertEquals("Run with microphone permission granted", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.RECORD_AUDIO))
        lateinit var capture: ForegroundMicCapture
        compose.runOnUiThread {
            capture = ForegroundMicCapture(compose.activity, compose.activity, consumer, consumerFactory = factory)
        }
        return capture
    }

    private fun closeCapture(capture: ForegroundMicCapture) {
        // LifecycleRegistry owns observer addition/removal on the UI thread. Actual recording,
        // injected processor stalls, privacy stop and reader joins remain on their real threads.
        compose.runOnUiThread { capture.close() }
    }

    private fun activeRecord(capture: ForegroundMicCapture): AudioRecord =
        ForegroundMicCapture::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(capture) as AudioRecord

    private fun ownedResources(capture: ForegroundMicCapture): Any? =
        ForegroundMicCapture::class.java.getDeclaredField("resources").apply { isAccessible = true }.get(capture)

    private fun await(description: String, condition: () -> Boolean) {
        try { compose.waitUntil(10_000, condition) }
        catch (error: Throwable) { throw AssertionError(description, error) }
    }

    @Test fun l32_processingStallBeyondActualCaptureCapacityFailsBeforeAnotherFrameAndClosesHardware() {
        val consumer = BlockedConsumer()
        val mic = capture(consumer)
        try {
            mic.start().getOrThrow()
            assertTrue("Actual first PCM must reach the consumer", consumer.entered.await(10, TimeUnit.SECONDS))
            val record = activeRecord(mic)
            val stamp = AudioTimestamp()
            await("This framework regression requires an observable real capture producer timestamp") {
                record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && stamp.nanoTime > 0
            }
            val initialPosition = stamp.framePosition
            val capacity = record.bufferSizeInFrames
            assertTrue(capacity >= 512)
            var lastPosition = initialPosition
            // No guessed sleep: the dedicated acquisition thread must keep draining actual PCM
            // while DSP is held. Its bounded FIFO must then stop explicitly, never discard packets
            // or allow AudioRecord to continue overflowing behind a blocked consumer.
            await("Bounded processor backlog must explicitly fail and stop the actual microphone") {
                if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    lastPosition = maxOf(lastPosition, stamp.framePosition)
                }
                record.recordingState == AudioRecord.RECORDSTATE_STOPPED && consumer.failures.isNotEmpty()
            }
            val code = ForegroundMicCapture::class.java.getDeclaredField("captureFailureCode")
                .apply { isAccessible = true }.get(mic).toString()
            assertEquals("The injected processor stall must be diagnosed, not hidden by a different clock failure", "ProcessorBacklog", code)
            assertTrue("Acquisition must make actual producer progress while the consumer is blocked", lastPosition > initialPosition)
            consumer.release.countDown()
            mic.awaitReaderExit()
            assertEquals("No frame with an unprovable buffer position may reach AEC/VAD", 1, consumer.frames.size)
            assertEquals("Capture failure is one local terminal event", 1, consumer.failures.size)
            assertEquals(512, consumer.frames.single().first)
            assertTrue("A framework timestamp is not a proof of 1 ms capture-buffer accuracy",
                consumer.frames.single().second.uncertaintyNanos > 1_000_000L)
            println("LIVE_CAPTURE_CLOCK fault=actual_consumer_stall acquisitionContinued=true boundedProcessorBacklog=true suspectPcmForwarded=false microphoneStopped=true")
        } finally { consumer.release.countDown(); closeCapture(mic); mic.awaitReaderExit() }
    }

    @Test fun l63_stopClosesHardwareWithoutReleasingHungReaderThenFreshCaptureHasBounded512SampleFrames() {
        val blocked = BlockedConsumer()
        val old = capture(blocked)
        try {
            old.start().getOrThrow()
            assertTrue(blocked.entered.await(10, TimeUnit.SECONDS))
            val record = activeRecord(old)
            val session = record.audioSessionId
            val owned = ownedResources(old)
            closeCapture(old)
            assertEquals("Privacy stop is independent of the blocked consumer", AudioRecord.RECORDSTATE_STOPPED, record.recordingState)
            assertEquals("A live reader still owns its initialized native record", AudioRecord.STATE_INITIALIZED, record.state)
            assertSame("Close must quarantine, not release, a reader still inside the consumer", owned, ownedResources(old))
            assertFalse("Bounded join must report the still-running reader", old.stopAndJoin())
            assertSame(owned, ownedResources(old))
            blocked.release.countDown()
            old.awaitReaderExit()
            assertNull("Only real reader exit makes the old record/effects disposable", ownedResources(old))
            assertTrue("Explicit stop must not be turned into a clock-failure event", blocked.failures.isEmpty())

            val frames = CopyOnWriteArrayList<Pair<Int, CaptureFrameTiming>>()
            val failures = CopyOnWriteArrayList<String>()
            val next = capture(object : Pcm16kConsumer {
                override fun onAudio(samples: FloatArray) = error("Missing capture timing")
                override fun onAudio(samples: FloatArray, timing: CaptureFrameTiming) { frames += samples.size to timing }
                override fun onCaptureError(message: String) { failures += message }
            })
            try {
                next.start().getOrThrow()
                val replacement = activeRecord(next)
                assertNotEquals("Recovery owns a fresh actual AudioRecord session", session, replacement.audioSessionId)
                await("Fresh actual capture must deliver multiple consumer frames") { frames.size >= 5 || failures.isNotEmpty() }
                next.requestStop(); next.awaitReaderExit()
                assertTrue("Ordinary continuous framework capture must retain clock integrity", failures.isEmpty())
                assertTrue(frames.size >= 5)
                frames.forEach { (size, timing) ->
                    assertEquals(512, size)
                    assertTrue(timing.firstSampleNanos > 0)
                    assertTrue(timing.nanosPerSample.isFinite())
                    assertTrue(timing.uncertaintyNanos > 1_000_000L)
                }
                frames.zipWithNext().forEach { (first, second) ->
                    assertTrue("Frame clocks cannot move backwards outside their declared bounds",
                        second.second.firstSampleNanos + second.second.uncertaintyNanos >=
                            first.second.firstSampleNanos - first.second.uncertaintyNanos)
                }
            } finally { closeCapture(next); next.awaitReaderExit() }
        } finally { blocked.release.countDown(); closeCapture(old); old.awaitReaderExit() }
    }

    @Test fun l63_stopDuringBlockedConsumerInitializationCannotOpenMicrophoneAfterStop() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Result<Unit>?>()
        val mic = capture(factory = {
            entered.countDown()
            var interrupted = false
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
            object : Pcm16kConsumer {
                override fun onAudio(samples: FloatArray) = error("Stopped initialization must never open capture")
                override fun onCaptureError(message: String) = Unit
            }
        })
        val starter = Thread({ outcome.set(mic.start()) }, "capture-clock-blocked-initialization")
        try {
            starter.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            closeCapture(mic)
            assertTrue("Stop must return independently of a blocked initializer", starter.isAlive)
            assertNull("No hardware can be opened while initialization is still blocked", ownedResources(mic))
            release.countDown()
            starter.join(10_000)
            assertFalse("Released initializer must exit without opening capture", starter.isAlive)
            assertTrue(outcome.get()?.isFailure == true)
            assertNull(ownedResources(mic))
        } finally { release.countDown(); closeCapture(mic); starter.join(10_000) }
    }
}
