package com.kompi.app.assistant.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionParserBehaviorTest {
    @Test fun parsesEveryAllowlistedAction() {
        val cases = listOf(
            """{"action":"list_apps"}""",
            """{"action":"open_app","packageName":"com.example.reader"}""",
            """{"action":"navigate","query":"Central Station"}""",
            """{"action":"dial","number":"+1 (555) 123-4567"}""",
            """{"action":"compose_message","number":"5551234","body":"Hello"}""",
            """{"action":"set_alarm","hour":7,"minute":30,"label":"Wake up"}""",
            """{"action":"set_timer","seconds":90,"label":"Tea"}""",
            """{"action":"open_url","url":"HTTPS://Example.COM/a/../b?q=1"}""",
            """{"action":"compose_email","to":["one@example.com","two@EXAMPLE.COM"],"subject":"Hello","body":"A message"}""",
            """{"action":"web_search","query":"weather today"}""",
            """{"action":"open_settings","target":"wifi"}""",
            """{"action":"open_settings","target":"bluetooth"}""",
            """{"action":"open_settings","target":"location"}""",
            """{"action":"open_settings","target":"app_notifications"}""",
            """{"action":"open_settings","target":"app_details"}""",
            """{"action":"open_settings","target":"alarms"}""",
        )
        cases.forEach { json -> assertTrue("Expected parser to accept $json", PhoneActionParser.parse(json) is PhoneActionParseResult.Parsed) }
    }

    @Test fun rejectsUnsupportedMalformedAndOutOfBoundsRequests() {
        val invalid = listOf(
            "not json",
            "[]",
            "{}",
            """{"action":"send_sms","number":"5551234","body":"x"}""",
            """{"action":"dial","number":"911","intent":"android.intent.action.CALL"}""",
            """{"action":"open_app","packageName":"com.example.app","component":"attacker"}""",
            """{"action":"set_alarm","hour":24,"minute":0}""",
            """{"action":"set_timer","seconds":0}""",
            """{"action":"navigate","query":"   "}""",
            """{"action":"compose_message","number":"5551234","body":""}""",
            """{"action":"navigate","query":"${"x".repeat(PhoneActionParser.MAX_JSON_BYTES)}"}""",
        )
        invalid.forEach { json -> assertTrue("Expected parser to reject $json", PhoneActionParser.parse(json) is PhoneActionParseResult.Rejected) }
    }

    @Test fun parsesAndCanonicalizesHttpsUrls() {
        val action = parsed("""{"action":"open_url","url":"HTTPS://ExAmPlE.COM/a/../b?q=1"}""") as PhoneAction.OpenUrl
        assertEquals("https://example.com/b?q=1", action.url.value)
        val unicodeDomain = parsed("""{"action":"open_url","url":"https://bücher.de/path"}""") as PhoneAction.OpenUrl
        assertEquals("https://xn--bcher-kva.de/path", unicodeDomain.url.value)
    }

    @Test fun parsesBoundedEmailDraftAndNormalizesDomains() {
        val action = parsed("""{"action":"compose_email","to":["One@Example.COM","two@bücher.de"],"subject":"Hello","body":"Line 1\nLine 2"}""") as PhoneAction.ComposeEmail
        assertEquals(listOf("One@example.com", "two@xn--bcher-kva.de"), action.recipients.map { it.value })
        assertEquals("Hello", action.subject)
        assertEquals("Line 1\nLine 2", action.body)
    }

    @Test fun rejectsUnsafeUrlsAndInvalidEmailDrafts() {
        val invalid = listOf(
            """{"action":"open_url","url":"http://example.com"}""",
            """{"action":"open_url","url":"https://user:pass@example.com"}""",
            """{"action":"open_url","url":"intent://example.com/#Intent;scheme=https;end"}""",
            """{"action":"open_url","url":"javascript:alert(1)"}""",
            """{"action":"open_url","url":"file:///etc/passwd"}""",
            """{"action":"open_url","url":"https://example.com\\\\@evil.example"}""",
            """{"action":"open_url","url":"https://example.com:70000"}""",
            """{"action":"open_url","url":"https://example.com/%0Dheader"}""",
            """{"action":"open_url","url":" https://example.com"}""",
            """{"action":"open_url","url":"https://example.com\\u0000"}""",
            """{"action":"compose_email","to":[]}""",
            """{"action":"compose_email","to":["Name <person@example.com>"]}""",
            """{"action":"compose_email","to":["person@example.com\\nBcc:evil@example.com"]}""",
            """{"action":"compose_email","to":["person@example.com"],"subject":"line 1\nline 2"}""",
            """{"action":"compose_email","to":["person@example.com"],"attachment":"content://private"}""",
            """{"action":"web_search","query":"   "}""",
            """{"action":"open_settings","target":"android.provider.Settings.ACTION_WIFI_SETTINGS"}""",
            """{"action":"open_settings","target":"unknown"}""",
            """{"action":"open_settings","target":"wifi","packageName":"com.example.app"}""",
        )
        invalid.forEach { json -> assertTrue("Expected parser to reject $json", PhoneActionParser.parse(json) is PhoneActionParseResult.Rejected) }
        val manyRecipients = (1..PhoneActionParser.MAX_EMAIL_RECIPIENTS + 1).joinToString(",") { "\"u$it@example.com\"" }
        assertTrue(PhoneActionParser.parse("""{"action":"compose_email","to":[$manyRecipients]}""") is PhoneActionParseResult.Rejected)
    }

    @Test fun rejectsOversizedNewActionArguments() {
        val oversizedUrl = org.json.JSONObject().put("action", "open_url").put("url", "https://example.com/${"x".repeat(PhoneActionParser.MAX_URL_LENGTH)}")
        val oversizedSubject = org.json.JSONObject().put("action", "compose_email").put("to", org.json.JSONArray().put("user@example.com"))
            .put("subject", "s".repeat(PhoneActionParser.MAX_EMAIL_SUBJECT_LENGTH + 1))
        val oversizedBody = org.json.JSONObject().put("action", "compose_email").put("to", org.json.JSONArray().put("user@example.com"))
            .put("body", "b".repeat(PhoneActionParser.MAX_EMAIL_BODY_LENGTH + 1))
        listOf(oversizedUrl, oversizedSubject, oversizedBody).forEach { request ->
            assertTrue(PhoneActionParser.parse(request.toString()) is PhoneActionParseResult.Rejected)
        }
    }

    @Test fun parsesOnlyTheTypedSettingsTargets() {
        val expected = mapOf(
            "wifi" to SettingsTarget.Wifi,
            "bluetooth" to SettingsTarget.Bluetooth,
            "location" to SettingsTarget.Location,
            "app_notifications" to SettingsTarget.AppNotifications,
            "app_details" to SettingsTarget.AppDetails,
            "alarms" to SettingsTarget.Alarms,
        )
        expected.forEach { (value, target) ->
            assertEquals(target, (parsed("""{"action":"open_settings","target":"$value"}""") as PhoneAction.OpenSettings).target)
        }
    }

    private fun parsed(json: String): PhoneAction = (PhoneActionParser.parse(json) as PhoneActionParseResult.Parsed).action

    @Test fun parserErrorCodesAreStableAtBoundary() {
        assertEquals("too_large", (PhoneActionParser.parse("x".repeat(PhoneActionParser.MAX_JSON_BYTES + 1)) as PhoneActionParseResult.Rejected).code)
        assertEquals("unsupported_action", (PhoneActionParser.parse("""{"action":"launch_anything"}""") as PhoneActionParseResult.Rejected).code)
    }
}
