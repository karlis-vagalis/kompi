package com.kompi.app.peers

import androidx.compose.runtime.Immutable

/** Where a host-details route should return when its Back action is used. */
enum class PeerReturnTarget { Chat, Directory, Sidebar }

/** A one-shot route request. Increment [eventId] for every details action, even for the same host. */
@Immutable
data class PeerDetailsRequest(
    val hostId: String,
    val eventId: Long,
    val returnTo: PeerReturnTarget,
)

/** Connection state is distinct from selection: only a selected host can be connected. */
enum class PeerHostStatus { NotSelected, Disconnected, Connecting, Synchronizing, Live }

@Immutable
data class PeerDirectoryHost(
    val hostId: String,
    val name: String,
    val selected: Boolean,
    val status: PeerHostStatus,
    val lastSeenEpochMillis: Long,
)

/** Host-scoped values supplied by the owner; requested values may be pending while offline. */
data class PeerHostSettings(
    val hostId: String,
    val name: String,
    val selected: Boolean,
    val status: PeerHostStatus,
    val lastSeenEpochMillis: Long,
    val requestedIdleTimeoutSeconds: Int,
    val effectiveIdleTimeoutSeconds: Int?,
    val idleTimeoutPending: Boolean,
    val idleTimeoutError: String?,
    val requestedUploadRetentionSeconds: Int,
    val effectiveUploadRetentionSeconds: Int?,
    val uploadRetentionPending: Boolean,
    val uploadRetentionError: String?,
    /** Optional host-reported capabilities; omit when the host has not supplied a current catalog. */
    val capabilityDetails: List<String>? = null,
    /** Original label supplied in the pairing code; reset removes only the local override. */
    val pairingName: String = name,
    val displayNameOverride: PeerDisplayName? = null,
)
