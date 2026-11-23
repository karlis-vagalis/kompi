package com.kompi.app.assistant.actions

import org.json.JSONException
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.util.Locale

/** Parsed, bounded phone capabilities. Only [PhoneActionParser] constructs these values. */
sealed interface PhoneAction {
    class Native internal constructor(internal val operation: com.kompi.app.assistant.native.NativeOperation) : PhoneAction
    data object ListApps : PhoneAction
    class OpenApp internal constructor(val packageName: String) : PhoneAction
    class Navigate internal constructor(val query: String) : PhoneAction
    class Dial internal constructor(val number: String) : PhoneAction
    class ComposeMessage internal constructor(val number: String, val body: String) : PhoneAction
    class SetAlarm internal constructor(val hour: Int, val minute: Int, val label: String) : PhoneAction
    class SetTimer internal constructor(val seconds: Int, val label: String) : PhoneAction
    class OpenUrl internal constructor(val url: HttpsUrl) : PhoneAction
    class ComposeEmail internal constructor(val recipients: List<EmailAddress>, val subject: String, val body: String) : PhoneAction
    class WebSearch internal constructor(val query: String) : PhoneAction
    class OpenSettings internal constructor(val target: SettingsTarget) : PhoneAction
}

/** An HTTPS destination parsed and canonicalized at the untrusted JSON boundary. */
data class HttpsUrl internal constructor(val value: String)

/** A normalized mailbox address; display names and non-ASCII domain spellings are not accepted. */
data class EmailAddress internal constructor(val value: String)

enum class SettingsTarget(val confirmationLabel: String) {
    Wifi("Wi-Fi settings"),
    Bluetooth("Bluetooth settings"),
    Location("Location settings"),
    AppNotifications("Kompi notification settings"),
    AppDetails("Kompi app settings"),
    Alarms("Alarm list"),
}

sealed interface PhoneActionParseResult {
    data class Parsed(val action: PhoneAction) : PhoneActionParseResult
    data class Rejected(val code: String, val message: String) : PhoneActionParseResult
}

/** Strict JSON boundary for the supported, explicit phone-action allowlist. Parsing has no side effects. */
object PhoneActionParser {
    const val MAX_JSON_BYTES = 8_192
    const val MAX_URL_LENGTH = 2_048
    const val MAX_EMAIL_RECIPIENTS = 10
    const val MAX_EMAIL_ADDRESS_LENGTH = 254
    const val MAX_EMAIL_SUBJECT_LENGTH = 200
    const val MAX_EMAIL_BODY_LENGTH = 4_000

    fun parse(json: String): PhoneActionParseResult {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return reject("too_large", "Action request is too large.")
        return try {
            val obj = JSONObject(json)
            val type = requiredString(obj, "action", 32)
            com.kompi.app.assistant.native.NativeActions.parse(type, obj)?.let { return PhoneActionParseResult.Parsed(PhoneAction.Native(it)) }
            if (obj.length() > 5) return reject("invalid_shape", "Action request has unexpected fields.")
            when (type) {
                "list_apps" -> {
                    if (!hasOnly(obj, "action")) reject("invalid_shape", "Action request has unexpected fields.")
                    else PhoneActionParseResult.Parsed(PhoneAction.ListApps)
                }
                "open_app" -> if (!hasOnly(obj, "action", "packageName")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.OpenApp(packageName(requiredString(obj, "packageName", 255))))
                "navigate" -> if (!hasOnly(obj, "action", "query")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.Navigate(text(requiredString(obj, "query", 500), "query", 1, 500)))
                "dial" -> if (!hasOnly(obj, "action", "number")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.Dial(phoneNumber(requiredString(obj, "number", 40))))
                "compose_message" -> if (!hasOnly(obj, "action", "number", "body")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.ComposeMessage(phoneNumber(requiredString(obj, "number", 40)), text(requiredString(obj, "body", 2000), "body", 1, 2000)))
                "set_alarm" -> if (!hasOnly(obj, "action", "hour", "minute", "label")) reject("invalid_shape", "Action request has unexpected fields.") else {
                    val hour = integer(obj, "hour", 0..23)
                    val minute = integer(obj, "minute", 0..59)
                    val label = optionalLabel(obj, "label")
                    PhoneActionParseResult.Parsed(PhoneAction.SetAlarm(hour, minute, label))
                }
                "set_timer" -> if (!hasOnly(obj, "action", "seconds", "label")) reject("invalid_shape", "Action request has unexpected fields.") else {
                    val seconds = integer(obj, "seconds", 1..86_400)
                    val label = optionalLabel(obj, "label")
                    PhoneActionParseResult.Parsed(PhoneAction.SetTimer(seconds, label))
                }
                "open_url" -> if (!hasOnly(obj, "action", "url")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.OpenUrl(httpsUrl(requiredRawString(obj, "url", MAX_URL_LENGTH))))
                "compose_email" -> if (!hasOnly(obj, "action", "to", "subject", "body")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.ComposeEmail(emailRecipients(obj), optionalText(obj, "subject", MAX_EMAIL_SUBJECT_LENGTH, allowLineBreaks = false), optionalText(obj, "body", MAX_EMAIL_BODY_LENGTH, allowLineBreaks = true)))
                "web_search" -> if (!hasOnly(obj, "action", "query")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.WebSearch(text(requiredString(obj, "query", 500), "query", 1, 500)))
                "open_settings" -> if (!hasOnly(obj, "action", "target")) reject("invalid_shape", "Action request has unexpected fields.") else
                    PhoneActionParseResult.Parsed(PhoneAction.OpenSettings(settingsTarget(requiredString(obj, "target", 32))))
                else -> reject("unsupported_action", "This phone action is not supported.")
            }
        } catch (_: InvalidAction) {
            reject("invalid_argument", "Action request contains an invalid or missing argument.")
        } catch (_: JSONException) {
            reject("invalid_json", "Action request must be a JSON object.")
        } catch (_: IllegalArgumentException) {
            reject("invalid_argument", "Action request contains an invalid or missing argument.")
        }
    }

    private class InvalidAction : RuntimeException()
    private fun requiredString(o: JSONObject, key: String, max: Int): String {
        if (!o.has(key) || o.isNull(key)) throw InvalidAction()
        val value = o.opt(key) as? String ?: throw InvalidAction()
        return text(value, key, 1, max)
    }
    private fun requiredRawString(o: JSONObject, key: String, max: Int): String {
        if (!o.has(key) || o.isNull(key)) throw InvalidAction()
        val value = o.opt(key) as? String ?: throw InvalidAction()
        if (value.length !in 1..max || value.any { it.isISOControl() }) throw InvalidAction()
        return value
    }
    private fun text(value: String, key: String, min: Int, max: Int): String {
        val normalized = value.trim()
        if (normalized.length !in min..max || normalized.any { it.isISOControl() }) throw InvalidAction()
        return normalized
    }
    private fun optionalText(o: JSONObject, key: String, max: Int, allowLineBreaks: Boolean): String {
        if (!o.has(key)) return ""
        if (o.isNull(key)) throw InvalidAction()
        val raw = o.opt(key) as? String ?: throw InvalidAction()
        if (raw.length > max || raw.any { it.isISOControl() && !(allowLineBreaks && (it == '\n' || it == '\r' || it == '\t')) }) throw InvalidAction()
        return raw
    }
    private fun phoneNumber(raw: String): String {
        val value = text(raw, "number", 1, 40)
        if (!value.matches(Regex("[+0-9().\\- ]{1,40}")) || value.count { it.isDigit() } !in 3..20) throw InvalidAction()
        return value
    }
    private fun packageName(raw: String): String {
        if (!raw.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) throw InvalidAction()
        return raw
    }
    private fun integer(o: JSONObject, key: String, range: IntRange): Int {
        val raw = o.opt(key) as? Number ?: throw InvalidAction()
        val value = raw.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value < range.first || value > range.last) throw InvalidAction()
        return value.toInt()
    }
    private fun optionalLabel(o: JSONObject, key: String): String {
        if (!o.has(key)) return ""
        if (o.isNull(key)) throw InvalidAction()
        val raw = o.opt(key) as? String ?: throw InvalidAction()
        if (raw.isBlank()) {
            if (raw.any { it.isISOControl() }) throw InvalidAction()
            return ""
        }
        return text(raw, key, 1, 80)
    }
    private fun httpsUrl(raw: String): HttpsUrl {
        if (raw != raw.trim() || raw.any { it.isISOControl() || it.isWhitespace() } || '\\' in raw || ENCODED_CONTROL.containsMatchIn(raw)) throw InvalidAction()
        val parsed = try { URI(raw) } catch (_: Exception) { throw InvalidAction() }
        if (!parsed.isAbsolute || parsed.isOpaque || !parsed.scheme.equals("https", ignoreCase = true)) throw InvalidAction()
        if (parsed.rawUserInfo != null) throw InvalidAction()
        val authority = parsed.rawAuthority ?: throw InvalidAction()
        if ('@' in authority || '%' in authority) throw InvalidAction()
        val host: String
        val port: Int
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) throw InvalidAction()
            host = authority.substring(0, close + 1)
            val suffix = authority.substring(close + 1)
            port = if (suffix.isEmpty()) -1 else {
                if (!suffix.startsWith(":")) throw InvalidAction()
                val digits = suffix.drop(1)
                if (digits.isEmpty() || !digits.all { it.isDigit() }) throw InvalidAction()
                digits.toIntOrNull() ?: throw InvalidAction()
            }
            if (parsed.host == null) throw InvalidAction()
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0 && authority.indexOf(':') != colon) throw InvalidAction()
            val hostPart = if (colon >= 0) authority.substring(0, colon) else authority
            val portText = if (colon >= 0) authority.substring(colon + 1) else ""
            if (colon >= 0 && (portText.isEmpty() || !portText.all { it.isDigit() })) throw InvalidAction()
            port = if (colon >= 0) portText.toIntOrNull() ?: throw InvalidAction() else -1
            host = try { IDN.toASCII(hostPart, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) } catch (_: IllegalArgumentException) { throw InvalidAction() }
        }
        if (host.isEmpty() || (!host.startsWith("[") && (host.length > 253 || host.split('.').any { it.length > 63 })) || port == 0 || port > 65_535) throw InvalidAction()
        val canonical = buildString {
            append("https://")
            append(host.lowercase(Locale.ROOT))
            if (port >= 0 && port != 443) append(':').append(port)
            val normalized = parsed.normalize()
            append(normalized.rawPath.orEmpty())
            normalized.rawQuery?.let { append('?').append(it) }
            normalized.rawFragment?.let { append('#').append(it) }
        }
        val asciiCanonical = try { URI(canonical).toASCIIString() } catch (_: Exception) { throw InvalidAction() }
        return HttpsUrl(asciiCanonical)
    }
    private fun emailRecipients(o: JSONObject): List<EmailAddress> {
        val raw = o.optJSONArray("to") ?: throw InvalidAction()
        if (raw.length() !in 1..MAX_EMAIL_RECIPIENTS) throw InvalidAction()
        return (0 until raw.length()).map { index ->
            val value = raw.opt(index) as? String ?: throw InvalidAction()
            emailAddress(value)
        }.distinctBy { it.value.lowercase(Locale.ROOT) }.also {
            if (it.size != raw.length()) throw InvalidAction()
        }
    }
    private fun emailAddress(raw: String): EmailAddress {
        if (raw != raw.trim() || raw.length !in 3..MAX_EMAIL_ADDRESS_LENGTH || raw.any { it.isWhitespace() || it.isISOControl() }) throw InvalidAction()
        val at = raw.indexOf('@')
        if (at !in 1 until raw.lastIndex || raw.lastIndexOf('@') != at) throw InvalidAction()
        val local = raw.substring(0, at)
        if (local.length > 64 || !local.matches(Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"))) throw InvalidAction()
        val domain = raw.substring(at + 1)
        val asciiDomain = try { IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) } catch (_: IllegalArgumentException) { throw InvalidAction() }
        if (asciiDomain.length > 253 || asciiDomain.split('.').size < 2 || asciiDomain.split('.').any { label ->
                label.isEmpty() || label.length > 63 || !label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
            }) throw InvalidAction()
        val normalized = "$local@$asciiDomain"
        if (normalized.length > MAX_EMAIL_ADDRESS_LENGTH) throw InvalidAction()
        return EmailAddress(normalized)
    }
    private val ENCODED_CONTROL = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE)
    private fun settingsTarget(raw: String): SettingsTarget = when (raw) {
        "wifi" -> SettingsTarget.Wifi
        "bluetooth" -> SettingsTarget.Bluetooth
        "location" -> SettingsTarget.Location
        "app_notifications" -> SettingsTarget.AppNotifications
        "app_details" -> SettingsTarget.AppDetails
        "alarms" -> SettingsTarget.Alarms
        else -> throw InvalidAction()
    }
    private fun hasOnly(o: JSONObject, vararg fields: String) = o.keys().asSequence().all { it in fields }
    private fun reject(code: String, message: String) = PhoneActionParseResult.Rejected(code, message)
}

/** Human-readable confirmation copy; never executes or resolves the action. */
object PhoneActionRenderer {
    fun confirmation(action: PhoneAction): String = when (action) {
        is PhoneAction.Native -> action.operation.confirmation
        PhoneAction.ListApps -> "Show apps that can be opened"
        is PhoneAction.OpenApp -> "Open app ${action.packageName}"
        is PhoneAction.Navigate -> "Search maps for: ${action.query}"
        is PhoneAction.Dial -> "Open dialer for ${action.number}"
        is PhoneAction.ComposeMessage -> "Compose a message to ${action.number}: ${action.body}"
        is PhoneAction.SetAlarm -> "Request alarm for ${"%02d:%02d".format(action.hour, action.minute)}${action.label.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""}. Clock may set it immediately."
        is PhoneAction.SetTimer -> "Request ${action.seconds}-second timer${action.label.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""}. Clock may start it immediately."
        is PhoneAction.OpenUrl -> "Open ${action.url.value}"
        is PhoneAction.ComposeEmail -> buildString {
            append("Compose email to ${action.recipients.joinToString { it.value }}")
            if (action.subject.isNotEmpty()) append(" — ${action.subject}")
            if (action.body.isNotEmpty()) append("\n\n${action.body}")
        }
        is PhoneAction.WebSearch -> "Search the web for: ${action.query}"
        is PhoneAction.OpenSettings -> "Open ${action.target.confirmationLabel}"
    }

    fun guidance(action: PhoneAction): String = when (action) {
        is PhoneAction.Native -> action.operation.guidance
        is PhoneAction.SetAlarm, is PhoneAction.SetTimer -> "Clock may act immediately after you confirm here."
        is PhoneAction.ComposeMessage -> "Your messaging app will open a draft. You must send it there."
        is PhoneAction.ComposeEmail -> "Your email app will open a draft. Kompi will not send it."
        is PhoneAction.OpenUrl -> "Android will open this HTTPS destination. Kompi cannot verify that the page loaded."
        is PhoneAction.WebSearch -> "Android will hand this query to an available search provider."
        is PhoneAction.OpenSettings -> if (action.target == SettingsTarget.Alarms) "This opens the Clock alarm list; Kompi cannot verify alarm state." else "This opens a Settings screen; Kompi will not change a setting."
        PhoneAction.ListApps -> "Kompi will list visible launcher apps, not every installed app."
        is PhoneAction.OpenApp -> "Android will open the selected app; Kompi cannot verify what it does next."
        is PhoneAction.Navigate -> "Android will open a map search; this may not start turn-by-turn navigation."
        is PhoneAction.Dial -> "Your phone app will show the number. You decide whether to place the call."
    }
}
