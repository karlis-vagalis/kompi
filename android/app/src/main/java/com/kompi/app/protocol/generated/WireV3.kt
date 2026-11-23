// GENERATED from Rust schemars network envelope; sha256 e302483ec7fcadfffafcc0a3d10057587abb5dc95fe2655bffa46bb82f36fcc2. DO NOT EDIT.
package com.kompi.app.protocol.generated

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator
import com.fasterxml.jackson.core.io.SerializedString
import java.io.ByteArrayOutputStream
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val MAX_PI_UI_TEXT_CHARS:Int=16000
const val VERSION:Int=4
const val PAIRING_VERSION:Int=1
const val MAX_PAYLOAD:Int=65536
const val ALPN:String="kompi/4"
const val PAIRING_TOKEN_PREFIX:String="kompi-pair-v1:"
const val PHONE_CONVERSATIONS_CAPABILITY:String="phone-conversations"
const val ATTACHMENTS_CAPABILITY:String="attachments"
const val ARTIFACT_DOWNLOADS_CAPABILITY:String="artifact-downloads"
const val DEVICE_NOTIFICATIONS_CAPABILITY:String="device-notifications"
const val RICH_DEVICE_NOTIFICATIONS_CAPABILITY:String="rich-device-notifications"
const val REPLY_NOTIFICATIONS_CAPABILITY:String="reply-notifications"
const val REPLY_NOTIFICATION_TTL_MS:Long=300000L
const val NOTIFICATION_INPUT_MAX_BYTES:Int=32768
const val NOTIFICATION_IMAGE_MAX_BYTES:Int=12288
const val NOTIFICATION_IMAGE_MAX_PIXELS:Int=1048576
const val NOTIFICATION_IMAGE_MAX_DIMENSION:Int=2048
const val NOTIFICATION_URL_MAX_CHARS:Int=2048
const val UNIFIED_PUSH_CAPABILITY:String="unified-push"
const val PUSH_ENDPOINT_MAX_BYTES:Int=2048
const val PUSH_MAX_CIPHERTEXT_BYTES:Int=4096
const val PUSH_MAX_TTL_MS:Long=86400000L
const val PUSH_MAX_FUTURE_SKEW_MS:Long=300000L
const val ATTACHMENT_CHUNK_MAX_BYTES:Int=49152
const val ATTACHMENT_MAX_FILE_BYTES:Long=26214400L
const val ATTACHMENT_MAX_FILES_PER_MESSAGE:Int=16
const val ATTACHMENT_MAX_PEER_BYTES:Long=104857600L
const val ATTACHMENT_MAX_PEER_ITEMS:Int=256
const val REQUEST_ID_MIN_LENGTH:Int=1
const val REQUEST_ID_MAX_LENGTH:Int=128
const val REQUEST_ID_PATTERN:String="^[A-Za-z0-9._:-]+$"
const val MIN_IDLE_TIMEOUT_SECONDS:Int=60
const val MAX_IDLE_TIMEOUT_SECONDS:Int=86400
const val DEFAULT_IDLE_TIMEOUT_SECONDS:Int=1800
const val DEFAULT_UPLOAD_RETENTION_SECONDS:Int=604800
const val MIN_UPLOAD_RETENTION_SECONDS:Int=3600
const val MAX_UPLOAD_RETENTION_SECONDS:Int=2592000
const val MAX_RESPONSE_CHUNK_CHARS:Int=2048
const val MAX_RECOVERY_CHUNKS:Int=128
const val MAX_SYNC_EVENTS:Int=128
const val MAX_SYNC_PAGE:Int=32
const val CBOR_MAX_DEPTH:Int=9
const val CBOR_MAX_MAP_ENTRIES:Int=32
const val CBOR_MAX_ARRAY_ENTRIES:Int=128
data class EnvelopeV3(val v:Int=VERSION,val id:String,val message:WireMessage)
data class AgentNotification(
    val body: String,
    val rich: com.kompi.app.protocol.generated.RichNotificationOptions? = null,
    val title: String
)
data class ChatModel(
    val efforts: List<String>,
    val modelId: String,
    val name: String,
    val provider: String
)
data class ChatModelConfiguration(
    val effort: String,
    val modelId: String,
    val provider: String
)
data class ChatTimingCursor(
    val after: String,
    val storeId: String
)
data class ChatTimingEvent(
    val attemptId: String,
    val conversationId: String? = null,
    val requestId: String,
    val responseRequestId: String? = null,
    val stamp: com.kompi.app.protocol.generated.ChatTimingStamp
)
data class ChatTimingRow(
    val cursor: String,
    val event: com.kompi.app.protocol.generated.ChatTimingEvent
)
data class ChatTimingStamp(
    val clockId: String,
    val monotonicMs: Long,
    val stage: String,
    val wallMs: Long
)
data class ConversationMetadata(
    val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null,
    val conversationId: String,
    val createdAt: String,
    val expiresAt: String,
    val idleTimeoutSeconds: Int,
    val sessionId: String,
    val sessionName: String? = null,
    val state: String
)
data class EffectiveSettings(
    val idleTimeoutSeconds: Int,
    val uploadRetentionSeconds: Int
)
data class GenerationStats(
    val assistantMessages: Long,
    val daemonTotalMs: Long? = null,
    val firstTextMs: Long? = null,
    val model: String? = null,
    val outputTokens: Long? = null,
    val provider: String? = null,
    val reasoningTokens: Long? = null,
    val textChunks: Long,
    val totalMs: Long
)
data class ModelCatalog(
    val defaultConfiguration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null,
    val models: List<com.kompi.app.protocol.generated.ChatModel>
)
data class NotificationButton(
    val label: String,
    val target: com.kompi.app.protocol.generated.NotificationTarget
)
data class NotificationImage(
    val base64: String
)
data class OfferedArtifact(
    val artifactId: String,
    val fileName: String,
    val length: Long,
    val sha256: String
)
data class PendingPiUiRequest(
    val conversationId: String,
    val dialog: com.kompi.app.protocol.generated.PiUiDialog,
    val interactionId: String
)
data class PiFrame(
    val conversationId: String,
    val epoch: String,
    val json: String,
    val kind: String,
    val last: Boolean,
    val part: Long,
    val requestId: String? = null,
    val sequence: Long,
    val sessionId: String
)
data class PushRegistration(
    val auth: String,
    val endpoint: String,
    val integrityKey: String,
    val p256dh: String,
    val registrationId: String
)
data class ReplyNotification(
    val completedAtMs: Long,
    val conversationId: String
)
data class RequestSummary(
    val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null,
    val conversationId: String,
    val nextSequence: Long,
    val outcome: String,
    val requestId: String
)
data class RichNotificationOptions(
    val buttons: List<com.kompi.app.protocol.generated.NotificationButton>? = null,
    val group: String? = null,
    val progress: com.kompi.app.protocol.generated.NotificationProgress? = null,
    val silent: Boolean? = null,
    val style: com.kompi.app.protocol.generated.NotificationStyle? = null,
    val subtext: String? = null,
    val tag: String? = null,
    val timeoutMs: Long? = null
)
data class SyncEvent(
    val cursor: String,
    val event: com.kompi.app.protocol.generated.DurableEvent
)
data class PushNotificationPayload(
    val body: String,
    val expiresAtMs: Long,
    val hostEndpoint: String,
    val issuedAtMs: Long,
    val notificationId: String,
    val registrationId: String,
    val title: String,
    val version: Long
)
data class PushAuthenticatedPayload(
    val mac: String,
    val payload: String,
    val version: Long
)
data class RichPushNotificationPayload(
    val expiresAtMs: Long,
    val hostEndpoint: String,
    val issuedAtMs: Long,
    val notification: com.kompi.app.protocol.generated.AgentNotification,
    val notificationId: String,
    val registrationId: String,
    val version: Long
)
data class ReplyPushNotificationPayload(
    val conversationId: String,
    val expiresAtMs: Long,
    val hostEndpoint: String,
    val issuedAtMs: Long,
    val notificationId: String,
    val registrationId: String,
    val version: Long
)
data class SessionChangedPayload(
    val session: com.kompi.app.protocol.generated.ConversationMetadata
)
data class RequestChangedPayload(
    val request: com.kompi.app.protocol.generated.RequestSummary
)
data class ResponseCompletedPayload(
    val finalSequence: Long,
    val outcome: String,
    val requestId: String
)
data class SettingsChangedPayload(
    val settings: com.kompi.app.protocol.generated.EffectiveSettings
)
data class AttachmentChangedPayload(
    val complete: Boolean,
    val expired: Boolean,
    val receivedBytes: Long,
    val totalBytes: Long? = null,
    val uploadId: String
)
@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")
@com.fasterxml.jackson.annotation.JsonSubTypes(value=[com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=DurableEvent.SessionChanged::class,name="session-changed"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=DurableEvent.RequestChanged::class,name="request-changed"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=DurableEvent.ResponseCompleted::class,name="response-completed"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=DurableEvent.SettingsChanged::class,name="settings-changed"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=DurableEvent.AttachmentChanged::class,name="attachment-changed")])
sealed interface DurableEvent {
    data class SessionChanged(val payload:SessionChangedPayload):DurableEvent
    data class RequestChanged(val payload:RequestChangedPayload):DurableEvent
    data class ResponseCompleted(val payload:ResponseCompletedPayload):DurableEvent
    data class SettingsChanged(val payload:SettingsChangedPayload):DurableEvent
    data class AttachmentChanged(val payload:AttachmentChangedPayload):DurableEvent
}
data class PiUiDialogSelectPayload(
    val options: List<String>,
    val timeoutMs: Long? = null,
    val title: String
)
data class PiUiDialogConfirmPayload(
    val message: String? = null,
    val timeoutMs: Long? = null,
    val title: String
)
data class PiUiDialogInputPayload(
    val placeholder: String? = null,
    val timeoutMs: Long? = null,
    val title: String
)
data class PiUiDialogEditorPayload(
    val prefill: String? = null,
    val timeoutMs: Long? = null,
    val title: String
)
@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")
@com.fasterxml.jackson.annotation.JsonSubTypes(value=[com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiDialog.Select::class,name="select"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiDialog.Confirm::class,name="confirm"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiDialog.Input::class,name="input"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiDialog.Editor::class,name="editor")])
sealed interface PiUiDialog {
    data class Select(val payload:PiUiDialogSelectPayload):PiUiDialog
    data class Confirm(val payload:PiUiDialogConfirmPayload):PiUiDialog
    data class Input(val payload:PiUiDialogInputPayload):PiUiDialog
    data class Editor(val payload:PiUiDialogEditorPayload):PiUiDialog
}
class PiUiAnswerCancelledPayload { @com.fasterxml.jackson.annotation.JsonValue fun value():Map<String,String> = emptyMap() }
data class PiUiAnswerSelectedPayload(
    val index: Int
)
data class PiUiAnswerConfirmedPayload(
    val confirmed: Boolean
)
data class PiUiAnswerTextPayload(
    val text: String
)
@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")
@com.fasterxml.jackson.annotation.JsonSubTypes(value=[com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiAnswer.Cancelled::class,name="cancelled"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiAnswer.Selected::class,name="selected"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiAnswer.Confirmed::class,name="confirmed"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=PiUiAnswer.Text::class,name="text")])
sealed interface PiUiAnswer {
    data class Cancelled(val payload:PiUiAnswerCancelledPayload):PiUiAnswer
    data class Selected(val payload:PiUiAnswerSelectedPayload):PiUiAnswer
    data class Confirmed(val payload:PiUiAnswerConfirmedPayload):PiUiAnswer
    data class Text(val payload:PiUiAnswerTextPayload):PiUiAnswer
}
data class NotificationStyleBigTextPayload(
    val text: String
)
data class NotificationStyleInboxPayload(
    val lines: List<String>
)
data class NotificationStyleBigPicturePayload(
    val image: com.kompi.app.protocol.generated.NotificationImage
)
@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")
@com.fasterxml.jackson.annotation.JsonSubTypes(value=[com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationStyle.BigText::class,name="big-text"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationStyle.Inbox::class,name="inbox"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationStyle.BigPicture::class,name="big-picture")])
sealed interface NotificationStyle {
    data class BigText(val payload:NotificationStyleBigTextPayload):NotificationStyle
    data class Inbox(val payload:NotificationStyleInboxPayload):NotificationStyle
    data class BigPicture(val payload:NotificationStyleBigPicturePayload):NotificationStyle
}
class NotificationTargetOpenKompiPayload { @com.fasterxml.jackson.annotation.JsonValue fun value():Map<String,String> = emptyMap() }
data class NotificationTargetHttpsPayload(
    val url: String
)
@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")
@com.fasterxml.jackson.annotation.JsonSubTypes(value=[com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationTarget.OpenKompi::class,name="open-kompi"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationTarget.Https::class,name="https")])
sealed interface NotificationTarget {
    data class OpenKompi(val payload:NotificationTargetOpenKompiPayload):NotificationTarget
    data class Https(val payload:NotificationTargetHttpsPayload):NotificationTarget
}
class NotificationProgressIndeterminatePayload { @com.fasterxml.jackson.annotation.JsonValue fun value():Map<String,String> = emptyMap() }
data class NotificationProgressDeterminatePayload(
    val percent: Long
)
@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")
@com.fasterxml.jackson.annotation.JsonSubTypes(value=[com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationProgress.Indeterminate::class,name="indeterminate"),com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=NotificationProgress.Determinate::class,name="determinate")])
sealed interface NotificationProgress {
    data class Indeterminate(val payload:NotificationProgressIndeterminatePayload):NotificationProgress
    data class Determinate(val payload:NotificationProgressDeterminatePayload):NotificationProgress
}
sealed interface WireMessage {
    @get:JsonIgnore val type:String
    data object ModelsGet:WireMessage { override val type:String get()="models-get" }
    data class ModelsResult(
        val catalog: com.kompi.app.protocol.generated.ModelCatalog
    ):WireMessage { override val type:String get()="models-result" }
    data object PiSnapshotGet:WireMessage { override val type:String get()="pi-snapshot-get" }
    data class PiConversationSnapshotGet(
        val conversationId: String
    ):WireMessage { override val type:String get()="pi-conversation-snapshot-get" }
    data class ConversationStreamOpen(
        val conversationId: String
    ):WireMessage { override val type:String get()="conversation-stream-open" }
    data class PiArchiveGet(
        val conversationId: String
    ):WireMessage { override val type:String get()="pi-archive-get" }
    data class PiFrame(
        val frame: com.kompi.app.protocol.generated.PiFrame
    ):WireMessage { override val type:String get()="pi-frame" }
    data object PiResetRequired:WireMessage { override val type:String get()="pi-reset-required" }
    data object PiSessionEmpty:WireMessage { override val type:String get()="pi-session-empty" }
    data class GenerationStats(
        val requestId: String,
        val stats: com.kompi.app.protocol.generated.GenerationStats
    ):WireMessage { override val type:String get()="generation-stats" }
    data class PiUiState(
        val requests: List<com.kompi.app.protocol.generated.PendingPiUiRequest>
    ):WireMessage { override val type:String get()="pi-ui-state" }
    data class PiConversationUiState(
        val conversationId: String,
        val requests: List<com.kompi.app.protocol.generated.PendingPiUiRequest>
    ):WireMessage { override val type:String get()="pi-conversation-ui-state" }
    data class PiUiResponse(
        val answer: com.kompi.app.protocol.generated.PiUiAnswer,
        val conversationId: String,
        val interactionId: String
    ):WireMessage { override val type:String get()="pi-ui-response" }
    data class AndroidToolRequest(
        val actionJson: String? = null,
        val conversationId: String,
        val requestId: String,
        val timeoutMs: Long,
        val tool: String
    ):WireMessage { override val type:String get()="android-tool-request" }
    data class AndroidToolResponse(
        val conversationId: String,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val requestId: String,
        val resultJson: String? = null,
        val success: Boolean
    ):WireMessage { override val type:String get()="android-tool-response" }
    data class AndroidToolResolved(
        val conversationId: String,
        val requestId: String
    ):WireMessage { override val type:String get()="android-tool-resolved" }
    data class DeviceNotificationRequest(
        val body: String,
        val requestId: String,
        val timeoutMs: Long,
        val title: String
    ):WireMessage { override val type:String get()="device-notification-request" }
    data class DeviceRichNotificationRequest(
        val notification: com.kompi.app.protocol.generated.AgentNotification,
        val requestId: String,
        val timeoutMs: Long
    ):WireMessage { override val type:String get()="device-rich-notification-request" }
    data class DeviceReplyNotificationRequest(
        val reply: com.kompi.app.protocol.generated.ReplyNotification,
        val requestId: String,
        val timeoutMs: Long
    ):WireMessage { override val type:String get()="device-reply-notification-request" }
    data class DeviceNotificationResponse(
        val code: String? = null,
        val message: String? = null,
        val requestId: String,
        val status: String
    ):WireMessage { override val type:String get()="device-notification-response" }
    data class PushRegister(
        val registration: com.kompi.app.protocol.generated.PushRegistration
    ):WireMessage { override val type:String get()="push-register" }
    data class PushUnregister(
        val registrationId: String
    ):WireMessage { override val type:String get()="push-unregister" }
    data class PushRegistrationResult(
        val code: String? = null,
        val registrationId: String,
        val status: String
    ):WireMessage { override val type:String get()="push-registration-result" }
    data class Hello(
        val capabilities: List<String>,
        val endpoint: String? = null,
        val idleTimeoutSeconds: Int? = null,
        val session: String? = null
    ):WireMessage { override val type:String get()="hello" }
    data class Pair(
        val name: String,
        val secret: String
    ):WireMessage { override val type:String get()="pair" }
    data class Paired(
        val endpoint: String
    ):WireMessage { override val type:String get()="paired" }
    data class Prompt(
        val attachments: List<String>? = null,
        val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration,
        val delivery: String,
        val text: String
    ):WireMessage { override val type:String get()="prompt" }
    data class TimedPrompt(
        val attachments: List<String>? = null,
        val attemptId: String,
        val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration,
        val delivery: String,
        val text: String
    ):WireMessage { override val type:String get()="timed-prompt" }
    data class ChatTimingGet(
        val cursor: com.kompi.app.protocol.generated.ChatTimingCursor? = null
    ):WireMessage { override val type:String get()="chat-timing-get" }
    data class ChatTimingPage(
        val hasMore: Boolean,
        val rows: List<com.kompi.app.protocol.generated.ChatTimingRow>,
        val storeId: String
    ):WireMessage { override val type:String get()="chat-timing-page" }
    data class StreamOpen(
        val role: String
    ):WireMessage { override val type:String get()="stream-open" }
    data class Sync(
        val after: String? = null
    ):WireMessage { override val type:String get()="sync" }
    data class SyncPage(
        val after: String? = null,
        val limit: Int
    ):WireMessage { override val type:String get()="sync-page" }
    data class RequestPage(
        val after: String? = null,
        val limit: Int
    ):WireMessage { override val type:String get()="request-page" }
    data class RequestPageResult(
        val nextCursor: String? = null,
        val requests: List<com.kompi.app.protocol.generated.RequestSummary>
    ):WireMessage { override val type:String get()="request-page-result" }
    data class SessionPage(
        val after: String? = null,
        val limit: Int
    ):WireMessage { override val type:String get()="session-page" }
    data class SessionPageResult(
        val nextCursor: String? = null,
        val sessions: List<com.kompi.app.protocol.generated.ConversationMetadata>
    ):WireMessage { override val type:String get()="session-page-result" }
    data class PiHistoryPage(
        val after: String? = null,
        val limit: Int
    ):WireMessage { override val type:String get()="pi-history-page" }
    data class PiHistoryPageResult(
        val nextCursor: String? = null,
        val sessions: List<com.kompi.app.protocol.generated.ConversationMetadata>
    ):WireMessage { override val type:String get()="pi-history-page-result" }
    data class SyncSnapshot(
        val cursor: String,
        val hasMoreRequests: Boolean,
        val hasMoreSessions: Boolean,
        val outstandingRequests: List<com.kompi.app.protocol.generated.RequestSummary>,
        val sessions: List<com.kompi.app.protocol.generated.ConversationMetadata>,
        val settings: com.kompi.app.protocol.generated.EffectiveSettings,
        val state: String
    ):WireMessage { override val type:String get()="sync-snapshot" }
    data class SyncEvents(
        val events: List<com.kompi.app.protocol.generated.SyncEvent>,
        val nextCursor: String
    ):WireMessage { override val type:String get()="sync-events" }
    data class SyncResetRequired(
        val currentCursor: String
    ):WireMessage { override val type:String get()="sync-reset-required" }
    data object SettingsGet:WireMessage { override val type:String get()="settings-get" }
    data class SettingsResult(
        val settings: com.kompi.app.protocol.generated.EffectiveSettings
    ):WireMessage { override val type:String get()="settings-result" }
    data class SettingsUpdate(
        val settings: com.kompi.app.protocol.generated.EffectiveSettings,
        val updateId: String
    ):WireMessage { override val type:String get()="settings-update" }
    data class SettingsConfirmed(
        val settings: com.kompi.app.protocol.generated.EffectiveSettings,
        val updateId: String
    ):WireMessage { override val type:String get()="settings-confirmed" }
    data class SettingsRejected(
        val code: String,
        val message: String,
        val updateId: String
    ):WireMessage { override val type:String get()="settings-rejected" }
    data class PreacceptanceRejected(
        val code: String,
        val message: String,
        val requestId: String,
        val retryable: Boolean
    ):WireMessage { override val type:String get()="preacceptance-rejected" }
    data class ResponseComplete(
        val conversationId: String,
        val finalSequence: Long,
        val outcome: String,
        val requestId: String
    ):WireMessage { override val type:String get()="response-complete" }
    data class ConversationClose(
        val conversationId: String
    ):WireMessage { override val type:String get()="conversation-close" }
    data class ConversationDelete(
        val conversationId: String
    ):WireMessage { override val type:String get()="conversation-delete" }
    data class ConversationDeleted(
        val conversationId: String
    ):WireMessage { override val type:String get()="conversation-deleted" }
    data class ConversationActivate(
        val conversationId: String,
        val expectedCurrent: String? = null
    ):WireMessage { override val type:String get()="conversation-activate" }
    data class ConversationPrompt(
        val attachments: List<String>? = null,
        val attemptId: String? = null,
        val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration,
        val conversationId: String,
        val createIfMissing: Boolean? = null,
        val delivery: String,
        val text: String
    ):WireMessage { override val type:String get()="conversation-prompt" }
    data class AttachmentExpired(
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-expired" }
    data class ArtifactGet(
        val artifactId: String,
        val conversationId: String
    ):WireMessage { override val type:String get()="artifact-get" }
    data class ArtifactReady(
        val artifact: com.kompi.app.protocol.generated.OfferedArtifact
    ):WireMessage { override val type:String get()="artifact-ready" }
    data class ArtifactData(
        val data: ByteArray
    ):WireMessage { override val type:String get()="artifact-data" }
    data object ArtifactEnd:WireMessage { override val type:String get()="artifact-end" }
    data class AttachmentOffer(
        val fileName: String,
        val length: Long,
        val mediaType: String,
        val sha256: String,
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-offer" }
    data class AttachmentReady(
        val chunkSize: Int,
        val offset: Long,
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-ready" }
    data class AttachmentChunk(
        val data: ByteArray,
        val offset: Long,
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-chunk" }
    data class AttachmentProgress(
        val receivedBytes: Long,
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-progress" }
    data class AttachmentFinish(
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-finish" }
    data class AttachmentAccepted(
        val fileName: String,
        val length: Long,
        val mediaType: String,
        val sha256: String,
        val uploadId: String
    ):WireMessage { override val type:String get()="attachment-accepted" }
    data class Ack(
        val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null,
        val conversationId: String? = null,
        val idleTimeoutSeconds: Int? = null,
        val sessionId: String? = null,
        val state: String
    ):WireMessage { override val type:String get()="ack" }
    data class Status(
        val activity: String,
        val conversationId: String? = null,
        val sessionId: String? = null,
        val state: String
    ):WireMessage { override val type:String get()="status" }
    data class Cancel(
        val target: String
    ):WireMessage { override val type:String get()="cancel" }
    data class Error(
        val code: String,
        val conversationId: String? = null,
        val message: String,
        val sessionId: String? = null
    ):WireMessage { override val type:String get()="error" }
    data class Conversation(
        val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null,
        val conversationId: String,
        val createdAt: String,
        val expiresAt: String,
        val idleTimeoutSeconds: Int,
        val sessionId: String,
        val sessionName: String? = null,
        val state: String
    ):WireMessage { override val type:String get()="conversation" }
    data object Ping:WireMessage { override val type:String get()="ping" }
    data object Pong:WireMessage { override val type:String get()="pong" }
}
class WireFormatException(message:String):IllegalArgumentException(message)
private class CborGuard(private val data:ByteArray) {
    private var p=0
    fun verify(){val (major,n)=head();if(major!=5||n !in 1..CBOR_MAX_MAP_ENTRIES.toLong())bad("root map");val keys=mutableSetOf<String>();repeat(n.toInt()){val key=text();if(!keys.add(key))bad("duplicate key");item(1)};if(p!=data.size)bad("trailing CBOR")}
    private fun byte():Int{if(p>=data.size)bad("truncated CBOR");return data[p++].toInt() and 255}
    private fun read(count:Int):Long{if(data.size-p<count)bad("truncated CBOR");var n=0L;repeat(count){n=(n shl 8) or byte().toLong()};return n}
    private fun head():Pair<Int,Long>{val first=byte();val ai=first and 31;val n=when{ai<24->ai.toLong();ai==24->read(1);ai==25->read(2);ai==26->read(4);ai==27->read(8);else->bad("indefinite CBOR")};if(n<0)bad("CBOR integer overflow");return (first ushr 5) to n}
    private fun text():String{val (m,n)=head();if(m!=3||n>MAX_PAYLOAD.toLong()||n>(data.size-p).toLong())bad("invalid CBOR text");val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);val value=decoder.decode(ByteBuffer.wrap(data,p,n.toInt())).toString();p+=n.toInt();return value}
    private fun item(depth:Int){if(depth>CBOR_MAX_DEPTH)bad("CBOR depth");val first=data.getOrNull(p)?.toInt()?.and(255)?:bad("truncated CBOR");if(first ushr 5==7){p++;if(first !in setOf(0xf4,0xf5,0xf6))bad("unsupported CBOR simple value");return};val (m,n)=head();when(m){0,1->Unit;2->{if(n>ATTACHMENT_CHUNK_MAX_BYTES.toLong()||n>(data.size-p).toLong())bad("CBOR byte string length");p+=n.toInt()};3->{if(n>(data.size-p).toLong())bad("CBOR text length");val d=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);d.decode(ByteBuffer.wrap(data,p,n.toInt()));p+=n.toInt()};4->{if(n>CBOR_MAX_ARRAY_ENTRIES)bad("CBOR array limit");repeat(n.toInt()){item(depth+1)}};5->{if(n>CBOR_MAX_MAP_ENTRIES)bad("CBOR map limit");val keys=mutableSetOf<String>();repeat(n.toInt()){if(!keys.add(text()))bad("duplicate key");item(depth+1)}};else->bad("unsupported CBOR type")}}
    private fun bad(reason:String):Nothing=throw WireFormatException(reason)
}
object WireCodec {
    private data class Shape(val fields:Set<String>,val required:Set<String>)
    private val shapes=mapOf(
        "models-get" to Shape(setOf(), setOf()),
        "models-result" to Shape(setOf("catalog"), setOf("catalog")),
        "pi-snapshot-get" to Shape(setOf(), setOf()),
        "pi-conversation-snapshot-get" to Shape(setOf("conversationId"), setOf("conversationId")),
        "conversation-stream-open" to Shape(setOf("conversationId"), setOf("conversationId")),
        "pi-archive-get" to Shape(setOf("conversationId"), setOf("conversationId")),
        "pi-frame" to Shape(setOf("frame"), setOf("frame")),
        "pi-reset-required" to Shape(setOf(), setOf()),
        "pi-session-empty" to Shape(setOf(), setOf()),
        "generation-stats" to Shape(setOf("requestId","stats"), setOf("requestId","stats")),
        "pi-ui-state" to Shape(setOf("requests"), setOf("requests")),
        "pi-conversation-ui-state" to Shape(setOf("conversationId","requests"), setOf("conversationId","requests")),
        "pi-ui-response" to Shape(setOf("answer","conversationId","interactionId"), setOf("conversationId","interactionId","answer")),
        "android-tool-request" to Shape(setOf("actionJson","conversationId","requestId","timeoutMs","tool"), setOf("conversationId","requestId","tool","timeoutMs")),
        "android-tool-response" to Shape(setOf("conversationId","errorCode","errorMessage","requestId","resultJson","success"), setOf("conversationId","requestId","success")),
        "android-tool-resolved" to Shape(setOf("conversationId","requestId"), setOf("conversationId","requestId")),
        "device-notification-request" to Shape(setOf("body","requestId","timeoutMs","title"), setOf("requestId","title","body","timeoutMs")),
        "device-rich-notification-request" to Shape(setOf("notification","requestId","timeoutMs"), setOf("requestId","notification","timeoutMs")),
        "device-reply-notification-request" to Shape(setOf("reply","requestId","timeoutMs"), setOf("requestId","reply","timeoutMs")),
        "device-notification-response" to Shape(setOf("code","message","requestId","status"), setOf("requestId","status")),
        "push-register" to Shape(setOf("registration"), setOf("registration")),
        "push-unregister" to Shape(setOf("registrationId"), setOf("registrationId")),
        "push-registration-result" to Shape(setOf("code","registrationId","status"), setOf("registrationId","status")),
        "hello" to Shape(setOf("capabilities","endpoint","idleTimeoutSeconds","session"), setOf("capabilities")),
        "pair" to Shape(setOf("name","secret"), setOf("secret","name")),
        "paired" to Shape(setOf("endpoint"), setOf("endpoint")),
        "prompt" to Shape(setOf("attachments","configuration","delivery","text"), setOf("text","delivery","configuration")),
        "timed-prompt" to Shape(setOf("attachments","attemptId","configuration","delivery","text"), setOf("attemptId","text","delivery","configuration")),
        "chat-timing-get" to Shape(setOf("cursor"), setOf()),
        "chat-timing-page" to Shape(setOf("hasMore","rows","storeId"), setOf("storeId","rows","hasMore")),
        "stream-open" to Shape(setOf("role"), setOf("role")),
        "sync" to Shape(setOf("after"), setOf()),
        "sync-page" to Shape(setOf("after","limit"), setOf("limit")),
        "request-page" to Shape(setOf("after","limit"), setOf("limit")),
        "request-page-result" to Shape(setOf("nextCursor","requests"), setOf("requests")),
        "session-page" to Shape(setOf("after","limit"), setOf("limit")),
        "session-page-result" to Shape(setOf("nextCursor","sessions"), setOf("sessions")),
        "pi-history-page" to Shape(setOf("after","limit"), setOf("limit")),
        "pi-history-page-result" to Shape(setOf("nextCursor","sessions"), setOf("sessions")),
        "sync-snapshot" to Shape(setOf("cursor","hasMoreRequests","hasMoreSessions","outstandingRequests","sessions","settings","state"), setOf("cursor","state","settings","sessions","outstandingRequests","hasMoreSessions","hasMoreRequests")),
        "sync-events" to Shape(setOf("events","nextCursor"), setOf("events","nextCursor")),
        "sync-reset-required" to Shape(setOf("currentCursor"), setOf("currentCursor")),
        "settings-get" to Shape(setOf(), setOf()),
        "settings-result" to Shape(setOf("settings"), setOf("settings")),
        "settings-update" to Shape(setOf("settings","updateId"), setOf("updateId","settings")),
        "settings-confirmed" to Shape(setOf("settings","updateId"), setOf("updateId","settings")),
        "settings-rejected" to Shape(setOf("code","message","updateId"), setOf("updateId","code","message")),
        "preacceptance-rejected" to Shape(setOf("code","message","requestId","retryable"), setOf("requestId","code","message","retryable")),
        "response-complete" to Shape(setOf("conversationId","finalSequence","outcome","requestId"), setOf("requestId","conversationId","outcome","finalSequence")),
        "conversation-close" to Shape(setOf("conversationId"), setOf("conversationId")),
        "conversation-delete" to Shape(setOf("conversationId"), setOf("conversationId")),
        "conversation-deleted" to Shape(setOf("conversationId"), setOf("conversationId")),
        "conversation-activate" to Shape(setOf("conversationId","expectedCurrent"), setOf("conversationId")),
        "conversation-prompt" to Shape(setOf("attachments","attemptId","configuration","conversationId","createIfMissing","delivery","text"), setOf("conversationId","text","delivery","configuration")),
        "attachment-expired" to Shape(setOf("uploadId"), setOf("uploadId")),
        "artifact-get" to Shape(setOf("artifactId","conversationId"), setOf("conversationId","artifactId")),
        "artifact-ready" to Shape(setOf("artifact"), setOf("artifact")),
        "artifact-data" to Shape(setOf("data"), setOf("data")),
        "artifact-end" to Shape(setOf(), setOf()),
        "attachment-offer" to Shape(setOf("fileName","length","mediaType","sha256","uploadId"), setOf("uploadId","fileName","mediaType","length","sha256")),
        "attachment-ready" to Shape(setOf("chunkSize","offset","uploadId"), setOf("uploadId","offset","chunkSize")),
        "attachment-chunk" to Shape(setOf("data","offset","uploadId"), setOf("uploadId","offset","data")),
        "attachment-progress" to Shape(setOf("receivedBytes","uploadId"), setOf("uploadId","receivedBytes")),
        "attachment-finish" to Shape(setOf("uploadId"), setOf("uploadId")),
        "attachment-accepted" to Shape(setOf("fileName","length","mediaType","sha256","uploadId"), setOf("uploadId","fileName","mediaType","length","sha256")),
        "ack" to Shape(setOf("configuration","conversationId","idleTimeoutSeconds","sessionId","state"), setOf("state")),
        "status" to Shape(setOf("activity","conversationId","sessionId","state"), setOf("state","activity")),
        "cancel" to Shape(setOf("target"), setOf("target")),
        "error" to Shape(setOf("code","conversationId","message","sessionId"), setOf("code","message")),
        "conversation" to Shape(setOf("configuration","conversationId","createdAt","expiresAt","idleTimeoutSeconds","sessionId","sessionName","state"), setOf("sessionId","conversationId","state","createdAt","expiresAt","idleTimeoutSeconds")),
        "ping" to Shape(setOf(), setOf()),
        "pong" to Shape(setOf(), setOf())
    )
    private val mapper=ObjectMapper(CBORFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).registerKotlinModule()
    private val idPattern=Regex(REQUEST_ID_PATTERN)
    fun decode(bytes:ByteArray):EnvelopeV3 {
        if(bytes.isEmpty()||bytes.size>MAX_PAYLOAD)fail("payload size")
        CborGuard(bytes).verify()
        try {
            val root=mapper.readTree(bytes) ?: fail("empty CBOR")
            if(!root.isObject||root.fieldNames().asSequence().toSet()!=setOf("v","id","message"))fail("invalid envelope keys")
            val v=root.path("v");if(!v.isIntegralNumber||v.intValue()!=VERSION)fail("unsupported version")
            val id=root.path("id").takeIf{it.isTextual}?.asText()?:fail("id must be text");if(id.length !in REQUEST_ID_MIN_LENGTH..REQUEST_ID_MAX_LENGTH||!idPattern.matches(id))fail("invalid id")
            val outer=root.path("message");if(!outer.isObject||outer.fieldNames().asSequence().toSet()!=setOf("type","payload"))fail("invalid message envelope")
            val tag=outer.path("type").takeIf{it.isTextual}?.asText()?:fail("message type must be text")
            val payloadNode=outer.path("payload");if(!payloadNode.isObject)fail("payload must be map")
            val payload=payloadNode as ObjectNode
            validatePayload(tag,payload)
            val message=when(tag){
            "models-get" -> mapper.treeToValue(payload, WireMessage.ModelsGet::class.java)
            "models-result" -> mapper.treeToValue(payload, WireMessage.ModelsResult::class.java)
            "pi-snapshot-get" -> mapper.treeToValue(payload, WireMessage.PiSnapshotGet::class.java)
            "pi-conversation-snapshot-get" -> mapper.treeToValue(payload, WireMessage.PiConversationSnapshotGet::class.java)
            "conversation-stream-open" -> mapper.treeToValue(payload, WireMessage.ConversationStreamOpen::class.java)
            "pi-archive-get" -> mapper.treeToValue(payload, WireMessage.PiArchiveGet::class.java)
            "pi-frame" -> mapper.treeToValue(payload, WireMessage.PiFrame::class.java)
            "pi-reset-required" -> mapper.treeToValue(payload, WireMessage.PiResetRequired::class.java)
            "pi-session-empty" -> mapper.treeToValue(payload, WireMessage.PiSessionEmpty::class.java)
            "generation-stats" -> mapper.treeToValue(payload, WireMessage.GenerationStats::class.java)
            "pi-ui-state" -> mapper.treeToValue(payload, WireMessage.PiUiState::class.java)
            "pi-conversation-ui-state" -> mapper.treeToValue(payload, WireMessage.PiConversationUiState::class.java)
            "pi-ui-response" -> mapper.treeToValue(payload, WireMessage.PiUiResponse::class.java)
            "android-tool-request" -> mapper.treeToValue(payload, WireMessage.AndroidToolRequest::class.java)
            "android-tool-response" -> mapper.treeToValue(payload, WireMessage.AndroidToolResponse::class.java)
            "android-tool-resolved" -> mapper.treeToValue(payload, WireMessage.AndroidToolResolved::class.java)
            "device-notification-request" -> mapper.treeToValue(payload, WireMessage.DeviceNotificationRequest::class.java)
            "device-rich-notification-request" -> mapper.treeToValue(payload, WireMessage.DeviceRichNotificationRequest::class.java)
            "device-reply-notification-request" -> mapper.treeToValue(payload, WireMessage.DeviceReplyNotificationRequest::class.java)
            "device-notification-response" -> mapper.treeToValue(payload, WireMessage.DeviceNotificationResponse::class.java)
            "push-register" -> mapper.treeToValue(payload, WireMessage.PushRegister::class.java)
            "push-unregister" -> mapper.treeToValue(payload, WireMessage.PushUnregister::class.java)
            "push-registration-result" -> mapper.treeToValue(payload, WireMessage.PushRegistrationResult::class.java)
            "hello" -> mapper.treeToValue(payload, WireMessage.Hello::class.java)
            "pair" -> mapper.treeToValue(payload, WireMessage.Pair::class.java)
            "paired" -> mapper.treeToValue(payload, WireMessage.Paired::class.java)
            "prompt" -> mapper.treeToValue(payload, WireMessage.Prompt::class.java)
            "timed-prompt" -> mapper.treeToValue(payload, WireMessage.TimedPrompt::class.java)
            "chat-timing-get" -> mapper.treeToValue(payload, WireMessage.ChatTimingGet::class.java)
            "chat-timing-page" -> mapper.treeToValue(payload, WireMessage.ChatTimingPage::class.java)
            "stream-open" -> mapper.treeToValue(payload, WireMessage.StreamOpen::class.java)
            "sync" -> mapper.treeToValue(payload, WireMessage.Sync::class.java)
            "sync-page" -> mapper.treeToValue(payload, WireMessage.SyncPage::class.java)
            "request-page" -> mapper.treeToValue(payload, WireMessage.RequestPage::class.java)
            "request-page-result" -> mapper.treeToValue(payload, WireMessage.RequestPageResult::class.java)
            "session-page" -> mapper.treeToValue(payload, WireMessage.SessionPage::class.java)
            "session-page-result" -> mapper.treeToValue(payload, WireMessage.SessionPageResult::class.java)
            "pi-history-page" -> mapper.treeToValue(payload, WireMessage.PiHistoryPage::class.java)
            "pi-history-page-result" -> mapper.treeToValue(payload, WireMessage.PiHistoryPageResult::class.java)
            "sync-snapshot" -> mapper.treeToValue(payload, WireMessage.SyncSnapshot::class.java)
            "sync-events" -> mapper.treeToValue(payload, WireMessage.SyncEvents::class.java)
            "sync-reset-required" -> mapper.treeToValue(payload, WireMessage.SyncResetRequired::class.java)
            "settings-get" -> mapper.treeToValue(payload, WireMessage.SettingsGet::class.java)
            "settings-result" -> mapper.treeToValue(payload, WireMessage.SettingsResult::class.java)
            "settings-update" -> mapper.treeToValue(payload, WireMessage.SettingsUpdate::class.java)
            "settings-confirmed" -> mapper.treeToValue(payload, WireMessage.SettingsConfirmed::class.java)
            "settings-rejected" -> mapper.treeToValue(payload, WireMessage.SettingsRejected::class.java)
            "preacceptance-rejected" -> mapper.treeToValue(payload, WireMessage.PreacceptanceRejected::class.java)
            "response-complete" -> mapper.treeToValue(payload, WireMessage.ResponseComplete::class.java)
            "conversation-close" -> mapper.treeToValue(payload, WireMessage.ConversationClose::class.java)
            "conversation-delete" -> mapper.treeToValue(payload, WireMessage.ConversationDelete::class.java)
            "conversation-deleted" -> mapper.treeToValue(payload, WireMessage.ConversationDeleted::class.java)
            "conversation-activate" -> mapper.treeToValue(payload, WireMessage.ConversationActivate::class.java)
            "conversation-prompt" -> mapper.treeToValue(payload, WireMessage.ConversationPrompt::class.java)
            "attachment-expired" -> mapper.treeToValue(payload, WireMessage.AttachmentExpired::class.java)
            "artifact-get" -> mapper.treeToValue(payload, WireMessage.ArtifactGet::class.java)
            "artifact-ready" -> mapper.treeToValue(payload, WireMessage.ArtifactReady::class.java)
            "artifact-data" -> mapper.treeToValue(payload, WireMessage.ArtifactData::class.java)
            "artifact-end" -> mapper.treeToValue(payload, WireMessage.ArtifactEnd::class.java)
            "attachment-offer" -> mapper.treeToValue(payload, WireMessage.AttachmentOffer::class.java)
            "attachment-ready" -> mapper.treeToValue(payload, WireMessage.AttachmentReady::class.java)
            "attachment-chunk" -> mapper.treeToValue(payload, WireMessage.AttachmentChunk::class.java)
            "attachment-progress" -> mapper.treeToValue(payload, WireMessage.AttachmentProgress::class.java)
            "attachment-finish" -> mapper.treeToValue(payload, WireMessage.AttachmentFinish::class.java)
            "attachment-accepted" -> mapper.treeToValue(payload, WireMessage.AttachmentAccepted::class.java)
            "ack" -> mapper.treeToValue(payload, WireMessage.Ack::class.java)
            "status" -> mapper.treeToValue(payload, WireMessage.Status::class.java)
            "cancel" -> mapper.treeToValue(payload, WireMessage.Cancel::class.java)
            "error" -> mapper.treeToValue(payload, WireMessage.Error::class.java)
            "conversation" -> mapper.treeToValue(payload, WireMessage.Conversation::class.java)
            "ping" -> mapper.treeToValue(payload, WireMessage.Ping::class.java)
            "pong" -> mapper.treeToValue(payload, WireMessage.Pong::class.java)
                else -> fail("unknown message type")
            }
            return EnvelopeV3(VERSION,id,message)
        }catch(e:WireFormatException){throw e}catch(e:Exception){throw WireFormatException("invalid CBOR: "+e.message)}
    }
    fun encode(envelope:EnvelopeV3):ByteArray {
        if(envelope.v!=VERSION||envelope.id.length !in REQUEST_ID_MIN_LENGTH..REQUEST_ID_MAX_LENGTH||!idPattern.matches(envelope.id))fail("invalid envelope")
        val (tag,payload)=when(val message=envelope.message){
            is WireMessage.ModelsGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ModelsResult -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiSnapshotGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiConversationSnapshotGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ConversationStreamOpen -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiArchiveGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiFrame -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiResetRequired -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiSessionEmpty -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.GenerationStats -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiUiState -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiConversationUiState -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiUiResponse -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AndroidToolRequest -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AndroidToolResponse -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AndroidToolResolved -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.DeviceNotificationRequest -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.DeviceRichNotificationRequest -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.DeviceReplyNotificationRequest -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.DeviceNotificationResponse -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PushRegister -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PushUnregister -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PushRegistrationResult -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Hello -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Pair -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Paired -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Prompt -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.TimedPrompt -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ChatTimingGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ChatTimingPage -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.StreamOpen -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Sync -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SyncPage -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.RequestPage -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.RequestPageResult -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SessionPage -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SessionPageResult -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiHistoryPage -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PiHistoryPageResult -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SyncSnapshot -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SyncEvents -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SyncResetRequired -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SettingsGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SettingsResult -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SettingsUpdate -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SettingsConfirmed -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.SettingsRejected -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.PreacceptanceRejected -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ResponseComplete -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ConversationClose -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ConversationDelete -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ConversationDeleted -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ConversationActivate -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ConversationPrompt -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentExpired -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ArtifactGet -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ArtifactReady -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ArtifactData -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.ArtifactEnd -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentOffer -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentReady -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentChunk -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentProgress -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentFinish -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.AttachmentAccepted -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Ack -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Status -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Cancel -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Error -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Conversation -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Ping -> message.type to mapper.valueToTree<ObjectNode>(message)
            is WireMessage.Pong -> message.type to mapper.valueToTree<ObjectNode>(message)
        }
        validatePayload(tag,payload)
        val root=mapper.createObjectNode().put("v",VERSION).put("id",envelope.id)
        val m=mapper.createObjectNode().put("type",tag);m.set<ObjectNode>("payload",payload);root.set<ObjectNode>("message",m)
        val output=ByteArrayOutputStream()
        (mapper.factory.createGenerator(output) as CBORGenerator).use { writeDefinite(it,root) }
        val bytes=output.toByteArray();if(bytes.isEmpty()||bytes.size>MAX_PAYLOAD)fail("payload size");return bytes
    }
    // Jackson's ordinary long-String serializer emits indefinite chunks. Our wire grammar
    // deliberately permits definite containers/text only, including large Unicode prompts.
    private fun writeDefinite(writer:CBORGenerator,node:JsonNode) {
        when {
            node.isObject -> {
                writer.writeStartObject(node.size())
                node.fields().forEachRemaining { (key,value) -> writer.writeFieldName(SerializedString(key)); writeDefinite(writer,value) }
                writer.writeEndObject()
            }
            node.isArray -> { writer.writeStartArray(node.size()); node.forEach { writeDefinite(writer,it) }; writer.writeEndArray() }
            node.isTextual -> { val utf8=node.textValue().encodeToByteArray(throwOnInvalidSequence=true); writer.writeUTF8String(utf8,0,utf8.size) }
            node.isBinary -> writer.writeBinary(node.binaryValue())
            node.isIntegralNumber && node.canConvertToLong() -> writer.writeNumber(node.longValue())
            node.isBoolean -> writer.writeBoolean(node.booleanValue())
            node.isNull -> writer.writeNull()
            else -> fail("unsupported CBOR value")
        }
    }
    private fun validatePayload(tag:String,payload:ObjectNode) {
        val shape=shapes[tag]?:fail("unknown message type")
        val keys=payload.fieldNames().asSequence().toSet();if(keys.any{it !in shape.fields}||!keys.containsAll(shape.required))fail("unknown or missing payload field")
        when(tag) {
            "prompt" -> { payload.get("text")?.takeIf{it.isTextual}?.let{payload.put("text",it.asText().trim{c -> c.code in 9..13 || c.code==0x85 || Character.isSpaceChar(c)})} }
            "timed-prompt" -> { payload.get("text")?.takeIf{it.isTextual}?.let{payload.put("text",it.asText().trim{c -> c.code in 9..13 || c.code==0x85 || Character.isSpaceChar(c)})} }
            "conversation-prompt" -> { payload.get("text")?.takeIf{it.isTextual}?.let{payload.put("text",it.asText().trim{c -> c.code in 9..13 || c.code==0x85 || Character.isSpaceChar(c)})} }
            else -> Unit
        }
        when(tag) {
            "models-get", "models-result", "pi-snapshot-get", "pi-conversation-snapshot-get", "conversation-stream-open", "pi-archive-get", "pi-frame", "pi-reset-required", "pi-session-empty", "generation-stats", "pi-ui-state", "pi-conversation-ui-state" -> validateGroup0(tag,payload)
            "pi-ui-response", "android-tool-request", "android-tool-response", "android-tool-resolved", "device-notification-request", "device-rich-notification-request", "device-reply-notification-request", "device-notification-response", "push-register", "push-unregister", "push-registration-result", "hello" -> validateGroup12(tag,payload)
            "pair", "paired", "prompt", "timed-prompt", "chat-timing-get", "chat-timing-page", "stream-open", "sync", "sync-page", "request-page", "request-page-result", "session-page" -> validateGroup24(tag,payload)
            "session-page-result", "pi-history-page", "pi-history-page-result", "sync-snapshot", "sync-events", "sync-reset-required", "settings-get", "settings-result", "settings-update", "settings-confirmed", "settings-rejected", "preacceptance-rejected" -> validateGroup36(tag,payload)
            "response-complete", "conversation-close", "conversation-delete", "conversation-deleted", "conversation-activate", "conversation-prompt", "attachment-expired", "artifact-get", "artifact-ready", "artifact-data", "artifact-end", "attachment-offer" -> validateGroup48(tag,payload)
            "attachment-ready", "attachment-chunk", "attachment-progress", "attachment-finish", "attachment-accepted", "ack", "status", "cancel", "error", "conversation", "ping", "pong" -> validateGroup60(tag,payload)
            else -> fail("unknown message type")
        }
    }
    private fun validateGroup0(tag:String,payload:ObjectNode) {
        when(tag) {
            "models-get" -> {

            }
            "models-result" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("catalog"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("catalog")}) fail("unknown dialog field"); payload.get("catalog")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("models"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("defaultConfiguration","models")}) fail("unknown dialog field"); n0.get("defaultConfiguration")?.let { n2 -> if(!n2.isNull) { if(!n2.isObject) fail("dialog field must be a map"); if(!n2.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n2.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n2.get("effort")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n2.get("modelId")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>256) fail("dialog text too long") }; n2.get("provider")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>128) fail("dialog text too long") } } }; n0.get("models")?.let { n2 -> if(!n2.isArray) fail("dialog field must be an array"); if(n2.size()>128) fail("too many dialog entries"); n2.forEach { n3 -> if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","name","efforts"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("efforts","modelId","name","provider")}) fail("unknown dialog field"); n3.get("efforts")?.let { n5 -> if(!n5.isArray) fail("dialog field must be an array"); if(n5.size()<1) fail("not enough dialog choices"); if(n5.size()>7) fail("too many dialog entries"); n5.forEach { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") } }; n3.get("modelId")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>256) fail("dialog text too long") }; n3.get("name")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>256) fail("dialog text too long") }; n3.get("provider")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>128) fail("dialog text too long") } } } } }
            "pi-snapshot-get" -> {

            }
            "pi-conversation-snapshot-get" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            }
            "conversation-stream-open" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            }
            "pi-archive-get" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            }
            "pi-frame" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("frame"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("frame")}) fail("unknown dialog field"); payload.get("frame")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("conversationId","sessionId","epoch","sequence","part","last","kind","json"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("conversationId","epoch","json","kind","last","part","requestId","sequence","sessionId")}) fail("unknown dialog field"); n0.get("conversationId")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n2.asText()).toString().equals(n2.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n0.get("epoch")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>128) fail("dialog text too long") }; n0.get("json")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length>8192) fail("dialog text too long") }; n0.get("kind")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText() !in setOf("event","snapshot","entries","settled")) fail("invalid field alternative") }; n0.get("last")?.let { n2 -> if(!n2.isBoolean) fail("dialog field must be a boolean") }; n0.get("part")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") }; n0.get("requestId")?.let { n2 -> if(!n2.isNull) { if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n2.asText())) fail("invalid dialog identity") } }; n0.get("sequence")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") }; n0.get("sessionId")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>128) fail("dialog text too long") } } }
            "pi-reset-required" -> {

            }
            "pi-session-empty" -> {

            }
            "generation-stats" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","stats"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("requestId","stats")}) fail("unknown dialog field"); payload.get("requestId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") }; payload.get("stats")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("totalMs","textChunks","assistantMessages"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("assistantMessages","daemonTotalMs","firstTextMs","model","outputTokens","provider","reasoningTokens","textChunks","totalMs")}) fail("unknown dialog field"); n0.get("assistantMessages")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") }; n0.get("daemonTotalMs")?.let { n2 -> if(!n2.isNull) { if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") } }; n0.get("firstTextMs")?.let { n2 -> if(!n2.isNull) { if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") } }; n0.get("model")?.let { n2 -> if(!n2.isNull) { if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length>256) fail("dialog text too long") } }; n0.get("outputTokens")?.let { n2 -> if(!n2.isNull) { if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") } }; n0.get("provider")?.let { n2 -> if(!n2.isNull) { if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length>128) fail("dialog text too long") } }; n0.get("reasoningTokens")?.let { n2 -> if(!n2.isNull) { if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") } }; n0.get("textChunks")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") }; n0.get("totalMs")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small") } } }
            "pi-ui-state" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("requests"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("requests")}) fail("unknown dialog field"); payload.get("requests")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>1) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("conversationId","interactionId","dialog"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("conversationId","dialog","interactionId")}) fail("unknown dialog field"); n1.get("conversationId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n3.asText()).toString().equals(n3.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n1.get("dialog")?.let { n3 -> if(!n3.isObject) fail("dialog must be a map"); when(n3.path("type").asText()) { "select" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("title","options"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("options","timeoutMs","title")}) fail("unknown dialog field"); n6.get("options")?.let { n7 -> if(!n7.isArray) fail("dialog field must be an array"); if(n7.size()<1) fail("not enough dialog choices"); if(n7.size()>32) fail("too many dialog entries"); n7.forEach { n8 -> if(!n8.isTextual) fail("dialog field must be text"); if(n8.asText().length>1024) fail("dialog text too long") } }; n6.get("timeoutMs")?.let { n7 -> if(!n7.isNull) { if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<1L) fail("dialog number too small"); if(n7.asLong()>600000L) fail("dialog number too large") } }; n6.get("title")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>2048) fail("dialog text too long") } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="select") fail("invalid dialog tag") } }; "confirm" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("title"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("message","timeoutMs","title")}) fail("unknown dialog field"); n6.get("message")?.let { n7 -> if(!n7.isNull) { if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>4096) fail("dialog text too long") } }; n6.get("timeoutMs")?.let { n7 -> if(!n7.isNull) { if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<1L) fail("dialog number too small"); if(n7.asLong()>600000L) fail("dialog number too large") } }; n6.get("title")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>2048) fail("dialog text too long") } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="confirm") fail("invalid dialog tag") } }; "input" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("title"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("placeholder","timeoutMs","title")}) fail("unknown dialog field"); n6.get("placeholder")?.let { n7 -> if(!n7.isNull) { if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>2048) fail("dialog text too long") } }; n6.get("timeoutMs")?.let { n7 -> if(!n7.isNull) { if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<1L) fail("dialog number too small"); if(n7.asLong()>600000L) fail("dialog number too large") } }; n6.get("title")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>2048) fail("dialog text too long") } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="input") fail("invalid dialog tag") } }; "editor" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("title"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("prefill","timeoutMs","title")}) fail("unknown dialog field"); n6.get("prefill")?.let { n7 -> if(!n7.isNull) { if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>16000) fail("dialog text too long") } }; n6.get("timeoutMs")?.let { n7 -> if(!n7.isNull) { if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<1L) fail("dialog number too small"); if(n7.asLong()>600000L) fail("dialog number too large") } }; n6.get("title")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length>2048) fail("dialog text too long") } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="editor") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } }; n1.get("interactionId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n3.asText())) fail("invalid dialog identity") } } } }
            "pi-conversation-ui-state" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("requests")?.let { n -> if(!n.isNull) { if(!n.isArray) fail("requests must be array"); if(n.size()>1) fail("requests too many"); n.forEach{ item -> if(!item.isObject) fail("invalid requests entry"); val itemKeys=item.fieldNames().asSequence().toSet(); if(itemKeys.any{it !in setOf("conversationId","dialog","interactionId")}||!itemKeys.containsAll(setOf("conversationId","interactionId","dialog"))) fail("invalid requests entry") } } }
            }
            else -> fail("unknown message type")
        }
    }
    private fun validateGroup12(tag:String,payload:ObjectNode) {
        when(tag) {
            "pi-ui-response" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("conversationId","interactionId","answer"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("answer","conversationId","interactionId")}) fail("unknown dialog field"); payload.get("answer")?.let { n0 -> if(!n0.isObject) fail("dialog must be a map"); when(n0.path("type").asText()) { "cancelled" -> { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n0.get("payload")?.let { n3 -> if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n0.get("type")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText()!="cancelled") fail("invalid dialog tag") } }; "selected" -> { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n0.get("payload")?.let { n3 -> if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("index"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("index")}) fail("unknown dialog field"); n3.get("index")?.let { n4 -> if(!n4.isIntegralNumber||!n4.canConvertToLong()) fail("dialog field must be an integer"); if(n4.asLong()<0L) fail("dialog number too small"); if(n4.asLong()>31L) fail("dialog number too large") } }; n0.get("type")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText()!="selected") fail("invalid dialog tag") } }; "confirmed" -> { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n0.get("payload")?.let { n3 -> if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("confirmed"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("confirmed")}) fail("unknown dialog field"); n3.get("confirmed")?.let { n4 -> if(!n4.isBoolean) fail("dialog field must be a boolean") } }; n0.get("type")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText()!="confirmed") fail("invalid dialog tag") } }; "text" -> { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n0.get("payload")?.let { n3 -> if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("text"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("text")}) fail("unknown dialog field"); n3.get("text")?.let { n4 -> if(!n4.isTextual) fail("dialog field must be text"); if(n4.asText().length>16000) fail("dialog text too long") } }; n0.get("type")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText()!="text") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } }; payload.get("conversationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; payload.get("interactionId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") } }
            "android-tool-request" -> {
            payload.get("actionJson")?.let { n -> if(!n.isNull) {  } }
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            payload.get("timeoutMs")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("timeoutMs must be integer"); if(n.asLong()<1L) fail("timeoutMs too small"); if(n.asLong()>60000L) fail("timeoutMs too large") } }
            payload.get("tool")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("tool must be text"); if(n.asText() !in setOf("phone_action","screen_context","send_notification")) fail("invalid tool") } }
            }
            "android-tool-response" -> { if(!(((payload.path("errorCode").isMissingNode || payload.path("errorCode").isNull) && (payload.path("errorMessage").isMissingNode || payload.path("errorMessage").isNull) && (payload.path("resultJson").isTextual) && (payload.path("success").isMissingNode || (payload.path("success").isBoolean && payload.path("success").booleanValue() == true))) || ((payload.path("errorCode").isTextual && payload.path("errorCode").asText().length >= 1) && (payload.path("errorMessage").isTextual) && (payload.path("success").isMissingNode || (payload.path("success").isBoolean && payload.path("success").booleanValue() == false))))) fail("invalid payload alternative"); if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("conversationId","requestId","success"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("conversationId","errorCode","errorMessage","requestId","resultJson","success")}) fail("unknown dialog field"); payload.get("conversationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; payload.get("errorCode")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length>64) fail("dialog text too long") } }; payload.get("errorMessage")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length>512) fail("dialog text too long") } }; payload.get("requestId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") }; payload.get("resultJson")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().encodeToByteArray().size>16384) fail("Android tool result too large") } }; payload.get("success")?.let { n0 -> if(!n0.isBoolean) fail("dialog field must be a boolean") } }
            "android-tool-resolved" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            }
            "device-notification-request" -> {
            payload.get("body")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("body must be text"); if(n.asText().length<1) fail("body too short"); if(n.asText().length>2000) fail("body too long") } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            payload.get("timeoutMs")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("timeoutMs must be integer"); if(n.asLong()<1L) fail("timeoutMs too small"); if(n.asLong()>30000L) fail("timeoutMs too large") } }
            payload.get("title")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("title must be text"); if(n.asText().length<1) fail("title too short"); if(n.asText().length>120) fail("title too long") } }
            }
            "device-rich-notification-request" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","notification","timeoutMs"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("notification","requestId","timeoutMs")}) fail("unknown dialog field"); payload.get("notification")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("title","body"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("body","rich","title")}) fail("unknown dialog field"); n0.get("body")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>2000) fail("dialog text too long") }; n0.get("rich")?.let { n2 -> if(!n2.isNull) { if(!n2.isObject) fail("dialog field must be a map"); if(!n2.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n2.fieldNames().asSequence().any{it !in setOf<String>("buttons","group","progress","silent","style","subtext","tag","timeoutMs")}) fail("unknown dialog field"); n2.get("buttons")?.let { n5 -> if(!n5.isNull) { if(!n5.isArray) fail("dialog field must be an array"); if(n5.size()>3) fail("too many dialog entries"); n5.forEach { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("label","target"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("label","target")}) fail("unknown dialog field"); n7.get("label")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText().length<1) fail("dialog text too short"); if(n9.asText().length>40) fail("dialog text too long") }; n7.get("target")?.let { n9 -> if(!n9.isObject) fail("dialog must be a map"); when(n9.path("type").asText()) { "open-kompi" -> { if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n9.get("payload")?.let { n12 -> if(!n12.isObject) fail("dialog field must be a map"); if(!n12.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n12.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n9.get("type")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText()!="open-kompi") fail("invalid dialog tag") } }; "https" -> { if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n9.get("payload")?.let { n12 -> if(!n12.isObject) fail("dialog field must be a map"); if(!n12.fieldNames().asSequence().toSet().containsAll(setOf<String>("url"))) fail("missing dialog field"); if(n12.fieldNames().asSequence().any{it !in setOf<String>("url")}) fail("unknown dialog field"); n12.get("url")?.let { n13 -> if(!n13.isTextual) fail("dialog field must be text"); if(n13.asText().length<9) fail("dialog text too short"); if(n13.asText().length>2048) fail("dialog text too long"); if(!Regex("^https://[^/@\\s\\x00-\\x20\\x7f]+(?:[/?#][^\\s\\x00-\\x20\\x7f]*)?$").matches(n13.asText())) fail("invalid dialog identity") } }; n9.get("type")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText()!="https") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } } } }; n2.get("group")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>64) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n5.asText())) fail("invalid dialog identity") } }; n2.get("progress")?.let { n5 -> if(!n5.isNull) { if(!n5.isObject) fail("dialog must be a map"); when(n5.path("type").asText()) { "indeterminate" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="indeterminate") fail("invalid dialog tag") } }; "determinate" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("percent"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("percent")}) fail("unknown dialog field"); n9.get("percent")?.let { n10 -> if(!n10.isIntegralNumber||!n10.canConvertToLong()) fail("dialog field must be an integer"); if(n10.asLong()<0L) fail("dialog number too small"); if(n10.asLong()>100L) fail("dialog number too large") } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="determinate") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } }; n2.get("silent")?.let { n5 -> if(!n5.isNull) { if(!n5.isBoolean) fail("dialog field must be a boolean") } }; n2.get("style")?.let { n5 -> if(!n5.isNull) { if(!n5.isObject) fail("dialog must be a map"); when(n5.path("type").asText()) { "big-text" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("text"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("text")}) fail("unknown dialog field"); n9.get("text")?.let { n10 -> if(!n10.isTextual) fail("dialog field must be text"); if(n10.asText().length<1) fail("dialog text too short"); if(n10.asText().length>8000) fail("dialog text too long") } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="big-text") fail("invalid dialog tag") } }; "inbox" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("lines"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("lines")}) fail("unknown dialog field"); n9.get("lines")?.let { n10 -> if(!n10.isArray) fail("dialog field must be an array"); if(n10.size()<1) fail("not enough dialog choices"); if(n10.size()>8) fail("too many dialog entries"); n10.forEach { n11 -> if(!n11.isTextual) fail("dialog field must be text"); if(n11.asText().length<1) fail("dialog text too short"); if(n11.asText().length>500) fail("dialog text too long") } } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="inbox") fail("invalid dialog tag") } }; "big-picture" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("image"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("image")}) fail("unknown dialog field"); n9.get("image")?.let { n10 -> if(!n10.isObject) fail("dialog field must be a map"); if(!n10.fieldNames().asSequence().toSet().containsAll(setOf<String>("base64"))) fail("missing dialog field"); if(n10.fieldNames().asSequence().any{it !in setOf<String>("base64")}) fail("unknown dialog field"); n10.get("base64")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText().length<1) fail("dialog text too short"); if(n12.asText().length>16384) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9_-]+$").matches(n12.asText())) fail("invalid dialog identity") } } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="big-picture") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } }; n2.get("subtext")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>120) fail("dialog text too long") } }; n2.get("tag")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>64) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n5.asText())) fail("invalid dialog identity") } }; n2.get("timeoutMs")?.let { n5 -> if(!n5.isNull) { if(!n5.isIntegralNumber||!n5.canConvertToLong()) fail("dialog field must be an integer"); if(n5.asLong()<1000L) fail("dialog number too small"); if(n5.asLong()>86400000L) fail("dialog number too large") } } } }; n0.get("title")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>120) fail("dialog text too long") } }; payload.get("requestId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") }; payload.get("timeoutMs")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer"); if(n0.asLong()<1L) fail("dialog number too small"); if(n0.asLong()>30000L) fail("dialog number too large") } }
            "device-reply-notification-request" -> {
            payload.get("reply")?.let { n -> if(!n.isNull) { if(!n.isObject) fail("reply must be object") } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            payload.get("timeoutMs")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("timeoutMs must be integer"); if(n.asLong()<1L) fail("timeoutMs too small"); if(n.asLong()>30000L) fail("timeoutMs too large") } }
            }
            "device-notification-response" -> {
            payload.get("code")?.let { n -> if(!n.isNull) {  } }
            payload.get("message")?.let { n -> if(!n.isNull) {  } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            payload.get("status")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("status must be text"); if(n.asText() !in setOf("posted","not-posted","unknown")) fail("invalid status") } }
            }
            "push-register" -> {
            payload.get("registration")?.let { n -> if(!n.isNull) { if(!n.isObject) fail("registration must be object") } }
            }
            "push-unregister" -> {
            payload.get("registrationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("registrationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid registrationId") } }
            }
            "push-registration-result" -> {
            payload.get("code")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("code must be text"); if(n.asText() !in setOf("origin-not-allowed","invalid-endpoint","invalid-keys","registration-limit","registration-mismatch","push-disabled","provider-unavailable")) fail("invalid code") } }
            payload.get("registrationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("registrationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid registrationId") } }
            payload.get("status")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("status must be text"); if(n.asText() !in setOf("registered","unregistered","rejected")) fail("invalid status") } }
            }
            "hello" -> {
            payload.get("capabilities")?.let { n -> if(!n.isNull) { if(!n.isArray) fail("capabilities must be array"); if(n.size()>32) fail("capabilities too many") } }
            payload.get("endpoint")?.let { n -> if(!n.isNull) {  } }
            payload.get("idleTimeoutSeconds")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("idleTimeoutSeconds must be integer"); if(n.asLong()<60L) fail("idleTimeoutSeconds too small"); if(n.asLong()>86400L) fail("idleTimeoutSeconds too large") } }
            payload.get("session")?.let { n -> if(!n.isNull) {  } }
            }
            else -> fail("unknown message type")
        }
    }
    private fun validateGroup24(tag:String,payload:ObjectNode) {
        when(tag) {
            "pair" -> {
            payload.get("name")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("name must be text"); if(n.asText().length<1) fail("name too short"); if(n.asText().length>128) fail("name too long") } }
            payload.get("secret")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("secret must be text"); if(n.asText().length<32) fail("secret too short"); if(n.asText().length>256) fail("secret too long") } }
            }
            "paired" -> {
            payload.get("endpoint")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("endpoint must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n.asText())) fail("invalid endpoint") } }
            }
            "prompt" -> { if(!(((payload.path("text").isTextual && Regex("[^\\u0009-\\u000d\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]").containsMatchIn(payload.path("text").asText()))) || ((payload.path("attachments").isArray && payload.path("attachments").size() >= 1)))) fail("invalid payload alternative"); if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("text","delivery","configuration"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("attachments","configuration","delivery","text")}) fail("unknown dialog field"); payload.get("attachments")?.let { n0 -> if(!n0.isNull) { if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>16) fail("too many dialog entries"); n0.forEach { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n2.asText()).toString().equals(n2.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } } }; payload.get("configuration")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n0.get("effort")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n0.get("modelId")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>256) fail("dialog text too long") }; n0.get("provider")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>128) fail("dialog text too long") } }; payload.get("delivery")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText() !in setOf("followUp")) fail("invalid field alternative") }; payload.get("text")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().codePointCount(0,n0.asText().length)>16000) fail("dialog text too long") } }
            "timed-prompt" -> { if(!(((payload.path("text").isTextual && Regex("[^\\u0009-\\u000d\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]").containsMatchIn(payload.path("text").asText()))) || ((payload.path("attachments").isArray && payload.path("attachments").size() >= 1)))) fail("invalid payload alternative"); if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("attemptId","text","delivery","configuration"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("attachments","attemptId","configuration","delivery","text")}) fail("unknown dialog field"); payload.get("attachments")?.let { n0 -> if(!n0.isNull) { if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>16) fail("too many dialog entries"); n0.forEach { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n2.asText()).toString().equals(n2.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } } }; payload.get("attemptId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; payload.get("configuration")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n0.get("effort")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n0.get("modelId")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>256) fail("dialog text too long") }; n0.get("provider")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>128) fail("dialog text too long") } }; payload.get("delivery")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText() !in setOf("followUp")) fail("invalid field alternative") }; payload.get("text")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().codePointCount(0,n0.asText().length)>16000) fail("dialog text too long") } }
            "chat-timing-get" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("cursor")}) fail("unknown dialog field"); payload.get("cursor")?.let { n0 -> if(!n0.isNull) { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("storeId","after"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("after","storeId")}) fail("unknown dialog field"); n0.get("after")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length>19) fail("dialog text too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n3.asText())) fail("invalid dialog identity") }; n0.get("storeId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n3.asText()).toString().equals(n3.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } } } }
            "chat-timing-page" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("storeId","rows","hasMore"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("hasMore","rows","storeId")}) fail("unknown dialog field"); payload.get("hasMore")?.let { n0 -> if(!n0.isBoolean) fail("dialog field must be a boolean") }; payload.get("rows")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>32) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("cursor","event"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("cursor","event")}) fail("unknown dialog field"); n1.get("cursor")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length>19) fail("dialog text too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n3.asText())) fail("invalid dialog identity") }; n1.get("event")?.let { n3 -> if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","attemptId","stamp"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("attemptId","conversationId","requestId","responseRequestId","stamp")}) fail("unknown dialog field"); n3.get("attemptId")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n5.asText()).toString().equals(n5.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n3.get("conversationId")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n5.asText()).toString().equals(n5.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }; n3.get("requestId")?.let { n5 -> if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n5.asText())) fail("invalid dialog identity") }; n3.get("responseRequestId")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n5.asText())) fail("invalid dialog identity") } }; n3.get("stamp")?.let { n5 -> if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("clockId","monotonicMs","wallMs","stage"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("clockId","monotonicMs","stage","wallMs")}) fail("unknown dialog field"); n5.get("clockId")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n7.asText()).toString().equals(n7.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n5.get("monotonicMs")?.let { n7 -> if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<0L) fail("dialog number too small") }; n5.get("stage")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText() !in setOf("daemon_receipt","daemon_catalog_ready","daemon_sweep_ready","daemon_claim_committed","daemon_claim_replay","daemon_ack_written","daemon_ack_write_failed","daemon_rejected","daemon_worker_dispatch","daemon_worker_written","daemon_worker_final","daemon_worker_failed","daemon_completion_committed","daemon_handler_failed","daemon_follow_up_staging","daemon_follow_up_staged","daemon_follow_up_released","worker_assistant_start","worker_first_text","worker_assistant_last_end","worker_agent_settled","worker_follow_up_boundary")) fail("invalid field alternative") }; n5.get("wallMs")?.let { n7 -> if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<0L) fail("dialog number too small") } } } } }; payload.get("storeId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }
            "stream-open" -> {
            payload.get("role")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("role must be text"); if(n.asText() !in setOf("chat-control","attachment-transfer")) fail("invalid role") } }
            }
            "sync" -> {
            payload.get("after")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("after must be text"); if(n.asText().length>19) fail("after too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n.asText())) fail("invalid after") } }
            }
            "sync-page" -> {
            payload.get("after")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("after must be text"); if(n.asText().length>19) fail("after too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n.asText())) fail("invalid after") } }
            payload.get("limit")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("limit must be integer"); if(n.asLong()<1L) fail("limit too small"); if(n.asLong()>32L) fail("limit too large") } }
            }
            "request-page" -> {
            payload.get("after")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("after must be text"); if(n.asText().length<1) fail("after too short"); if(n.asText().length>128) fail("after too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid after") } }
            payload.get("limit")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("limit must be integer"); if(n.asLong()<1L) fail("limit too small"); if(n.asLong()>32L) fail("limit too large") } }
            }
            "request-page-result" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("requests"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("nextCursor","requests")}) fail("unknown dialog field"); payload.get("nextCursor")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") } }; payload.get("requests")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>32) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","conversationId","outcome","nextSequence"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","nextSequence","outcome","requestId")}) fail("unknown dialog field"); n1.get("configuration")?.let { n3 -> if(!n3.isNull) { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n3.get("effort")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n3.get("modelId")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>256) fail("dialog text too long") }; n3.get("provider")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>128) fail("dialog text too long") } } }; n1.get("conversationId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n3.asText()).toString().equals(n3.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n1.get("nextSequence")?.let { n3 -> if(!n3.isIntegralNumber||!n3.canConvertToLong()) fail("dialog field must be an integer"); if(n3.asLong()<0L) fail("dialog number too small") }; n1.get("outcome")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText() !in setOf("accepted","queued","running","completed","failed","cancelled","uncertain","rejected")) fail("invalid field alternative") }; n1.get("requestId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n3.asText())) fail("invalid dialog identity") } } } }
            "session-page" -> {
            payload.get("after")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("after must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid after") } }
            payload.get("limit")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("limit must be integer"); if(n.asLong()<1L) fail("limit too small"); if(n.asLong()>32L) fail("limit too large") } }
            }
            else -> fail("unknown message type")
        }
    }
    private fun validateGroup36(tag:String,payload:ObjectNode) {
        when(tag) {
            "session-page-result" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("sessions"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("nextCursor","sessions")}) fail("unknown dialog field"); payload.get("nextCursor")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }; payload.get("sessions")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>32) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("sessionId","conversationId","state","createdAt","expiresAt","idleTimeoutSeconds"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","createdAt","expiresAt","idleTimeoutSeconds","sessionId","sessionName","state")}) fail("unknown dialog field"); n1.get("configuration")?.let { n3 -> if(!n3.isNull) { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n3.get("effort")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n3.get("modelId")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>256) fail("dialog text too long") }; n3.get("provider")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>128) fail("dialog text too long") } } }; n1.get("conversationId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n3.asText()).toString().equals(n3.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n1.get("createdAt")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text") }; n1.get("expiresAt")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text") }; n1.get("idleTimeoutSeconds")?.let { n3 -> if(!n3.isIntegralNumber||!n3.canConvertToLong()) fail("dialog field must be an integer"); if(n3.asLong()<60L) fail("dialog number too small"); if(n3.asLong()>86400L) fail("dialog number too large") }; n1.get("sessionId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long") }; n1.get("sessionName")?.let { n3 -> if(!n3.isNull) { if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length>512) fail("dialog text too long") } }; n1.get("state")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText() !in setOf("active","expired","closed","unavailable")) fail("invalid field alternative") } } } }
            "pi-history-page" -> {
            payload.get("after")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("after must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid after") } }
            payload.get("limit")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("limit must be integer"); if(n.asLong()<1L) fail("limit too small"); if(n.asLong()>32L) fail("limit too large") } }
            }
            "pi-history-page-result" -> {
            payload.get("nextCursor")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("nextCursor must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid nextCursor") } }
            payload.get("sessions")?.let { n -> if(!n.isNull) { if(!n.isArray) fail("sessions must be array"); if(n.size()>32) fail("sessions too many"); n.forEach{ item -> if(!item.isObject) fail("invalid sessions entry"); val itemKeys=item.fieldNames().asSequence().toSet(); if(itemKeys.any{it !in setOf("configuration","conversationId","createdAt","expiresAt","idleTimeoutSeconds","sessionId","sessionName","state")}||!itemKeys.containsAll(setOf("sessionId","conversationId","state","createdAt","expiresAt","idleTimeoutSeconds"))) fail("invalid sessions entry") } } }
            }
            "sync-snapshot" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("cursor","state","settings","sessions","outstandingRequests","hasMoreSessions","hasMoreRequests"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("cursor","hasMoreRequests","hasMoreSessions","outstandingRequests","sessions","settings","state")}) fail("unknown dialog field"); payload.get("cursor")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length>19) fail("dialog text too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n0.asText())) fail("invalid dialog identity") }; payload.get("hasMoreRequests")?.let { n0 -> if(!n0.isBoolean) fail("dialog field must be a boolean") }; payload.get("hasMoreSessions")?.let { n0 -> if(!n0.isBoolean) fail("dialog field must be a boolean") }; payload.get("outstandingRequests")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>32) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","conversationId","outcome","nextSequence"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","nextSequence","outcome","requestId")}) fail("unknown dialog field"); n1.get("configuration")?.let { n3 -> if(!n3.isNull) { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n3.get("effort")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n3.get("modelId")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>256) fail("dialog text too long") }; n3.get("provider")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>128) fail("dialog text too long") } } }; n1.get("conversationId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n3.asText()).toString().equals(n3.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n1.get("nextSequence")?.let { n3 -> if(!n3.isIntegralNumber||!n3.canConvertToLong()) fail("dialog field must be an integer"); if(n3.asLong()<0L) fail("dialog number too small") }; n1.get("outcome")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText() !in setOf("accepted","queued","running","completed","failed","cancelled","uncertain","rejected")) fail("invalid field alternative") }; n1.get("requestId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n3.asText())) fail("invalid dialog identity") } } }; payload.get("sessions")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>32) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("sessionId","conversationId","state","createdAt","expiresAt","idleTimeoutSeconds"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","createdAt","expiresAt","idleTimeoutSeconds","sessionId","sessionName","state")}) fail("unknown dialog field"); n1.get("configuration")?.let { n3 -> if(!n3.isNull) { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n3.get("effort")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n3.get("modelId")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>256) fail("dialog text too long") }; n3.get("provider")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText().length<1) fail("dialog text too short"); if(n6.asText().length>128) fail("dialog text too long") } } }; n1.get("conversationId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n3.asText()).toString().equals(n3.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n1.get("createdAt")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text") }; n1.get("expiresAt")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text") }; n1.get("idleTimeoutSeconds")?.let { n3 -> if(!n3.isIntegralNumber||!n3.canConvertToLong()) fail("dialog field must be an integer"); if(n3.asLong()<60L) fail("dialog number too small"); if(n3.asLong()>86400L) fail("dialog number too large") }; n1.get("sessionId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long") }; n1.get("sessionName")?.let { n3 -> if(!n3.isNull) { if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length>512) fail("dialog text too long") } }; n1.get("state")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText() !in setOf("active","expired","closed","unavailable")) fail("invalid field alternative") } } }; payload.get("settings")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("idleTimeoutSeconds","uploadRetentionSeconds"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("idleTimeoutSeconds","uploadRetentionSeconds")}) fail("unknown dialog field"); n0.get("idleTimeoutSeconds")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<60L) fail("dialog number too small"); if(n2.asLong()>86400L) fail("dialog number too large") }; n0.get("uploadRetentionSeconds")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<3600L) fail("dialog number too small"); if(n2.asLong()>2592000L) fail("dialog number too large") } }; payload.get("state")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText() !in setOf("connecting","synchronizing","live","disconnected")) fail("invalid field alternative") } }
            "sync-events" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("events","nextCursor"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("events","nextCursor")}) fail("unknown dialog field"); payload.get("events")?.let { n0 -> if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>128) fail("too many dialog entries"); n0.forEach { n1 -> if(!n1.isObject) fail("dialog field must be a map"); if(!n1.fieldNames().asSequence().toSet().containsAll(setOf<String>("cursor","event"))) fail("missing dialog field"); if(n1.fieldNames().asSequence().any{it !in setOf<String>("cursor","event")}) fail("unknown dialog field"); n1.get("cursor")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length>19) fail("dialog text too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n3.asText())) fail("invalid dialog identity") }; n1.get("event")?.let { n3 -> if(!n3.isObject) fail("dialog must be a map"); when(n3.path("type").asText()) { "session-changed" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("session"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("session")}) fail("unknown dialog field"); n6.get("session")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("sessionId","conversationId","state","createdAt","expiresAt","idleTimeoutSeconds"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","createdAt","expiresAt","idleTimeoutSeconds","sessionId","sessionName","state")}) fail("unknown dialog field"); n7.get("configuration")?.let { n9 -> if(!n9.isNull) { if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n9.get("effort")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n9.get("modelId")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText().length<1) fail("dialog text too short"); if(n12.asText().length>256) fail("dialog text too long") }; n9.get("provider")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText().length<1) fail("dialog text too short"); if(n12.asText().length>128) fail("dialog text too long") } } }; n7.get("conversationId")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n9.asText()).toString().equals(n9.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n7.get("createdAt")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text") }; n7.get("expiresAt")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text") }; n7.get("idleTimeoutSeconds")?.let { n9 -> if(!n9.isIntegralNumber||!n9.canConvertToLong()) fail("dialog field must be an integer"); if(n9.asLong()<60L) fail("dialog number too small"); if(n9.asLong()>86400L) fail("dialog number too large") }; n7.get("sessionId")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText().length<1) fail("dialog text too short"); if(n9.asText().length>128) fail("dialog text too long") }; n7.get("sessionName")?.let { n9 -> if(!n9.isNull) { if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText().length>512) fail("dialog text too long") } }; n7.get("state")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText() !in setOf("active","expired","closed","unavailable")) fail("invalid field alternative") } } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="session-changed") fail("invalid dialog tag") } }; "request-changed" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("request"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("request")}) fail("unknown dialog field"); n6.get("request")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","conversationId","outcome","nextSequence"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","nextSequence","outcome","requestId")}) fail("unknown dialog field"); n7.get("configuration")?.let { n9 -> if(!n9.isNull) { if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n9.get("effort")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n9.get("modelId")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText().length<1) fail("dialog text too short"); if(n12.asText().length>256) fail("dialog text too long") }; n9.get("provider")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText().length<1) fail("dialog text too short"); if(n12.asText().length>128) fail("dialog text too long") } } }; n7.get("conversationId")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n9.asText()).toString().equals(n9.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n7.get("nextSequence")?.let { n9 -> if(!n9.isIntegralNumber||!n9.canConvertToLong()) fail("dialog field must be an integer"); if(n9.asLong()<0L) fail("dialog number too small") }; n7.get("outcome")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText() !in setOf("accepted","queued","running","completed","failed","cancelled","uncertain","rejected")) fail("invalid field alternative") }; n7.get("requestId")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText().length<1) fail("dialog text too short"); if(n9.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n9.asText())) fail("invalid dialog identity") } } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="request-changed") fail("invalid dialog tag") } }; "response-completed" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("requestId","outcome","finalSequence"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("finalSequence","outcome","requestId")}) fail("unknown dialog field"); n6.get("finalSequence")?.let { n7 -> if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<0L) fail("dialog number too small") }; n6.get("outcome")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText() !in setOf("accepted","queued","running","completed","failed","cancelled","uncertain","rejected")) fail("invalid field alternative") }; n6.get("requestId")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length<1) fail("dialog text too short"); if(n7.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n7.asText())) fail("invalid dialog identity") } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="response-completed") fail("invalid dialog tag") } }; "settings-changed" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("settings"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("settings")}) fail("unknown dialog field"); n6.get("settings")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("idleTimeoutSeconds","uploadRetentionSeconds"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("idleTimeoutSeconds","uploadRetentionSeconds")}) fail("unknown dialog field"); n7.get("idleTimeoutSeconds")?.let { n9 -> if(!n9.isIntegralNumber||!n9.canConvertToLong()) fail("dialog field must be an integer"); if(n9.asLong()<60L) fail("dialog number too small"); if(n9.asLong()>86400L) fail("dialog number too large") }; n7.get("uploadRetentionSeconds")?.let { n9 -> if(!n9.isIntegralNumber||!n9.canConvertToLong()) fail("dialog field must be an integer"); if(n9.asLong()<3600L) fail("dialog number too small"); if(n9.asLong()>2592000L) fail("dialog number too large") } } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="settings-changed") fail("invalid dialog tag") } }; "attachment-changed" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n6 -> if(!n6.isObject) fail("dialog field must be a map"); if(!n6.fieldNames().asSequence().toSet().containsAll(setOf<String>("uploadId","receivedBytes","complete","expired"))) fail("missing dialog field"); if(n6.fieldNames().asSequence().any{it !in setOf<String>("complete","expired","receivedBytes","totalBytes","uploadId")}) fail("unknown dialog field"); n6.get("complete")?.let { n7 -> if(!n7.isBoolean) fail("dialog field must be a boolean") }; n6.get("expired")?.let { n7 -> if(!n7.isBoolean) fail("dialog field must be a boolean") }; n6.get("receivedBytes")?.let { n7 -> if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<0L) fail("dialog number too small") }; n6.get("totalBytes")?.let { n7 -> if(!n7.isNull) { if(!n7.isIntegralNumber||!n7.canConvertToLong()) fail("dialog field must be an integer"); if(n7.asLong()<0L) fail("dialog number too small") } }; n6.get("uploadId")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n7.asText()).toString().equals(n7.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }; n3.get("type")?.let { n6 -> if(!n6.isTextual) fail("dialog field must be text"); if(n6.asText()!="attachment-changed") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } } }; payload.get("nextCursor")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length>19) fail("dialog text too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n0.asText())) fail("invalid dialog identity") } }
            "sync-reset-required" -> {
            payload.get("currentCursor")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("currentCursor must be text"); if(n.asText().length>19) fail("currentCursor too long"); if(!Regex("^(0|[1-9][0-9]{0,17}|9223372036854775807|[1-8][0-9]{18}|9[0-1][0-9]{17}|92[0-1][0-9]{16}|922[0-2][0-9]{15}|9223[0-2][0-9]{14}|92233[0-6][0-9]{13}|922337[0-1][0-9]{12}|92233720[0-2][0-9]{10}|922337203[0-5][0-9]{9}|9223372036[0-7][0-9]{8}|92233720368[0-4][0-9]{7}|922337203685[0-3][0-9]{6}|9223372036854[0-6][0-9]{5}|92233720368547[0-6][0-9]{4}|922337203685477[0-4][0-9]{3}|9223372036854775[0-7][0-9]{2}|922337203685477580[0-6][0-9]{0})$").matches(n.asText())) fail("invalid currentCursor") } }
            }
            "settings-get" -> {

            }
            "settings-result" -> {
            payload.get("settings")?.let { n -> if(!n.isNull) { if(!n.isObject) fail("settings must be object") } }
            }
            "settings-update" -> {
            payload.get("settings")?.let { n -> if(!n.isNull) { if(!n.isObject) fail("settings must be object") } }
            payload.get("updateId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("updateId must be text"); if(n.asText().length<1) fail("updateId too short"); if(n.asText().length>128) fail("updateId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid updateId") } }
            }
            "settings-confirmed" -> {
            payload.get("settings")?.let { n -> if(!n.isNull) { if(!n.isObject) fail("settings must be object") } }
            payload.get("updateId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("updateId must be text"); if(n.asText().length<1) fail("updateId too short"); if(n.asText().length>128) fail("updateId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid updateId") } }
            }
            "settings-rejected" -> {
            payload.get("code")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("code must be text") } }
            payload.get("message")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("message must be text") } }
            payload.get("updateId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("updateId must be text"); if(n.asText().length<1) fail("updateId too short"); if(n.asText().length>128) fail("updateId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid updateId") } }
            }
            "preacceptance-rejected" -> {
            payload.get("code")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("code must be text") } }
            payload.get("message")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("message must be text") } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            payload.get("retryable")?.let { n -> if(!n.isNull) {  } }
            }
            else -> fail("unknown message type")
        }
    }
    private fun validateGroup48(tag:String,payload:ObjectNode) {
        when(tag) {
            "response-complete" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("finalSequence")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("finalSequence must be integer"); if(n.asLong()<0L) fail("finalSequence too small") } }
            payload.get("outcome")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("outcome must be text"); if(n.asText() !in setOf("accepted","queued","running","completed","failed","cancelled","uncertain","rejected")) fail("invalid outcome") } }
            payload.get("requestId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("requestId must be text"); if(n.asText().length<1) fail("requestId too short"); if(n.asText().length>128) fail("requestId too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid requestId") } }
            }
            "conversation-close" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            }
            "conversation-delete" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            }
            "conversation-deleted" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            }
            "conversation-activate" -> {
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("expectedCurrent")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("expectedCurrent must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid expectedCurrent") } }
            }
            "conversation-prompt" -> { if(!(((payload.path("text").isTextual && Regex("[^\\u0009-\\u000d\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]").containsMatchIn(payload.path("text").asText()))) || ((payload.path("attachments").isArray && payload.path("attachments").size() >= 1)))) fail("invalid payload alternative"); if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("conversationId","text","delivery","configuration"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("attachments","attemptId","configuration","conversationId","createIfMissing","delivery","text")}) fail("unknown dialog field"); payload.get("attachments")?.let { n0 -> if(!n0.isNull) { if(!n0.isArray) fail("dialog field must be an array"); if(n0.size()>16) fail("too many dialog entries"); n0.forEach { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n2.asText()).toString().equals(n2.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } } }; payload.get("attemptId")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }; payload.get("configuration")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n0.get("effort")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n0.get("modelId")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>256) fail("dialog text too long") }; n0.get("provider")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>128) fail("dialog text too long") } }; payload.get("conversationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; payload.get("createIfMissing")?.let { n0 -> if(!n0.isNull) { if(!n0.isBoolean) fail("dialog field must be a boolean") } }; payload.get("delivery")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText() !in setOf("followUp")) fail("invalid field alternative") }; payload.get("text")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().codePointCount(0,n0.asText().length)>16000) fail("dialog text too long") } }
            "attachment-expired" -> {
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            "artifact-get" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("conversationId","artifactId"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("artifactId","conversationId")}) fail("unknown dialog field"); payload.get("artifactId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; payload.get("conversationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }
            "artifact-ready" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("artifact"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("artifact")}) fail("unknown dialog field"); payload.get("artifact")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("artifactId","fileName","length","sha256"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("artifactId","fileName","length","sha256")}) fail("unknown dialog field"); n0.get("artifactId")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n2.asText()).toString().equals(n2.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; n0.get("fileName")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(!Regex("^[A-Za-z0-9]([A-Za-z0-9 ._()+-]{0,118}[A-Za-z0-9_()+-])?$").matches(n2.asText())) fail("invalid dialog identity") }; n0.get("length")?.let { n2 -> if(!n2.isIntegralNumber||!n2.canConvertToLong()) fail("dialog field must be an integer"); if(n2.asLong()<0L) fail("dialog number too small"); if(n2.asLong()>16777216L) fail("dialog number too large") }; n0.get("sha256")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n2.asText())) fail("invalid dialog identity") } } }
            "artifact-data" -> {
            payload.get("data")?.let { n -> if(!n.isNull) { if(!n.isBinary) fail("data must be CBOR bytes"); if(n.binaryValue().size !in 1..49152) fail("data too large") } }
            }
            "artifact-end" -> {

            }
            "attachment-offer" -> {
            payload.get("fileName")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("fileName must be text"); if(n.asText().length>255) fail("fileName too long") } }
            payload.get("length")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("length must be integer"); if(n.asLong()<0L) fail("length too small"); if(n.asLong()>26214400L) fail("length too large") } }
            payload.get("mediaType")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("mediaType must be text"); if(n.asText().length>127) fail("mediaType too long") } }
            payload.get("sha256")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("sha256 must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n.asText())) fail("invalid sha256") } }
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            else -> fail("unknown message type")
        }
    }
    private fun validateGroup60(tag:String,payload:ObjectNode) {
        when(tag) {
            "attachment-ready" -> {
            payload.get("chunkSize")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("chunkSize must be integer"); if(n.asLong()<1L) fail("chunkSize too small"); if(n.asLong()>49152L) fail("chunkSize too large") } }
            payload.get("offset")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("offset must be integer"); if(n.asLong()<0L) fail("offset too small") } }
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            "attachment-chunk" -> {
            payload.get("data")?.let { n -> if(!n.isNull) { if(!n.isBinary) fail("data must be CBOR bytes"); if(n.binaryValue().size !in 1..49152) fail("data too large") } }
            payload.get("offset")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("offset must be integer"); if(n.asLong()<0L) fail("offset too small") } }
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            "attachment-progress" -> {
            payload.get("receivedBytes")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("receivedBytes must be integer"); if(n.asLong()<0L) fail("receivedBytes too small") } }
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            "attachment-finish" -> {
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            "attachment-accepted" -> {
            payload.get("fileName")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("fileName must be text"); if(n.asText().length>255) fail("fileName too long") } }
            payload.get("length")?.let { n -> if(!n.isNull) { if(!n.isIntegralNumber) fail("length must be integer"); if(n.asLong()<0L) fail("length too small"); if(n.asLong()>26214400L) fail("length too large") } }
            payload.get("mediaType")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("mediaType must be text"); if(n.asText().length>127) fail("mediaType too long") } }
            payload.get("sha256")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("sha256 must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n.asText())) fail("invalid sha256") } }
            payload.get("uploadId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("uploadId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid uploadId") } }
            }
            "ack" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("state"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","idleTimeoutSeconds","sessionId","state")}) fail("unknown dialog field"); payload.get("configuration")?.let { n0 -> if(!n0.isNull) { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n0.get("effort")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n0.get("modelId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>256) fail("dialog text too long") }; n0.get("provider")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long") } } }; payload.get("conversationId")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") } }; payload.get("idleTimeoutSeconds")?.let { n0 -> if(!n0.isNull) { if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer"); if(n0.asLong()<60L) fail("dialog number too small"); if(n0.asLong()>86400L) fail("dialog number too large") } }; payload.get("sessionId")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long") } }; payload.get("state")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText() !in setOf("accepted","queued")) fail("invalid field alternative") } }
            "status" -> {
            payload.get("activity")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("activity must be text"); if(n.asText().length>2048) fail("activity too long") } }
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("sessionId")?.let { n -> if(!n.isNull) {  } }
            payload.get("state")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("state must be text"); if(n.asText() !in setOf("running")) fail("invalid state") } }
            }
            "cancel" -> {
            payload.get("target")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("target must be text"); if(n.asText().length<1) fail("target too short"); if(n.asText().length>128) fail("target too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n.asText())) fail("invalid target") } }
            }
            "error" -> {
            payload.get("code")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("code must be text"); if(n.asText().length<1) fail("code too short"); if(n.asText().length>64) fail("code too long") } }
            payload.get("conversationId")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("conversationId must be text"); if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail("invalid conversationId") } }
            payload.get("message")?.let { n -> if(!n.isNull) { if(!n.isTextual) fail("message must be text"); if(n.asText().length>2048) fail("message too long") } }
            payload.get("sessionId")?.let { n -> if(!n.isNull) {  } }
            }
            "conversation" -> { if(!payload.isObject) fail("dialog field must be a map"); if(!payload.fieldNames().asSequence().toSet().containsAll(setOf<String>("sessionId","conversationId","state","createdAt","expiresAt","idleTimeoutSeconds"))) fail("missing dialog field"); if(payload.fieldNames().asSequence().any{it !in setOf<String>("configuration","conversationId","createdAt","expiresAt","idleTimeoutSeconds","sessionId","sessionName","state")}) fail("unknown dialog field"); payload.get("configuration")?.let { n0 -> if(!n0.isNull) { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("provider","modelId","effort"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("effort","modelId","provider")}) fail("unknown dialog field"); n0.get("effort")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText() !in setOf("off","minimal","low","medium","high","xhigh","max")) fail("invalid field alternative") }; n0.get("modelId")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>256) fail("dialog text too long") }; n0.get("provider")?.let { n3 -> if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>128) fail("dialog text too long") } } }; payload.get("conversationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; payload.get("createdAt")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text") }; payload.get("expiresAt")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text") }; payload.get("idleTimeoutSeconds")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer"); if(n0.asLong()<60L) fail("dialog number too small"); if(n0.asLong()>86400L) fail("dialog number too large") }; payload.get("sessionId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long") }; payload.get("sessionName")?.let { n0 -> if(!n0.isNull) { if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length>512) fail("dialog text too long") } }; payload.get("state")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText() !in setOf("active","expired","closed","unavailable")) fail("invalid field alternative") } }
            "ping" -> {

            }
            "pong" -> {

            }
            else -> fail("unknown message type")
        }
    }
    private val artifactJsonMapper by lazy {
        ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(512).maxNumberLength(20).build()).build())
            .registerKotlinModule()
    }
    fun parseOfferedArtifact(json:String):OfferedArtifact {
        if(json.length>512) fail("artifact metadata too large")
        val node=artifactJsonMapper.readTree(json)?:fail("empty artifact metadata")
        if(!node.isObject) fail("dialog field must be a map"); if(!node.fieldNames().asSequence().toSet().containsAll(setOf<String>("artifactId","fileName","length","sha256"))) fail("missing dialog field"); if(node.fieldNames().asSequence().any{it !in setOf<String>("artifactId","fileName","length","sha256")}) fail("unknown dialog field"); node.get("artifactId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; node.get("fileName")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(!Regex("^[A-Za-z0-9]([A-Za-z0-9 ._()+-]{0,118}[A-Za-z0-9_()+-])?$").matches(n0.asText())) fail("invalid dialog identity") }; node.get("length")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer"); if(n0.asLong()<0L) fail("dialog number too small"); if(n0.asLong()>16777216L) fail("dialog number too large") }; node.get("sha256")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n0.asText())) fail("invalid dialog identity") }
        return artifactJsonMapper.treeToValue(node,OfferedArtifact::class.java)
    }
    private val notificationJsonMapper by lazy { ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(CBOR_MAX_DEPTH).maxStringLength(NOTIFICATION_INPUT_MAX_BYTES).maxNumberLength(20).build()).build()).registerKotlinModule() }
    fun parseAgentNotification(json:String):AgentNotification {
        if(json.toByteArray(Charsets.UTF_8).size>NOTIFICATION_INPUT_MAX_BYTES) fail("notification size")
        val node=notificationJsonMapper.readTree(json)?:fail("empty notification")
        if(!node.isObject) fail("dialog field must be a map"); if(!node.fieldNames().asSequence().toSet().containsAll(setOf<String>("title","body"))) fail("missing dialog field"); if(node.fieldNames().asSequence().any{it !in setOf<String>("body","rich","title")}) fail("unknown dialog field"); node.get("body")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>2000) fail("dialog text too long") }; node.get("rich")?.let { n0 -> if(!n0.isNull) { if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("buttons","group","progress","silent","style","subtext","tag","timeoutMs")}) fail("unknown dialog field"); n0.get("buttons")?.let { n3 -> if(!n3.isNull) { if(!n3.isArray) fail("dialog field must be an array"); if(n3.size()>3) fail("too many dialog entries"); n3.forEach { n5 -> if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("label","target"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("label","target")}) fail("unknown dialog field"); n5.get("label")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText().length<1) fail("dialog text too short"); if(n7.asText().length>40) fail("dialog text too long") }; n5.get("target")?.let { n7 -> if(!n7.isObject) fail("dialog must be a map"); when(n7.path("type").asText()) { "open-kompi" -> { if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n7.get("payload")?.let { n10 -> if(!n10.isObject) fail("dialog field must be a map"); if(!n10.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n10.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n7.get("type")?.let { n10 -> if(!n10.isTextual) fail("dialog field must be text"); if(n10.asText()!="open-kompi") fail("invalid dialog tag") } }; "https" -> { if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n7.get("payload")?.let { n10 -> if(!n10.isObject) fail("dialog field must be a map"); if(!n10.fieldNames().asSequence().toSet().containsAll(setOf<String>("url"))) fail("missing dialog field"); if(n10.fieldNames().asSequence().any{it !in setOf<String>("url")}) fail("unknown dialog field"); n10.get("url")?.let { n11 -> if(!n11.isTextual) fail("dialog field must be text"); if(n11.asText().length<9) fail("dialog text too short"); if(n11.asText().length>2048) fail("dialog text too long"); if(!Regex("^https://[^/@\\s\\x00-\\x20\\x7f]+(?:[/?#][^\\s\\x00-\\x20\\x7f]*)?$").matches(n11.asText())) fail("invalid dialog identity") } }; n7.get("type")?.let { n10 -> if(!n10.isTextual) fail("dialog field must be text"); if(n10.asText()!="https") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } } } }; n0.get("group")?.let { n3 -> if(!n3.isNull) { if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>64) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n3.asText())) fail("invalid dialog identity") } }; n0.get("progress")?.let { n3 -> if(!n3.isNull) { if(!n3.isObject) fail("dialog must be a map"); when(n3.path("type").asText()) { "indeterminate" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n3.get("type")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText()!="indeterminate") fail("invalid dialog tag") } }; "determinate" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("percent"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("percent")}) fail("unknown dialog field"); n7.get("percent")?.let { n8 -> if(!n8.isIntegralNumber||!n8.canConvertToLong()) fail("dialog field must be an integer"); if(n8.asLong()<0L) fail("dialog number too small"); if(n8.asLong()>100L) fail("dialog number too large") } }; n3.get("type")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText()!="determinate") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } }; n0.get("silent")?.let { n3 -> if(!n3.isNull) { if(!n3.isBoolean) fail("dialog field must be a boolean") } }; n0.get("style")?.let { n3 -> if(!n3.isNull) { if(!n3.isObject) fail("dialog must be a map"); when(n3.path("type").asText()) { "big-text" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("text"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("text")}) fail("unknown dialog field"); n7.get("text")?.let { n8 -> if(!n8.isTextual) fail("dialog field must be text"); if(n8.asText().length<1) fail("dialog text too short"); if(n8.asText().length>8000) fail("dialog text too long") } }; n3.get("type")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText()!="big-text") fail("invalid dialog tag") } }; "inbox" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("lines"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("lines")}) fail("unknown dialog field"); n7.get("lines")?.let { n8 -> if(!n8.isArray) fail("dialog field must be an array"); if(n8.size()<1) fail("not enough dialog choices"); if(n8.size()>8) fail("too many dialog entries"); n8.forEach { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText().length<1) fail("dialog text too short"); if(n9.asText().length>500) fail("dialog text too long") } } }; n3.get("type")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText()!="inbox") fail("invalid dialog tag") } }; "big-picture" -> { if(!n3.isObject) fail("dialog field must be a map"); if(!n3.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n3.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n3.get("payload")?.let { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("image"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("image")}) fail("unknown dialog field"); n7.get("image")?.let { n8 -> if(!n8.isObject) fail("dialog field must be a map"); if(!n8.fieldNames().asSequence().toSet().containsAll(setOf<String>("base64"))) fail("missing dialog field"); if(n8.fieldNames().asSequence().any{it !in setOf<String>("base64")}) fail("unknown dialog field"); n8.get("base64")?.let { n10 -> if(!n10.isTextual) fail("dialog field must be text"); if(n10.asText().length<1) fail("dialog text too short"); if(n10.asText().length>16384) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9_-]+$").matches(n10.asText())) fail("invalid dialog identity") } } }; n3.get("type")?.let { n7 -> if(!n7.isTextual) fail("dialog field must be text"); if(n7.asText()!="big-picture") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } }; n0.get("subtext")?.let { n3 -> if(!n3.isNull) { if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>120) fail("dialog text too long") } }; n0.get("tag")?.let { n3 -> if(!n3.isNull) { if(!n3.isTextual) fail("dialog field must be text"); if(n3.asText().length<1) fail("dialog text too short"); if(n3.asText().length>64) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n3.asText())) fail("invalid dialog identity") } }; n0.get("timeoutMs")?.let { n3 -> if(!n3.isNull) { if(!n3.isIntegralNumber||!n3.canConvertToLong()) fail("dialog field must be an integer"); if(n3.asLong()<1000L) fail("dialog number too small"); if(n3.asLong()>86400000L) fail("dialog number too large") } } } }; node.get("title")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>120) fail("dialog text too long") }
        return notificationJsonMapper.treeToValue(node,AgentNotification::class.java)
    }
    fun parseReplyPushNotificationPayload(json:String):ReplyPushNotificationPayload {
        if(json.toByteArray(Charsets.UTF_8).size>PUSH_MAX_CIPHERTEXT_BYTES) fail("push notification size")
        val node=notificationJsonMapper.readTree(json)?:fail("empty notification")
        if(!node.isObject) fail("dialog field must be a map"); if(!node.fieldNames().asSequence().toSet().containsAll(setOf<String>("version","hostEndpoint","registrationId","notificationId","issuedAtMs","expiresAtMs","conversationId"))) fail("missing dialog field"); if(node.fieldNames().asSequence().any{it !in setOf<String>("conversationId","expiresAtMs","hostEndpoint","issuedAtMs","notificationId","registrationId","version")}) fail("unknown dialog field"); node.get("conversationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; node.get("expiresAtMs")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer") }; node.get("hostEndpoint")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n0.asText())) fail("invalid dialog identity") }; node.get("issuedAtMs")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer") }; node.get("notificationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") }; node.get("registrationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; node.get("version")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer"); if(n0.asLong()<3L) fail("dialog number too small"); if(n0.asLong()>3L) fail("dialog number too large") }
        return notificationJsonMapper.treeToValue(node,ReplyPushNotificationPayload::class.java)
    }
    fun parseRichPushNotificationPayload(json:String):RichPushNotificationPayload {
        if(json.toByteArray(Charsets.UTF_8).size>PUSH_MAX_CIPHERTEXT_BYTES) fail("push notification size")
        val node=notificationJsonMapper.readTree(json)?:fail("empty notification")
        if(!node.isObject) fail("dialog field must be a map"); if(!node.fieldNames().asSequence().toSet().containsAll(setOf<String>("version","hostEndpoint","registrationId","notificationId","issuedAtMs","expiresAtMs","notification"))) fail("missing dialog field"); if(node.fieldNames().asSequence().any{it !in setOf<String>("expiresAtMs","hostEndpoint","issuedAtMs","notification","notificationId","registrationId","version")}) fail("unknown dialog field"); node.get("expiresAtMs")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer") }; node.get("hostEndpoint")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(!Regex("^[a-f0-9]{64}$").matches(n0.asText())) fail("invalid dialog identity") }; node.get("issuedAtMs")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer") }; node.get("notification")?.let { n0 -> if(!n0.isObject) fail("dialog field must be a map"); if(!n0.fieldNames().asSequence().toSet().containsAll(setOf<String>("title","body"))) fail("missing dialog field"); if(n0.fieldNames().asSequence().any{it !in setOf<String>("body","rich","title")}) fail("unknown dialog field"); n0.get("body")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>2000) fail("dialog text too long") }; n0.get("rich")?.let { n2 -> if(!n2.isNull) { if(!n2.isObject) fail("dialog field must be a map"); if(!n2.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n2.fieldNames().asSequence().any{it !in setOf<String>("buttons","group","progress","silent","style","subtext","tag","timeoutMs")}) fail("unknown dialog field"); n2.get("buttons")?.let { n5 -> if(!n5.isNull) { if(!n5.isArray) fail("dialog field must be an array"); if(n5.size()>3) fail("too many dialog entries"); n5.forEach { n7 -> if(!n7.isObject) fail("dialog field must be a map"); if(!n7.fieldNames().asSequence().toSet().containsAll(setOf<String>("label","target"))) fail("missing dialog field"); if(n7.fieldNames().asSequence().any{it !in setOf<String>("label","target")}) fail("unknown dialog field"); n7.get("label")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText().length<1) fail("dialog text too short"); if(n9.asText().length>40) fail("dialog text too long") }; n7.get("target")?.let { n9 -> if(!n9.isObject) fail("dialog must be a map"); when(n9.path("type").asText()) { "open-kompi" -> { if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n9.get("payload")?.let { n12 -> if(!n12.isObject) fail("dialog field must be a map"); if(!n12.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n12.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n9.get("type")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText()!="open-kompi") fail("invalid dialog tag") } }; "https" -> { if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n9.get("payload")?.let { n12 -> if(!n12.isObject) fail("dialog field must be a map"); if(!n12.fieldNames().asSequence().toSet().containsAll(setOf<String>("url"))) fail("missing dialog field"); if(n12.fieldNames().asSequence().any{it !in setOf<String>("url")}) fail("unknown dialog field"); n12.get("url")?.let { n13 -> if(!n13.isTextual) fail("dialog field must be text"); if(n13.asText().length<9) fail("dialog text too short"); if(n13.asText().length>2048) fail("dialog text too long"); if(!Regex("^https://[^/@\\s\\x00-\\x20\\x7f]+(?:[/?#][^\\s\\x00-\\x20\\x7f]*)?$").matches(n13.asText())) fail("invalid dialog identity") } }; n9.get("type")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText()!="https") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } } } }; n2.get("group")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>64) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n5.asText())) fail("invalid dialog identity") } }; n2.get("progress")?.let { n5 -> if(!n5.isNull) { if(!n5.isObject) fail("dialog must be a map"); when(n5.path("type").asText()) { "indeterminate" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>())) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>()}) fail("unknown dialog field") }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="indeterminate") fail("invalid dialog tag") } }; "determinate" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("percent"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("percent")}) fail("unknown dialog field"); n9.get("percent")?.let { n10 -> if(!n10.isIntegralNumber||!n10.canConvertToLong()) fail("dialog field must be an integer"); if(n10.asLong()<0L) fail("dialog number too small"); if(n10.asLong()>100L) fail("dialog number too large") } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="determinate") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } }; n2.get("silent")?.let { n5 -> if(!n5.isNull) { if(!n5.isBoolean) fail("dialog field must be a boolean") } }; n2.get("style")?.let { n5 -> if(!n5.isNull) { if(!n5.isObject) fail("dialog must be a map"); when(n5.path("type").asText()) { "big-text" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("text"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("text")}) fail("unknown dialog field"); n9.get("text")?.let { n10 -> if(!n10.isTextual) fail("dialog field must be text"); if(n10.asText().length<1) fail("dialog text too short"); if(n10.asText().length>8000) fail("dialog text too long") } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="big-text") fail("invalid dialog tag") } }; "inbox" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("lines"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("lines")}) fail("unknown dialog field"); n9.get("lines")?.let { n10 -> if(!n10.isArray) fail("dialog field must be an array"); if(n10.size()<1) fail("not enough dialog choices"); if(n10.size()>8) fail("too many dialog entries"); n10.forEach { n11 -> if(!n11.isTextual) fail("dialog field must be text"); if(n11.asText().length<1) fail("dialog text too short"); if(n11.asText().length>500) fail("dialog text too long") } } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="inbox") fail("invalid dialog tag") } }; "big-picture" -> { if(!n5.isObject) fail("dialog field must be a map"); if(!n5.fieldNames().asSequence().toSet().containsAll(setOf<String>("type","payload"))) fail("missing dialog field"); if(n5.fieldNames().asSequence().any{it !in setOf<String>("payload","type")}) fail("unknown dialog field"); n5.get("payload")?.let { n9 -> if(!n9.isObject) fail("dialog field must be a map"); if(!n9.fieldNames().asSequence().toSet().containsAll(setOf<String>("image"))) fail("missing dialog field"); if(n9.fieldNames().asSequence().any{it !in setOf<String>("image")}) fail("unknown dialog field"); n9.get("image")?.let { n10 -> if(!n10.isObject) fail("dialog field must be a map"); if(!n10.fieldNames().asSequence().toSet().containsAll(setOf<String>("base64"))) fail("missing dialog field"); if(n10.fieldNames().asSequence().any{it !in setOf<String>("base64")}) fail("unknown dialog field"); n10.get("base64")?.let { n12 -> if(!n12.isTextual) fail("dialog field must be text"); if(n12.asText().length<1) fail("dialog text too short"); if(n12.asText().length>16384) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9_-]+$").matches(n12.asText())) fail("invalid dialog identity") } } }; n5.get("type")?.let { n9 -> if(!n9.isTextual) fail("dialog field must be text"); if(n9.asText()!="big-picture") fail("invalid dialog tag") } }; else -> fail("unknown dialog alternative") } } }; n2.get("subtext")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>120) fail("dialog text too long") } }; n2.get("tag")?.let { n5 -> if(!n5.isNull) { if(!n5.isTextual) fail("dialog field must be text"); if(n5.asText().length<1) fail("dialog text too short"); if(n5.asText().length>64) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n5.asText())) fail("invalid dialog identity") } }; n2.get("timeoutMs")?.let { n5 -> if(!n5.isNull) { if(!n5.isIntegralNumber||!n5.canConvertToLong()) fail("dialog field must be an integer"); if(n5.asLong()<1000L) fail("dialog number too small"); if(n5.asLong()>86400000L) fail("dialog number too large") } } } }; n0.get("title")?.let { n2 -> if(!n2.isTextual) fail("dialog field must be text"); if(n2.asText().length<1) fail("dialog text too short"); if(n2.asText().length>120) fail("dialog text too long") } }; node.get("notificationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(n0.asText().length<1) fail("dialog text too short"); if(n0.asText().length>128) fail("dialog text too long"); if(!Regex("^[A-Za-z0-9._:-]+$").matches(n0.asText())) fail("invalid dialog identity") }; node.get("registrationId")?.let { n0 -> if(!n0.isTextual) fail("dialog field must be text"); if(runCatching{java.util.UUID.fromString(n0.asText()).toString().equals(n0.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation") }; node.get("version")?.let { n0 -> if(!n0.isIntegralNumber||!n0.canConvertToLong()) fail("dialog field must be an integer"); if(n0.asLong()<2L) fail("dialog number too small"); if(n0.asLong()>2L) fail("dialog number too large") }
        return notificationJsonMapper.treeToValue(node,RichPushNotificationPayload::class.java)
    }
    private fun fail(reason:String):Nothing=throw WireFormatException(reason)
}
object FrameCodec {
    fun frame(payload:ByteArray):ByteArray { if(payload.isEmpty()||payload.size>MAX_PAYLOAD)throw WireFormatException("payload size");return ByteBuffer.allocate(payload.size+4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).put(payload).array() }
    fun payload(frame:ByteArray):ByteArray { if(frame.size<5)throw WireFormatException("truncated frame");val n=ByteBuffer.wrap(frame,0,4).order(ByteOrder.BIG_ENDIAN).int;if(n !in 1..MAX_PAYLOAD||frame.size!=n+4)throw WireFormatException("invalid frame length");return frame.copyOfRange(4,frame.size) }
}
