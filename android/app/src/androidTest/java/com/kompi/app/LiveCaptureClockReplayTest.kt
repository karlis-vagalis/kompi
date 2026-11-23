package com.kompi.app

import android.media.AudioTimestamp
import com.kompi.app.audio.CaptureFrameTiming
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/** Content-free capture-clock replay, not microphone/ASR E2E. Replays a sustained stream through
 * the exact production clock, including the short-interval timestamp jitter observed in capture timestamps.
 * Private reflection follows the native capture diagnostics; there is no production test switch.
 */
class LiveCaptureClockReplayTest {
    private val origin = 10_000_000_000L
    private class Clock(origin: Long) {
        private val type = Class.forName("com.kompi.app.audio.ForegroundMicCapture\$CaptureReadClock")
        private val clock = type.getDeclaredConstructor(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(origin, 3840)
        private val before = type.getDeclaredMethod("beforeRead", Long::class.javaPrimitiveType).apply { isAccessible = true }
        private val after = type.getDeclaredMethod("afterRead", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, AudioTimestamp::class.java).apply { isAccessible = true }
        fun read(began: Long, frame: Long, time: Long): CaptureFrameTiming {
            try {
                before.invoke(clock, began)
                return after.invoke(clock, began, began + 100_000L, began + 110_000L, 320,
                    AudioTimestamp().apply { framePosition = frame; nanoTime = time }) as CaptureFrameTiming
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
    private fun failedStage(action: () -> Unit): Int {
        val failure = runCatching(action).exceptionOrNull() ?: throw AssertionError("Expected a real capture-clock fault")
        assertEquals("CaptureClockDiscontinuity", failure.javaClass.simpleName)
        val snapshot = failure.javaClass.getDeclaredField("snapshot").apply { isAccessible = true }.get(failure)
        return snapshot.javaClass.getDeclaredField("stage").apply { isAccessible = true }.getInt(snapshot)
    }

    @Test fun l32_shortIntervalTimestampJitterDoesNotStopSustainedCaptureOrFreshRestart() {
        repeat(2) { restart ->
            val base = origin + restart * 20_000_000_000L
            val clock = Clock(base)
            val timings = (1L..600L).map { index ->
                val nominal = base + index * 20_000_000L
                // Adjacent 19/21 ms timestamp intervals for 320 frames average exactly 16 kHz.
                // The regression fixture has a 320-frame / 18.93 ms adjacent interval.
                val jitter = if (index % 2L == 0L) 1_100_000L else 0L
                clock.read(nominal + 2_000_000L, index * 320, nominal + jitter)
            }
            assertEquals(600, timings.size)
            assertTrue(timings.all { it.firstSampleNanos > 0 && it.uncertaintyNanos > 0 && it.nanosPerSample in 60_000.0..65_000.0 })
            assertTrue(timings.zipWithNext().all { (a, b) -> b.firstSampleNanos + b.uncertaintyNanos >= a.firstSampleNanos - a.uncertaintyNanos })
        }
    }

    @Test fun l32_realTimestampResetsSustainedRateFaultsAndReaderDeadlinesStillStopCapture() {
        val reset = Clock(origin)
        reset.read(origin + 22_000_000, 320, origin + 20_000_000)
        assertEquals(6, failedStage { reset.read(origin + 42_000_000, 0, origin + 40_000_000) })

        val rate = Clock(origin)
        assertEquals(8, failedStage {
            (1L..30L).forEach { index ->
                // The HAL frame counter claims twice the actual sample rate for a full window.
                rate.read(origin + index * 20_000_000 + 2_000_000, index * 640, origin + index * 20_000_000)
            }
        })

        val stalled = Clock(origin)
        stalled.read(origin + 22_000_000, 320, origin + 20_000_000)
        assertEquals(1, failedStage { stalled.read(origin + 300_000_000, 640, origin + 298_000_000) })
    }
}
