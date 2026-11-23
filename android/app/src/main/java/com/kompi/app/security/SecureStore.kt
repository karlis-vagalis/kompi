package com.kompi.app.security

import com.kompi.app.transport.Delivery
import com.kompi.app.assistant.actions.PhoneCapability
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/** Stores identity and pairing secrets as ciphertext; the wrapping key never leaves Android Keystore. */
class SecureStore(context: Context) {
    companion object {
        private val hostPolicyLock = Any()
        const val DEFAULT_IDLE_TIMEOUT_SECONDS = 1_800
        const val MIN_IDLE_TIMEOUT_SECONDS = com.kompi.app.protocol.generated.MIN_IDLE_TIMEOUT_SECONDS
        const val MAX_IDLE_TIMEOUT_SECONDS = com.kompi.app.protocol.generated.MAX_IDLE_TIMEOUT_SECONDS
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("identity_v1", Context.MODE_PRIVATE)
    private val keyAlias = "kompi.identity.wrap.v1"
    data class Pairing(val endpointTicket: String, val secret: String, val name: String, val confirmed: Boolean = false, val machineId: String = "")
    data class TrustedMachine(val machineId: String, val pairing: Pairing, val lastSeenEpochMillis: Long, val idleTimeoutSeconds: Int = DEFAULT_IDLE_TIMEOUT_SECONDS, val displayNameOverride: com.kompi.app.peers.PeerDisplayName? = null) {
        val displayName: String get() = displayNameOverride?.value ?: pairing.name
    }
    data class PendingPrompt(val id: String, val text: String, val delivery: Delivery, val accepted: Boolean = false, val machineId: String? = null, val attachmentIds: List<String> = emptyList(), val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null, val targetConversationId: String? = null, val waitingForConversation: Boolean = false, val createIfMissing: Boolean = false)

    @Synchronized fun identitySeed(): ByteArray {
        prefs.getString("identity_ciphertext", null)?.let { return decrypt(it) }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        persist("identity_ciphertext", seed)
        return seed
    }
    /** Trusted daemon identities are keyed by the authenticated Iroh EndpointId, never by a label or ticket string. */
    @Synchronized fun trustedMachines(): List<TrustedMachine> {
        val encoded = prefs.getString("trusted_machines_ciphertext", null) ?: return emptyList()
        val rows = org.json.JSONArray(String(decrypt(encoded), Charsets.UTF_8))
        require(rows.length() <= 64) { "Too many trusted machines" }
        val parsed = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val pairingJson = row.getJSONObject("pairing")
            val pairing = Pairing(
                pairingJson.getString("endpointTicket"), pairingJson.getString("secret"),
                pairingJson.getString("name"), pairingJson.optBoolean("confirmed", false),
                row.getString("machineId"),
            )
            val machineId = canonicalMachineId(row.getString("machineId"))
            require(machineId == endpointId(pairing.endpointTicket)) { "Trusted machine ticket does not match its endpoint identity" }
            val idleTimeoutSeconds = row.optInt("idleTimeoutSeconds", DEFAULT_IDLE_TIMEOUT_SECONDS)
            require(idleTimeoutSeconds in MIN_IDLE_TIMEOUT_SECONDS..MAX_IDLE_TIMEOUT_SECONDS && idleTimeoutSeconds % 60 == 0) { "Stored idle timeout is invalid" }
            val displayNameOverride = if (row.has("displayNameOverride")) {
                com.kompi.app.peers.PeerDisplayName.parse(row.getString("displayNameOverride")).getOrThrow()
            } else null
            TrustedMachine(machineId, pairing, row.optLong("lastSeenEpochMillis", 0L).coerceAtLeast(0L), idleTimeoutSeconds, displayNameOverride)
        }.distinctBy { it.machineId }
        val unconfirmed = parsed.firstOrNull { !it.pairing.confirmed }
        if (unconfirmed != null && prefs.getString("pending_pairing_ciphertext", null) == null) persistPairing("pending_pairing_ciphertext", unconfirmed.pairing)
        val trusted = parsed.filter { it.pairing.confirmed }
        if (trusted.size != parsed.size) persistMachines(trusted)
        return trusted.sortedByDescending { it.lastSeenEpochMillis }
    }

    @Synchronized fun selectedMachineId(): String? {
        val trusted = trustedMachines()
        val requested = prefs.getString("selected_machine_id", null)?.let(::canonicalMachineId)
        return requested?.takeIf { id -> trusted.any { it.machineId == id } } ?: trusted.firstOrNull()?.machineId
    }

    @Synchronized fun selectMachine(machineId: String) {
        val id = canonicalMachineId(machineId)
        require(trustedMachines().any { it.machineId == id }) { "Machine is not trusted" }
        check(prefs.edit().putString("selected_machine_id", id).commit()) { "Could not select trusted machine" }
    }

    /** Scanning/pasting is only an attempt; it must not select or trust the host. */
    @Synchronized fun stagePairing(pairing: Pairing) {
        require(!pairing.confirmed) { "Pairing input must not already be confirmed" }
        val machineId = endpointId(pairing.endpointTicket)
        persistPairing("pending_pairing_ciphertext", pairing.copy(machineId = machineId))
    }

    /** Called only for the matching Paired reply on an authenticated connection. */
    @Synchronized fun confirmPairing(pairing: Pairing, authenticatedMachineId: String): Pairing {
        val machineId = endpointId(pairing.endpointTicket)
        val savedPairing = PairingConfirmation.fromReply(
            pendingPairing(), pairing, machineId, canonicalMachineId(authenticatedMachineId),
        ).pairing
        val machines = trustedMachines().toMutableList()
        val existing = machines.firstOrNull { it.machineId == machineId }
        val replacement = TrustedMachine(machineId, savedPairing, existing?.lastSeenEpochMillis ?: 0L, existing?.idleTimeoutSeconds ?: DEFAULT_IDLE_TIMEOUT_SECONDS, existing?.displayNameOverride)
        val index = machines.indexOfFirst { it.machineId == machineId }
        if (index < 0) machines.add(replacement) else machines[index] = replacement
        persistMachines(machines)
        check(prefs.edit().putString("selected_machine_id", machineId).remove("pending_pairing_ciphertext").commit()) { "Could not select paired machine" }
        return savedPairing
    }

    @Synchronized fun clearPendingPairing() {
        check(prefs.edit().remove("pending_pairing_ciphertext").commit()) { "Could not clear pending pairing" }
    }

    @Synchronized fun pairing(): Pairing? = trustedMachines().firstOrNull { it.machineId == selectedMachineId() }?.pairing

    @Synchronized fun idleTimeoutSeconds(machineId: String): Int {
        val id = canonicalMachineId(machineId)
        return trustedMachines().firstOrNull { it.machineId == id }?.idleTimeoutSeconds ?: DEFAULT_IDLE_TIMEOUT_SECONDS
    }

    @Synchronized fun saveIdleTimeoutSeconds(machineId: String, seconds: Int) {
        require(seconds in MIN_IDLE_TIMEOUT_SECONDS..MAX_IDLE_TIMEOUT_SECONDS && seconds % 60 == 0) { "Idle timeout must be between ${MIN_IDLE_TIMEOUT_SECONDS / 60} and ${MAX_IDLE_TIMEOUT_SECONDS / 60} whole minutes" }
        val id = canonicalMachineId(machineId)
        val machines = trustedMachines().toMutableList()
        val index = machines.indexOfFirst { it.machineId == id }
        require(index >= 0) { "Idle timeout belongs to an untrusted machine" }
        machines[index] = machines[index].copy(idleTimeoutSeconds = seconds)
        persistMachines(machines)
    }

    /** Updates presentation only; do not modify pairing data or the selected endpoint. */
    @Synchronized fun saveDisplayName(machineId: String, name: com.kompi.app.peers.PeerDisplayName?) {
        val id = canonicalMachineId(machineId)
        val machines = trustedMachines().toMutableList()
        val index = machines.indexOfFirst { it.machineId == id }
        require(index >= 0) { "Computer is not trusted" }
        machines[index] = machines[index].copy(displayNameOverride = name)
        persistMachines(machines)
    }

    private fun phoneGrantKey(id: String) = "phone_grants_$id"

    @Synchronized internal fun phoneGrants(machineId: String): Set<PhoneCapability>? = synchronized(hostPolicyLock) {
        val id = canonicalMachineId(machineId)
        if (trustedMachines().none { it.machineId == id }) return@synchronized null
        prefs.getStringSet(phoneGrantKey(id), emptySet()).orEmpty()
            .mapNotNull(PhoneCapability::fromStoredName).toSet()
    }

    @Synchronized internal fun setPhoneGrant(machineId: String, capability: PhoneCapability, enabled: Boolean) {
        synchronized(hostPolicyLock) {
            val id = canonicalMachineId(machineId)
            require(selectedMachineId() == id) { "Select this computer before changing its phone access" }
            val key = phoneGrantKey(id)
            val grants = prefs.getStringSet(key, emptySet()).orEmpty()
            val updated = if (enabled) grants + capability.name else grants - capability.name
            if (!prefs.edit().putStringSet(key, updated).commit()) {
                // SharedPreferences updates memory even on a disk-write failure.
                // Don't let a failed enable authorize a live request.
                if (enabled) prefs.edit().putStringSet(key, grants).commit()
                error("Could not save phone access")
            }
        }
    }

    @Synchronized fun currentPairing(): Pairing? = pendingPairing() ?: pairing()

    @Synchronized private fun pendingPairing(): Pairing? {
        val encoded = prefs.getString("pending_pairing_ciphertext", null) ?: return null
        val json = JSONObject(String(decrypt(encoded), Charsets.UTF_8))
        return Pairing(json.getString("endpointTicket"), json.getString("secret"), json.getString("name"), false, endpointId(json.getString("endpointTicket")))
    }

    /** Called only after the QUIC remote key has been compared to the ticket's EndpointId. */
    @Synchronized fun markMachineSeen(machineId: String) {
        val id = canonicalMachineId(machineId)
        val machines = trustedMachines().toMutableList()
        val index = machines.indexOfFirst { it.machineId == id }
        if (index < 0) return
        machines[index] = machines[index].copy(lastSeenEpochMillis = System.currentTimeMillis())
        persistMachines(machines)
    }

    @Synchronized fun clearPairing() {
        val selected = selectedMachineId()
        if (selected == null) {
            check(prefs.edit().remove("pending_pairing_ciphertext").commit()) { "Could not clear pending pairing" }
            return
        }
        val machines = trustedMachines().filterNot { it.machineId == selected }
        synchronized(hostPolicyLock) {
            clearHostPolicy(selected)
            persistMachines(machines)
            val editor = prefs.edit().remove("pending_pairing_ciphertext")
            if (machines.isEmpty()) editor.remove("trusted_machines_ciphertext").remove("selected_machine_id")
            else editor.putString("selected_machine_id", machines.maxByOrNull { it.lastSeenEpochMillis }!!.machineId)
            check(editor.commit()) { "Could not forget trusted machine" }
        }
        com.kompi.app.assistant.push.PushHostId.parse(selected)?.let { host ->
            com.kompi.app.assistant.push.UnifiedPushRepository.get(appContext).forget(host)
        }
    }

    /** Removes exactly one trusted host; Room history and unsent requests are deliberately untouched. */
    @Synchronized fun forgetTrustedMachine(machineId: String) {
        val id = canonicalMachineId(machineId)
        val machines = trustedMachines()
        require(machines.any { it.machineId == id }) { "Machine is not trusted" }
        val selectedBeforeRemoval = selectedMachineId()
        val remaining = machines.filterNot { it.machineId == id }
        synchronized(hostPolicyLock) {
            clearHostPolicy(id)
            persistMachines(remaining)
            val editor = prefs.edit()
            if (selectedBeforeRemoval == id) {
                val replacement = remaining.maxByOrNull { it.lastSeenEpochMillis }
                if (replacement == null) editor.remove("selected_machine_id")
                else editor.putString("selected_machine_id", replacement.machineId)
            }
            check(editor.commit()) { "Could not forget trusted machine" }
        }
        com.kompi.app.assistant.push.PushHostId.parse(id)?.let { host ->
            com.kompi.app.assistant.push.UnifiedPushRepository.get(appContext).forget(host)
        }
    }

    private fun continuationKey(machineId: String) = "conversation_continue_${canonicalMachineId(machineId)}"
    private fun concurrentKey(machineId: String) = "concurrent_chats_${canonicalMachineId(machineId)}"
    internal fun supportsConcurrentChats(machineId: String) = prefs.getBoolean(concurrentKey(machineId), false)
    internal fun rememberConcurrentSupport(machineId: String, supported: Boolean) {
        if (supportsConcurrentChats(machineId) != supported) prefs.edit().putBoolean(concurrentKey(machineId), supported).apply()
    }

    internal fun supportsContinuation(machineId: String): Boolean = prefs.getBoolean(continuationKey(machineId), false)
    internal fun rememberContinuationSupport(machineId: String, supported: Boolean) {
        if (supportsContinuation(machineId) != supported) prefs.edit().putBoolean(continuationKey(machineId), supported).apply()
    }

    private fun clearHostPolicy(machineId: String) {
        check(prefs.edit().remove(phoneGrantKey(machineId)).remove(archivePinsKey(machineId)).remove(continuationKey(machineId)).remove(concurrentKey(machineId)).commit()) {
            "Could not clear this computer's phone access and conversation settings"
        }
    }

    private fun archivePinsKey(machineId: String) = "archive_pins_${canonicalMachineId(machineId)}"

    @Synchronized internal fun archivePins(machineId: String): Set<String> {
        val encrypted = prefs.getString(archivePinsKey(machineId), null) ?: return emptySet()
        val array = org.json.JSONArray(String(decrypt(encrypted), Charsets.UTF_8))
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    /** Store the requested state, not a toggle: replay cannot undo a pin. */
    @Synchronized internal fun setArchivePinned(machineId: String, conversationId: String, pinned: Boolean): Set<String> =
        synchronized(hostPolicyLock) {
            require(java.util.UUID.fromString(conversationId).toString() == conversationId)
            require(trustedMachines().any { it.machineId == machineId }) { "Computer is not trusted" }
            val key = archivePinsKey(machineId)
            val previous = archivePins(machineId)
            val next = if (pinned) previous + conversationId else previous - conversationId
            if (next != previous) {
                if (next.isEmpty()) check(prefs.edit().remove(key).commit()) { "Could not remove conversation pins" }
                else persist(key, org.json.JSONArray(next).toString().toByteArray(Charsets.UTF_8))
            }
            next
        }

    private fun persistPairing(key: String, pairing: Pairing) {
        persist(key, JSONObject().put("endpointTicket", pairing.endpointTicket).put("secret", pairing.secret).put("name", pairing.name).toString().toByteArray(Charsets.UTF_8))
    }

    private fun persistMachines(machines: List<TrustedMachine>) {
        require(machines.size <= 64) { "Trusted machine roster is full" }
        val rows = org.json.JSONArray().also { array -> machines.forEach { machine ->
            val row = JSONObject().put("machineId", machine.machineId).put("lastSeenEpochMillis", machine.lastSeenEpochMillis).put("idleTimeoutSeconds", machine.idleTimeoutSeconds).put("pairing", JSONObject()
                .put("endpointTicket", machine.pairing.endpointTicket).put("secret", machine.pairing.secret)
                .put("name", machine.pairing.name).put("confirmed", machine.pairing.confirmed))
            machine.displayNameOverride?.let { row.put("displayNameOverride", it.value) }
            array.put(row)
        } }
        persist("trusted_machines_ciphertext", rows.toString().toByteArray(Charsets.UTF_8))
    }

    private fun endpointId(ticket: String): String = canonicalMachineId(
        com.kompi.app.transport.IrohEndpointIdentity.fromTicket(ticket),
    )

    private fun canonicalMachineId(value: String): String {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid Iroh endpoint identity" }
        return value
    }

    @Synchronized fun chatModel(machineId: String, confirmed: Boolean = false): com.kompi.app.protocol.generated.ChatModelConfiguration? {
        val name = "chat_model_${if (confirmed) "confirmed" else "draft"}_${canonicalMachineId(machineId)}"
        return prefs.getString(name, null)?.let { com.kompi.app.data.ChatModelStorage.decodeConfiguration(decrypt(it)) }
    }

    @Synchronized fun saveChatModel(machineId: String, configuration: com.kompi.app.protocol.generated.ChatModelConfiguration, confirmed: Boolean = false) {
        val name = "chat_model_${if (confirmed) "confirmed" else "draft"}_${canonicalMachineId(machineId)}"
        persist(name, com.kompi.app.data.ChatModelStorage.encodeConfiguration(configuration))
    }

    /** Encrypts sensitive Room columns with this app's Keystore-backed AES-GCM key. */
    fun protect(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** Decrypts a value previously produced by [protect]. */
    fun reveal(value: String): String = String(decrypt(value), Charsets.UTF_8)

    private fun persist(name: String, plaintext: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plaintext)
        val combined = cipher.iv + encrypted
        check(prefs.edit().putString(name, Base64.encodeToString(combined, Base64.NO_WRAP)).commit()) { "Could not persist encrypted identity" }
    }
    private fun decrypt(encoded: String): ByteArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size >= 12 + 16) { "Corrupt encrypted record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
