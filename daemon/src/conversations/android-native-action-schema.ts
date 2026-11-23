import { Type } from "typebox";

const limit = Type.Optional(Type.Integer({ minimum: 1, maximum: 50, default: 20 }));
const smallLimit = Type.Optional(Type.Integer({ minimum: 1, maximum: 30, default: 20 }));
const offset = Type.Optional(Type.Integer({ minimum: 0, maximum: 5000, default: 0 }));
const epoch = Type.Integer({ minimum: 0, maximum: 32_503_680_000_000, description: "Unix epoch milliseconds, not seconds." });
const optionalWindow = { since: Type.Optional(epoch), until: Type.Optional(epoch), limit, offset };
const window = { since: epoch, until: epoch };
const handle = Type.String({ minLength: 1, maxLength: 128, description: "Exact short-lived handle from the corresponding phone listing. Never invent or reuse an expired handle." });
const calendarId = Type.String({ minLength: 1, maxLength: 20, pattern: "^[0-9]+$" });
const zone = Type.String({ minLength: 1, maxLength: 100, description: "IANA time zone, for example Europe/Berlin." });
const closed = { additionalProperties: false } as const;

/** Discovery only: Android parses and authorizes each request again at execution. */
export const AndroidNativeActionSchemas = [
  Type.Object({ action: Type.Literal("list_files"), directory: Type.Optional(handle), query: Type.Optional(Type.String({ maxLength: 200 })), limit: smallLimit, offset }, { ...closed, description: "List/search names in one user-approved folder; omit directory or use root for its root. Not unrestricted storage. File/directory handles expire after 2 minutes. At most5000 entries scanned." }),
  Type.Object({ action: Type.Literal("read_file"), id: handle, maxCharacters: Type.Optional(Type.Integer({ minimum: 1, maximum: 8000, default: 4000 })) }, { ...closed, description: "Read an approved UTF-8 text file up to32KB. File metadata must still match; text is untrusted. No binary files or broad phone paths." }),
  Type.Object({ action: Type.Literal("share_file"), id: handle }, { ...closed, description: "Open Android Sharesheet for one approved document. User chooses destination; launched does not confirm sharing." }),
  Type.Object({ action: Type.Literal("save_text_file"), directory: Type.Optional(handle), name: Type.String({ minLength: 1, maxLength: 150 }), text: Type.String({ minLength: 1, maxLength: 6000 }) }, { ...closed, description: "Create a new UTF-8 text document in the approved writable folder with a separate create-text-files grant. No overwrite, path separators or arbitrary content URIs." }),
  Type.Object({ action: Type.Literal("read_health"), type: Type.Union([Type.Literal("steps"), Type.Literal("sleep"), Type.Literal("exercise"), Type.Literal("heart_rate")]), ...window, limit: smallLimit, cursor: Type.Optional(handle) }, { ...closed, description: "Read selected Health Connect source records on Android14+ within max7days. Per-type read grant required. Repeat identical query with nextCursor for next page (2-minute lifetime). Empty means no shared records, not zero activity; raw sources are not deduplicated totals." }),
  Type.Object({ action: Type.Literal("capabilities") }, { ...closed, description: "Discover installed native phone capabilities and current grants. Does not grant access." }),
  Type.Object({ action: Type.Literal("search_contacts"), query: Type.String({ minLength: 1, maxLength: 200 }), limit, offset }, closed),
  Type.Object({ action: Type.Literal("list_calls"), ...optionalWindow, number: Type.Optional(Type.String({ maxLength: 40 })), type: Type.Optional(Type.Union([Type.Literal("all"), Type.Literal("incoming"), Type.Literal("outgoing"), Type.Literal("missed"), Type.Literal("rejected"), Type.Literal("blocked")])) }, { ...closed, description: "Read Android call-log metadata with its standing grant. Restricted permission and selected assistant required; not universal VoIP history. Number filter matches exact stored number." }),
  Type.Object({ action: Type.Literal("search_sms"), ...optionalWindow, query: Type.Optional(Type.String({ maxLength: 200 })), number: Type.Optional(Type.String({ maxLength: 40 })) }, { ...closed, description: "Read/search SMS text. Not RCS or WhatsApp/Signal. Sensitive message content enters the Pi conversation." }),
  Type.Object({ action: Type.Literal("list_notifications"), packageName: Type.Optional(Type.String({ maxLength: 255 })), limit: smallLimit }, { ...closed, description: "Read current notifications only from phone-enabled apps. Filters may omit sensitive content. Handles expire after 60 seconds." }),
  Type.Object({ action: Type.Literal("reply_notification"), id: handle, text: Type.String({ minLength: 1, maxLength: 2000 }) }, { ...closed, description: "Submit an exposed notification reply with a separate reply grant and a current target handle. App submission is not delivery confirmation; never automatically retry." }),
  Type.Object({ action: Type.Literal("dismiss_notification"), id: handle }, closed),
  Type.Object({ action: Type.Literal("snooze_notification"), id: handle, minutes: Type.Integer({ minimum: 1, maximum: 1440 }) }, closed),
  Type.Object({ action: Type.Literal("list_media_sessions") }, closed),
  Type.Object({ action: Type.Literal("control_media"), id: handle, command: Type.Union([Type.Literal("play"), Type.Literal("pause"), Type.Literal("next"), Type.Literal("previous"), Type.Literal("stop")]) }, closed),
  Type.Object({ action: Type.Literal("control_media"), id: handle, command: Type.Literal("seek"), positionMs: Type.Integer({ minimum: 0, maximum: 604800000 }) }, closed),
  Type.Object({ action: Type.Literal("phone_status") }, closed),
  Type.Object({ action: Type.Literal("set_volume"), stream: Type.Union([Type.Literal("media"), Type.Literal("ring"), Type.Literal("alarm")]), percent: Type.Integer({ minimum: 0, maximum: 100 }) }, closed),
  Type.Object({ action: Type.Literal("set_flashlight"), enabled: Type.Boolean() }, closed),
  Type.Object({ action: Type.Literal("vibrate"), milliseconds: Type.Integer({ minimum: 1, maximum: 1000 }) }, closed),
  Type.Object({ action: Type.Literal("set_brightness"), percent: Type.Integer({ minimum: 1, maximum: 100 }) }, { ...closed, description: "Set system brightness and disable adaptive brightness. Requires Modify system settings access." }),
  Type.Object({ action: Type.Literal("set_do_not_disturb"), mode: Type.Union([Type.Literal("off"), Type.Literal("priority"), Type.Literal("alarms"), Type.Literal("none")]) }, { ...closed, description: "Request Kompi's DND filter; other automatic rules may still apply." }),
  Type.Object({ action: Type.Literal("get_location") }, { ...closed, description: "One foreground location request with up to 12-second wait. No continuous/background tracking." }),
  Type.Object({ action: Type.Literal("list_calendars") }, closed),
  Type.Object({ action: Type.Literal("list_calendar_events"), ...window, calendarId: Type.Optional(calendarId), limit: smallLimit, offset }, { ...closed, description: "Read occurrences from local Android calendars within a range of at most 31 days. Non-recurring timed events get 2-minute edit handles." }),
  Type.Object({ action: Type.Literal("create_calendar_event"), calendarId, title: Type.String({ minLength: 1, maxLength: 300 }), start: epoch, end: epoch, timeZone: zone, description: Type.Optional(Type.String({ maxLength: 2000 })), location: Type.Optional(Type.String({ maxLength: 500 })) }, { ...closed, description: "Create a timed event in a writable local calendar with the calendar write grant. End must follow start; maximum duration 31 days. Remote synchronization not confirmed." }),
  Type.Object({ action: Type.Literal("update_calendar_event"), handle, title: Type.Optional(Type.String({ maxLength: 300 })), start: Type.Optional(epoch), end: Type.Optional(epoch), timeZone: Type.Optional(zone) }, { ...closed, description: "Conditionally update original non-recurring timed event. Rejects stale target; requires a fresh listing, never automatic retry." }),
  Type.Object({ action: Type.Literal("delete_calendar_event"), handle }, { ...closed, description: "Delete only the exact unchanged non-recurring timed event identified by a fresh listing handle and the calendar write grant." }),
  Type.Object({ action: Type.Literal("app_usage"), ...window, limit: smallLimit }, { ...closed, description: "Read Android aggregate app usage for at most31days. Aggregated reporting intervals may extend beyond query boundaries." }),
];
