package com.kompi.app.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairHandshakeTest {
    @Test fun retriesUnconfirmedPairAfterLostAcknowledgementButNeverAfterConfirmation() {
        assertTrue(PairHandshake.shouldSendCredential(confirmed = false, sentOnThisConnection = false))
        assertFalse(PairHandshake.shouldSendCredential(confirmed = false, sentOnThisConnection = true))
        // A server may have stored the key while the paired ACK was lost; daemon-side idempotent ACK completes this retry.
        assertTrue(PairHandshake.shouldSendCredential(confirmed = false, sentOnThisConnection = false))
        assertFalse(PairHandshake.shouldSendCredential(confirmed = true, sentOnThisConnection = false))
        assertFalse(PairHandshake.shouldSendCredential(confirmed = true, sentOnThisConnection = true))
    }
}
