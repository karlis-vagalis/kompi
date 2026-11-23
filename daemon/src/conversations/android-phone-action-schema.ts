import { Type } from "typebox";
import { AndroidNativeActionSchemas } from "./android-native-action-schema.js";

/**
 * Model-facing discovery, not authorization. Android's PhoneActionParser remains the
 * authoritative semantic boundary (including URI/mailbox normalization and UTF-8 limits).
 * Keep these action names, fields and bounds aligned with that phone capability set.
 */
export const AndroidPhoneActionSchema = Type.Union([
  ...AndroidNativeActionSchemas,
  Type.Object({
    action: Type.Literal("list_apps"),
  }, { additionalProperties: false, description: "List the phone's visible launcher apps with its standing grant; not an inventory of every installed package." }),
  Type.Object({
    action: Type.Literal("open_app"),
    packageName: Type.String({ minLength: 1, maxLength: 255, pattern: "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$", description: "Exact Android package name from the visible app listing, not an app label." }),
  }, { additionalProperties: false, description: "Open an app's launcher activity. Launching is not proof of subsequent app behavior." }),
  Type.Object({
    action: Type.Literal("navigate"),
    query: Type.String({ minLength: 1, maxLength: 500, description: "Map search destination or address, without control characters." }),
  }, { additionalProperties: false, description: "Open a map search; this does not necessarily start turn-by-turn navigation." }),
  Type.Object({
    action: Type.Literal("dial"),
    number: Type.String({ minLength: 3, maxLength: 40, pattern: "^[+0-9(). -]+$", description: "Phone number containing 3–20 digits; only digits, +, parentheses, dot, hyphen and spaces are accepted. No extensions or USSD." }),
  }, { additionalProperties: false, description: "Open the dialer with a number. The user decides whether to call; Kompi never places a call directly." }),
  Type.Object({
    action: Type.Literal("compose_message"),
    number: Type.String({ minLength: 3, maxLength: 40, pattern: "^[+0-9(). -]+$", description: "Recipient phone number containing 3–20 digits, without extensions or USSD." }),
    body: Type.String({ minLength: 1, maxLength: 2_000, description: "Message draft, without control characters or line breaks." }),
  }, { additionalProperties: false, description: "Open a message draft. The user must send it in the messaging app." }),
  Type.Object({
    action: Type.Literal("set_alarm"),
    hour: Type.Integer({ minimum: 0, maximum: 23, description: "Local phone hour in 24-hour time." }),
    minute: Type.Integer({ minimum: 0, maximum: 59 }),
    label: Type.Optional(Type.String({ maxLength: 80, description: "Optional alarm label, without control characters." })),
  }, { additionalProperties: false, description: "Request an alarm with its standing grant. Clock may enable it immediately; no further confirmation is guaranteed, and saving is not verified." }),
  Type.Object({
    action: Type.Literal("set_timer"),
    seconds: Type.Integer({ minimum: 1, maximum: 86_400 }),
    label: Type.Optional(Type.String({ maxLength: 80, description: "Optional timer label, without control characters." })),
  }, { additionalProperties: false, description: "Request a timer with its standing grant. Clock may start it immediately; no further confirmation is guaranteed, and completion is not verified." }),
  Type.Object({
    action: Type.Literal("open_url"),
    url: Type.String({ minLength: 1, maxLength: 2_048, description: "Absolute HTTPS URL only. No credentials, whitespace, backslashes, control characters or encoded controls. Kompi validates and canonicalizes the destination before launch." }),
  }, { additionalProperties: false, description: "Open an allowed HTTPS destination. No arbitrary URI schemes, components or grant flags; page loading is not verified." }),
  Type.Object({
    action: Type.Literal("compose_email"),
    to: Type.Array(Type.String({ minLength: 3, maxLength: 254, description: "Bare mailbox address, not a display name or mailto URI. No whitespace; duplicates are rejected case-insensitively." }), { minItems: 1, maxItems: 10, uniqueItems: true }),
    subject: Type.Optional(Type.String({ maxLength: 200, description: "Optional subject without line breaks or control characters." })),
    body: Type.Optional(Type.String({ maxLength: 4_000, description: "Optional body; line breaks and tabs are allowed, other control characters are not." })),
  }, { additionalProperties: false, description: "Open an email draft for 1–10 recipients. No attachments, CC or BCC; Kompi never sends it." }),
  Type.Object({
    action: Type.Literal("web_search"),
    query: Type.String({ minLength: 1, maxLength: 500, description: "Web search query, without control characters." }),
  }, { additionalProperties: false, description: "Hand a query to an available Android search provider; results are not returned or verified." }),
  Type.Object({
    action: Type.Literal("open_settings"),
    target: Type.Union([
      Type.Literal("wifi"), Type.Literal("bluetooth"), Type.Literal("location"),
      Type.Literal("app_notifications"), Type.Literal("app_details"), Type.Literal("alarms"),
    ], { description: "Fixed settings destinations only. app_notifications and app_details refer only to Kompi; alarms opens the alarm list." }),
  }, { additionalProperties: false, description: "Open a fixed system Settings screen or alarm list; this never changes a setting." }),
], { description: "Exactly one supported phone capability. Execution requires a matching standing grant and a foreground, unlocked phone. No arbitrary Android intents or automatic completion claims." });
