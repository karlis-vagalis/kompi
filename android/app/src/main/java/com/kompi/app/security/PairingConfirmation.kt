package com.kompi.app.security

/** Proof that a pending attempt received a matching reply from the ticket's authenticated peer. */
internal class PairingConfirmation private constructor(val pairing: SecureStore.Pairing) {
    companion object {
        fun fromReply(
            pending: SecureStore.Pairing?,
            attempt: SecureStore.Pairing,
            expectedMachineId: String,
            authenticatedMachineId: String,
        ): PairingConfirmation {
            require(expectedMachineId.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected machine identity" }
            require(expectedMachineId == authenticatedMachineId) { "Pairing confirmation came from another machine" }
            check(pending != null && pending.endpointTicket == attempt.endpointTicket && pending.secret == attempt.secret) {
                "Pairing attempt is no longer pending"
            }
            return PairingConfirmation(attempt.copy(confirmed = true, machineId = expectedMachineId))
        }
    }
}
