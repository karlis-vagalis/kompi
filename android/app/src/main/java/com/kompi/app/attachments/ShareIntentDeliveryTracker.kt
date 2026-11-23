package com.kompi.app.attachments

/** Distinguishes Activity recreation of the current launch intent from a new sharesheet choice. */
class ShareIntentDeliveryTracker(restoredFingerprint: String? = null) {
    var lastFingerprint: String? = restoredFingerprint
        private set

    /** onCreate receives the same launch intent after configuration recreation. */
    fun shouldHandleOnCreate(share: AttachmentSharePayload.Share, isRecreation: Boolean): Boolean {
        if (isRecreation && share.fingerprint == lastFingerprint) return false
        lastFingerprint = share.fingerprint
        return true
    }

    /** Every onNewIntent is a new explicit user share, even for the same URI as before. */
    fun handleOnNewIntent(share: AttachmentSharePayload.Share): Boolean {
        lastFingerprint = share.fingerprint
        return true
    }
}
