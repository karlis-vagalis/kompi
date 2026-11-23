package com.kompi.app

import com.kompi.app.audio.LiveAudioSession

/** Test-only fault injection upstream of real PCM route validation, NOT a hardware unplug.
 * No callbacks/transcripts/audio phases are injected; the production reader must detect the fault
 * and actually release AudioRecord/AudioTrack. Avoids unsupported virtual HAL device creation. */
internal object NativeRouteFault {
    fun loseCurrentRoute() {
        val owner = LiveAudioSession::class.java.getDeclaredField("active").apply { isAccessible = true }
        val session = owner.get(null) as? LiveAudioSession ?: error("Real Live route must be active before fault injection")
        val lock = session.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(session)
        synchronized(lock) {
            // Corrupt only the owned lease's expected route fingerprint. The actual AudioRecord
            // reader must call normal production route validation, revoke and stop real output.
            session.javaClass.getDeclaredField("expectedDeviceId").apply { isAccessible = true }
                .set(session, Int.MIN_VALUE)
        }
    }
}
