package com.kompi.app.assistant.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kompi.app.assistant.AgentNotifications
import com.kompi.app.assistant.push.PushIngressDiagnostics.Event
import com.kompi.app.security.SecureStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Host-scoped push state. Registration crypto is represented by a sealed provisional/bound
 * generation, and disabled bindings contain no endpoint or key material. The encrypted preference
 * parser constructs these domain states atomically before any caller can use them.
 */
internal class UnifiedPushRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secureStore = SecureStore(appContext)
    private val replayLedger = UnifiedPushReplayLedger(appContext)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = _changes.asSharedFlow()

    internal class Registration private constructor(
        val hostId: PushHostId,
        val registrationId: PushRegistrationId,
        val endpoint: String,
        val p256dh: String,
        val auth: String,
        /** Random 32-byte HMAC key; only exposed to the authenticated Iroh sync layer. */
        val integrityKey: String,
    ) {
        override fun toString(): String = "PushRegistration(hostId=$hostId, registrationId=$registrationId, endpoint=[REDACTED], keys=[REDACTED])"

        companion object {
            fun parse(
                hostId: PushHostId,
                registrationId: PushRegistrationId,
                endpoint: String,
                p256dh: String,
                auth: String,
                integrityKey: String,
            ): Registration? {
                val material = PushEndpointMaterial.parse(endpoint, p256dh, auth) ?: return null
                if (decodeBase64Url(integrityKey)?.size != 32) return null
                return Registration(hostId, registrationId, material.url, material.p256dh, material.auth, integrityKey)
            }

        }
    }

    private data class ProvisionalGeneration(
        val registrationId: PushRegistrationId,
        val integrityKey: String,
    ) {
        init { require(decodeBase64Url(integrityKey)?.size == 32) }
        override fun toString(): String = "ProvisionalGeneration(registrationId=$registrationId, integrityKey=[REDACTED])"
    }

    private sealed interface RegistrationMaterial {
        val registrationId: PushRegistrationId
        data class Provisional(val generation: ProvisionalGeneration) : RegistrationMaterial {
            override val registrationId: PushRegistrationId get() = generation.registrationId
            override fun toString(): String = "Provisional(registrationId=$registrationId, key=[REDACTED])"
        }
        data class Bound(val registration: Registration) : RegistrationMaterial {
            override val registrationId: PushRegistrationId get() = registration.registrationId
            override fun toString(): String = registration.toString()
        }
    }

    private sealed interface BindingState {
        val status: PushSetupStatus
        val error: PushSetupError?
        data class Enabled(
            val material: RegistrationMaterial,
            override val status: PushSetupStatus,
            override val error: PushSetupError? = null,
        ) : BindingState {
            override fun toString(): String = "Enabled(status=$status, material=$material, error=$error)"
        }
        data class Disabled(
            override val status: PushSetupStatus,
            override val error: PushSetupError? = null,
            /** Retained only when the bounded unregister queue is full; receipt is still fenced. */
            val retiringRegistrationId: PushRegistrationId? = null,
        ) : BindingState
    }

    private data class Record(
        val hostId: PushHostId,
        val binding: BindingState,
        val pendingUnregisters: List<PushRegistrationId>,
    ) {
        val enabled: Boolean get() = binding is BindingState.Enabled
        val status: PushSetupStatus get() = binding.status
        val error: PushSetupError? get() = binding.error
        val registrationId: PushRegistrationId? get() = when (val state = binding) {
            is BindingState.Enabled -> state.material.registrationId
            is BindingState.Disabled -> state.retiringRegistrationId
        }
        fun registration(): Registration? = ((binding as? BindingState.Enabled)?.material as? RegistrationMaterial.Bound)?.registration
        override fun toString(): String = "PushBinding(hostId=$hostId, enabled=$enabled, status=$status, registrationId=$registrationId, endpoint=[REDACTED], keys=[REDACTED])"
    }

    internal sealed interface PendingOperation {
        val hostId: PushHostId
        data class Register(override val hostId: PushHostId, val registration: Registration) : PendingOperation
        data class Unregister(override val hostId: PushHostId, val registrationId: PushRegistrationId) : PendingOperation
    }

    @Synchronized
    fun startRegistration(hostId: PushHostId): String? {
        if (!isTrusted(hostId) || !AgentNotifications.enabled(appContext) || !notificationPermissionGranted()) return null
        val records = readRecords().toMutableMap()
        val old = records[hostId.value]
        val pending = (old?.pendingUnregisters.orEmpty() + listOfNotNull(old?.registrationId)).distinct()
        // Keep one queue slot available so this generation can always be retired later.
        if (pending.size >= MAX_PENDING_UNREGISTERS) return null
        val generation = ProvisionalGeneration(PushRegistrationId.fresh(), newIntegrityKey())
        records[hostId.value] = Record(
            hostId = hostId,
            binding = BindingState.Enabled(RegistrationMaterial.Provisional(generation), PushSetupStatus.WaitingForEndpoint),
            pendingUnregisters = pending,
        )
        if (!writeRecords(records)) return null
        return instanceFor(hostId)
    }

    /** Use only after the official SDK's current/default distributor selection succeeds. */
    fun requestRegistration(hostId: PushHostId): Boolean {
        val instance = startRegistration(hostId) ?: return false
        return runCatching {
            org.unifiedpush.android.connector.UnifiedPush.register(
                appContext,
                instance = instance,
                messageForDistributor = "Kompi background notifications",
            )
        }.isSuccess
    }

    @Synchronized
    fun acceptEndpoint(instance: String, endpoint: org.unifiedpush.android.connector.data.PushEndpoint) {
        val hostId = hostFromInstance(instance) ?: return rejectEndpoint(Event.ENDPOINT_INVALID_INSTANCE)
        if (!isTrusted(hostId)) return rejectEndpoint(Event.ENDPOINT_UNTRUSTED)
        if (!AgentNotifications.enabled(appContext)) return rejectEndpoint(Event.ENDPOINT_DISABLED)
        if (endpoint.temporary) return rejectEndpoint(Event.ENDPOINT_TEMPORARY)
        val keys = endpoint.pubKeySet
        val material = PushEndpointMaterial.parse(endpoint.url, keys?.pubKey, keys?.auth)
        val records = readRecords().toMutableMap()
        val current = records[hostId.value]?.takeIf { it.enabled } ?: return rejectEndpoint(Event.ENDPOINT_NO_BINDING)
        if (material == null) {
            PushIngressDiagnostics.record(if (keys == null) Event.ENDPOINT_MISSING_KEYS else Event.ENDPOINT_REJECTED_MATERIAL)
            records[hostId.value] = retireCurrent(
                current,
                PushSetupStatus.NeedsAttention,
                if (keys == null) PushSetupError.MissingEncryptionKeys else PushSetupError.EndpointRejected,
            )
            check(writeRecords(records)) { "Could not persist UnifiedPush endpoint rejection" }
            return
        }

        val enabled = current.binding as BindingState.Enabled
        val nextBinding = when (val existing = enabled.material) {
            is RegistrationMaterial.Provisional -> BindingState.Enabled(
                material = RegistrationMaterial.Bound(requireNotNull(Registration.parse(
                    hostId,
                    existing.generation.registrationId,
                    material.url,
                    material.p256dh,
                    material.auth,
                    existing.generation.integrityKey,
                ))),
                status = PushSetupStatus.WaitingForComputer,
            )
            is RegistrationMaterial.Bound -> {
                val registration = existing.registration
                if (registration.endpoint == material.url && registration.p256dh == material.p256dh && registration.auth == material.auth) {
                    // SDK may redeliver the same endpoint; retain generation and MAC key exactly.
                    enabled.copy(status = PushSetupStatus.WaitingForComputer, error = null)
                } else {
                    val retired = (current.pendingUnregisters + registration.registrationId).distinct()
                    // Reserve a queue slot for the new generation's eventual retirement.
                    if (retired.size >= MAX_PENDING_UNREGISTERS) {
                        PushIngressDiagnostics.record(Event.ENDPOINT_GENERATION_LIMIT)
                        records[hostId.value] = retireCurrent(current, PushSetupStatus.NeedsAttention, PushSetupError.StorageUnavailable)
                        check(writeRecords(records)) { "Could not persist UnifiedPush generation exhaustion" }
                        return
                    }
                    val next = requireNotNull(Registration.parse(
                        hostId = hostId,
                        registrationId = PushRegistrationId.fresh(),
                        endpoint = material.url,
                        p256dh = material.p256dh,
                        auth = material.auth,
                        integrityKey = newIntegrityKey(),
                    )) { "Could not construct valid UnifiedPush registration material" }
                    BindingState.Enabled(RegistrationMaterial.Bound(next), PushSetupStatus.WaitingForComputer)
                }
            }
        }
        records[hostId.value] = current.copy(
            binding = nextBinding,
            pendingUnregisters = if (nextBinding is BindingState.Enabled && nextBinding.material is RegistrationMaterial.Bound &&
                enabled.material is RegistrationMaterial.Bound && nextBinding.material.registrationId != enabled.material.registrationId) {
                (current.pendingUnregisters + enabled.material.registrationId).distinct()
            } else current.pendingUnregisters,
        )
        check(writeRecords(records)) { "Could not persist UnifiedPush endpoint" }
        PushIngressDiagnostics.record(Event.ENDPOINT_BOUND)
    }

    @Synchronized
    fun markRegistered(hostId: PushHostId, registrationId: PushRegistrationId): Boolean {
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return false
        if (!current.enabled || current.registrationId != registrationId || current.registration() == null || !isTrusted(hostId)) return false
        val enabled = current.binding as BindingState.Enabled
        records[hostId.value] = current.copy(binding = enabled.copy(status = PushSetupStatus.Active, error = null))
        return writeRecords(records)
    }

    @Synchronized
    fun markLocalFailure(hostId: PushHostId, code: PushSetupError): Boolean {
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return false
        val enabled = current.binding as? BindingState.Enabled ?: return false
        records[hostId.value] = current.copy(binding = enabled.copy(status = PushSetupStatus.NeedsAttention, error = code))
        return writeRecords(records)
    }

    @Synchronized
    fun markRegisterRejected(hostId: PushHostId, registrationId: PushRegistrationId, code: PushSetupError): Boolean {
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return false
        if (!current.enabled || current.registrationId != registrationId) return false
        records[hostId.value] = retireCurrent(current, PushSetupStatus.NeedsAttention, code)
        return writeRecords(records)
    }

    @Synchronized
    fun markCapabilityUnsupported(hostId: PushHostId): Boolean {
        val current = readRecords()[hostId.value] ?: return false
        val registrationId = current.registrationId
        if (current.enabled && registrationId != null) return markRegisterRejected(hostId, registrationId, PushSetupError.ComputerRejected)
        val records = readRecords().toMutableMap()
        records[hostId.value] = current.copy(binding = BindingState.Disabled(PushSetupStatus.NeedsAttention, PushSetupError.ComputerRejected))
        return writeRecords(records)
    }

    @Synchronized
    fun markUnregisterRejected(hostId: PushHostId, registrationId: PushRegistrationId, code: PushSetupError): Boolean {
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return false
        if (registrationId !in current.pendingUnregisters && current.registrationId != registrationId) return false
        val nextBinding = when (val binding = current.binding) {
            is BindingState.Enabled -> binding.copy(status = PushSetupStatus.NeedsAttention, error = code)
            is BindingState.Disabled -> binding.copy(status = PushSetupStatus.NeedsAttention, error = code)
        }
        records[hostId.value] = current.copy(binding = nextBinding)
        return writeRecords(records)
    }

    @Synchronized
    fun registrationFailed(instance: String, reason: org.unifiedpush.android.connector.FailedReason) {
        val hostId = hostFromInstance(instance) ?: return
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return
        val enabled = current.binding as? BindingState.Enabled ?: return
        if (!isTrusted(hostId)) return
        val error = when (reason) {
            org.unifiedpush.android.connector.FailedReason.NETWORK,
            org.unifiedpush.android.connector.FailedReason.VAPID_REQUIRED,
            org.unifiedpush.android.connector.FailedReason.ACTION_REQUIRED,
            org.unifiedpush.android.connector.FailedReason.INTERNAL_ERROR -> PushSetupError.RegistrationFailed
        }
        records[hostId.value] = current.copy(binding = enabled.copy(status = PushSetupStatus.NeedsAttention, error = error))
        writeRecords(records)
    }

    @Synchronized
    fun markUnregistered(instance: String) {
        val hostId = hostFromInstance(instance) ?: return
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return
        if (!current.enabled || !isTrusted(hostId)) return
        val providerAvailable = runCatching { org.unifiedpush.android.connector.UnifiedPush.getDistributors(appContext).isNotEmpty() }.getOrNull()
        records[hostId.value] = retireCurrent(
            current,
            if (providerAvailable == false) PushSetupStatus.ProviderUnavailable else PushSetupStatus.NeedsAttention,
            if (providerAvailable == false) null else PushSetupError.RegistrationFailed,
        )
        writeRecords(records)
    }

    /** Called only while foreground UI is already active. No persistent worker is created. */
    @Synchronized
    fun refreshProviderAvailability(): Boolean {
        if (!AgentNotifications.enabled(appContext) || !notificationPermissionGranted()) {
            disableAll()
            return false
        }
        val distributors = runCatching { org.unifiedpush.android.connector.UnifiedPush.getDistributors(appContext) }.getOrNull()
            ?: return false
        // The official SDK emits onUnregistered for instances whose selected provider was removed.
        runCatching { org.unifiedpush.android.connector.UnifiedPush.getAckDistributor(appContext) }
        if (distributors.isEmpty()) retireForMissingProvider()
        return distributors.isNotEmpty()
    }

    @Synchronized
    private fun retireForMissingProvider() {
        val records = readRecords().toMutableMap()
        var changed = false
        for ((hostId, current) in records.toMap()) {
            if (!current.enabled) continue
            records[hostId] = retireCurrent(current, PushSetupStatus.ProviderUnavailable, null)
            changed = true
        }
        if (changed) {
            check(writeRecords(records)) { "Could not persist missing-provider retirement" }
            records.values.filter { it.status == PushSetupStatus.ProviderUnavailable }.forEach { record ->
                runCatching { org.unifiedpush.android.connector.UnifiedPush.unregister(appContext, instanceFor(record.hostId)) }
            }
        }
    }

    @Synchronized
    fun markTemporarilyUnavailable(instance: String) {
        val hostId = hostFromInstance(instance) ?: return
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return
        val enabled = current.binding as? BindingState.Enabled ?: return
        if (enabled.status == PushSetupStatus.Active) {
            records[hostId.value] = current.copy(binding = enabled.copy(status = PushSetupStatus.TemporarilyUnavailable))
            writeRecords(records)
        }
    }

    @Synchronized
    fun retryRegistration(hostId: PushHostId): String? {
        if (!isTrusted(hostId) || !AgentNotifications.enabled(appContext)) return null
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return null
        val enabled = current.binding as? BindingState.Enabled ?: return null
        records[hostId.value] = current.copy(binding = enabled.copy(status = PushSetupStatus.WaitingForEndpoint, error = null))
        if (!writeRecords(records)) return null
        return instanceFor(hostId)
    }

    @Synchronized
    fun disable(hostId: PushHostId, forget: Boolean = false) {
        val records = readRecords().toMutableMap()
        val current = records[hostId.value]
        if (current == null) {
            if (forget) runCatching { org.unifiedpush.android.connector.UnifiedPush.unregister(appContext, instanceFor(hostId)) }
            return
        }
        if (forget) records.remove(hostId.value)
        else records[hostId.value] = retireCurrent(current, PushSetupStatus.Off, null)
        check(writeRecords(records)) { "Could not persist UnifiedPush opt-out" }
        runCatching { org.unifiedpush.android.connector.UnifiedPush.unregister(appContext, instanceFor(hostId)) }
    }

    @Synchronized
    fun disableAll() {
        readRecords().values.filter { it.enabled }.map { it.hostId }.forEach { disable(it) }
    }

    @Synchronized
    fun forget(hostId: PushHostId) = disable(hostId, forget = true)

    @Synchronized
    fun summaries(): List<PushSetupSummary> = readRecords().values.map { record ->
        val trusted = isTrusted(record.hostId)
        PushSetupSummary(record.hostId, if (trusted) record.status else PushSetupStatus.Off, if (trusted) record.error else null)
    }

    @Synchronized
    fun pendingOperations(hostId: PushHostId): List<PendingOperation> = pendingOperations().filter { it.hostId == hostId }

    @Synchronized
    fun pendingOperations(): List<PendingOperation> {
        val operations = mutableListOf<PendingOperation>()
        for (record in readRecords().values) {
            if (!isTrusted(record.hostId)) continue
            record.pendingUnregisters.forEach { operations += PendingOperation.Unregister(record.hostId, it) }
            if (!record.enabled) record.registrationId?.takeIf { it !in record.pendingUnregisters }
                ?.let { operations += PendingOperation.Unregister(record.hostId, it) }
            if (record.enabled && record.pendingUnregisters.isEmpty() && record.status == PushSetupStatus.WaitingForComputer) {
                record.registration()?.let { operations += PendingOperation.Register(record.hostId, it) }
            }
        }
        return operations
    }

    @Synchronized
    fun confirmUnregistered(hostId: PushHostId, registrationId: PushRegistrationId): Boolean {
        val records = readRecords().toMutableMap()
        val current = records[hostId.value] ?: return false
        if (registrationId !in current.pendingUnregisters && !(current.registrationId == registrationId && current.binding is BindingState.Disabled)) return false
        val binding = (current.binding as? BindingState.Disabled)?.let { disabled ->
            if (disabled.retiringRegistrationId == registrationId) disabled.copy(retiringRegistrationId = null) else disabled
        } ?: current.binding
        records[hostId.value] = current.copy(binding = binding, pendingUnregisters = current.pendingUnregisters - registrationId)
        return writeRecords(records)
    }

    @Synchronized
    fun acceptPush(message: org.unifiedpush.android.connector.data.PushMessage, instance: String): JSONObject {
        if (!message.decrypted) return rejected(Event.MESSAGE_NOT_DECRYPTED)
        val hostId = hostFromInstance(instance) ?: return rejected(Event.MESSAGE_INVALID_INSTANCE)
        if (!isTrusted(hostId)) return rejected(Event.MESSAGE_UNTRUSTED)
        if (!AgentNotifications.enabled(appContext)) return rejected(Event.MESSAGE_DISABLED)
        if (!notificationPermissionGranted()) return rejected(Event.MESSAGE_PERMISSION_REQUIRED)
        val record = readRecords()[hostId.value] ?: return rejected(Event.MESSAGE_NO_BINDING)
        if (!record.enabled) return rejected(Event.MESSAGE_DISABLED_BINDING)
        val registration = record.registration() ?: return rejected(Event.MESSAGE_PROVISIONAL_BINDING)
        // A valid local binding may receive its first push before the Iroh registration ACK.
        val packet = AuthenticatedPushPacket.parse(message.content, registration) ?: return rejected(Event.PACKET_REJECTED)
        PushIngressDiagnostics.record(Event.PACKET_ACCEPTED)
        return when (replayLedger.claim(hostId.value, registration.registrationId.toString(), packet.notificationId, packet.expiresAtEpochMillis, System.currentTimeMillis())) {
            ReplayClaim.Duplicate, ReplayClaim.Conflict -> rejected(Event.REPLAY_DUPLICATE)
            ReplayClaim.Expired -> rejected(Event.REPLAY_EXPIRED)
            ReplayClaim.Full -> rejected(Event.REPLAY_FULL)
            ReplayClaim.StorageFailure -> rejected(Event.REPLAY_STORAGE_FAILURE)
            ReplayClaim.Claimed -> when (val content = packet.content) {
                is AuthenticatedPushPacket.Content.Agent -> AgentNotifications.postRichForHost(
                    appContext, hostId, packet.notificationId, packet.expiresAtEpochMillis, content.notification,
                )
                is AuthenticatedPushPacket.Content.Reply -> AgentNotifications.postReplyForHost(
                    appContext, hostId, packet.notificationId, packet.expiresAtEpochMillis, content.reply,
                )
            }.also { PushIngressDiagnostics.postResult(it.optString("status")) }
        }
    }

    private fun retireCurrent(current: Record, status: PushSetupStatus, error: PushSetupError?): Record {
        val registrationId = current.registrationId
        val pending = (current.pendingUnregisters + listOfNotNull(registrationId)).distinct()
        val fits = pending.size <= MAX_PENDING_UNREGISTERS
        return current.copy(
            binding = BindingState.Disabled(status, error, registrationId.takeIf { !fits }),
            pendingUnregisters = if (fits) pending else current.pendingUnregisters,
        )
    }

    private fun readRecords(): Map<String, Record> {
        val encrypted = prefs.getString(RECORDS, null) ?: return emptyMap()
        val root = JSONObject(secureStore.reveal(encrypted))
        require(root.getInt("version") == STORAGE_VERSION) { "Unsupported UnifiedPush binding-store version" }
        val rows = root.getJSONArray("records")
        require(rows.length() <= MAX_HOSTS) { "UnifiedPush binding store exceeds its bound" }
        val records = linkedMapOf<String, Record>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val hostId = PushHostId.parse(row.getString("hostId")) ?: error("Invalid stored host identity")
            val pendingRows = row.getJSONArray("pendingUnregisters")
            require(pendingRows.length() <= MAX_PENDING_UNREGISTERS) { "Too many pending unregister operations" }
            val pending = (0 until pendingRows.length()).map { i ->
                PushRegistrationId.parse(pendingRows.getString(i)) ?: error("Invalid unregister identity")
            }
            require(pending.size == pending.distinct().size) { "Duplicate unregister identity" }
            val status = PushSetupStatus.valueOf(row.getString("status"))
            val error = row.optString("error").takeIf(String::isNotEmpty)?.let(PushSetupError::valueOf)
            val binding = when (row.getString("bindingKind")) {
                "enabled-provisional" -> {
                    require(status !in DISABLED_STATUSES) { "Invalid enabled provisional status" }
                    val generation = ProvisionalGeneration(
                        PushRegistrationId.parse(row.getString("registrationId")) ?: error("Invalid stored registration identity"),
                        row.getString("integrityKey"),
                    )
                    BindingState.Enabled(RegistrationMaterial.Provisional(generation), status, error)
                }
                "enabled-bound" -> {
                    require(status !in DISABLED_STATUSES) { "Invalid enabled registration status" }
                    val registration = Registration.parse(
                        hostId,
                        PushRegistrationId.parse(row.getString("registrationId")) ?: error("Invalid stored registration identity"),
                        row.getString("endpoint"), row.getString("p256dh"), row.getString("auth"), row.getString("integrityKey"),
                    ) ?: error("Invalid stored registration material")
                    BindingState.Enabled(RegistrationMaterial.Bound(registration), status, error)
                }
                "disabled" -> {
                    require(status in DISABLED_STATUSES) { "Invalid disabled binding status" }
                    val retiring = row.optString("retiringRegistrationId").takeIf(String::isNotEmpty)?.let {
                        PushRegistrationId.parse(it) ?: error("Invalid retiring registration identity")
                    }
                    BindingState.Disabled(status, error, retiring)
                }
                else -> error("Unknown UnifiedPush binding state")
            }
            require(records.put(hostId.value, Record(hostId, binding, pending)) == null) { "Duplicate host binding" }
        }
        return records
    }

    private fun writeRecords(records: Map<String, Record>): Boolean {
        require(records.size <= MAX_HOSTS) { "Too many UnifiedPush host bindings" }
        val rows = JSONArray()
        records.values.sortedBy { it.hostId.value }.forEach { record ->
            require(record.pendingUnregisters.size <= MAX_PENDING_UNREGISTERS)
            val row = JSONObject()
                .put("hostId", record.hostId.value)
                .put("status", record.status.name)
                .put("error", record.error?.name)
                .put("pendingUnregisters", JSONArray().also { ids -> record.pendingUnregisters.forEach(ids::put) })
            when (val binding = record.binding) {
                is BindingState.Enabled -> when (val material = binding.material) {
                    is RegistrationMaterial.Provisional -> row
                        .put("bindingKind", "enabled-provisional")
                        .put("registrationId", material.generation.registrationId.toString())
                        .put("integrityKey", material.generation.integrityKey)
                    is RegistrationMaterial.Bound -> row
                        .put("bindingKind", "enabled-bound")
                        .put("registrationId", material.registration.registrationId.toString())
                        .put("endpoint", material.registration.endpoint)
                        .put("p256dh", material.registration.p256dh)
                        .put("auth", material.registration.auth)
                        .put("integrityKey", material.registration.integrityKey)
                }
                is BindingState.Disabled -> {
                    row.put("bindingKind", "disabled")
                    binding.retiringRegistrationId?.let { row.put("retiringRegistrationId", it.toString()) }
                }
            }
            rows.put(row)
        }
        val payload = JSONObject().put("version", STORAGE_VERSION).put("records", rows).toString()
        val persisted = prefs.edit().putString(RECORDS, secureStore.protect(payload)).commit()
        if (persisted) _changes.tryEmit(Unit)
        return persisted
    }

    private fun notificationPermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun isTrusted(hostId: PushHostId): Boolean = runCatching {
        SecureStore(appContext).trustedMachines().any { it.machineId == hostId.value }
    }.getOrDefault(false)

    private fun instanceFor(hostId: PushHostId): String = "kompi-${hostId.value}"

    private fun hostFromInstance(instance: String): PushHostId? = instance.removePrefix("kompi-")
        .takeIf { instance == "kompi-$it" }?.let(PushHostId::parse)

    private fun newIntegrityKey(): String = android.util.Base64.encodeToString(
        ByteArray(32).also(SecureRandom()::nextBytes),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    )

    private fun rejectEndpoint(event: Event) {
        PushIngressDiagnostics.record(event)
    }

    private fun rejected(event: Event): JSONObject {
        PushIngressDiagnostics.record(event)
        return JSONObject().put("status", "rejected")
    }

    companion object {
        private const val PREFS = "unified_push_v1"
        private const val RECORDS = "encrypted_bindings"
        private const val STORAGE_VERSION = 2
        private const val MAX_HOSTS = 64
        private const val MAX_PENDING_UNREGISTERS = 32
        private val DISABLED_STATUSES = setOf(PushSetupStatus.Off, PushSetupStatus.ProviderUnavailable, PushSetupStatus.NeedsAttention)

        @Volatile private var singleton: UnifiedPushRepository? = null
        fun get(context: Context): UnifiedPushRepository = singleton ?: synchronized(this) {
            singleton ?: UnifiedPushRepository(context.applicationContext).also { singleton = it }
        }
    }
}
