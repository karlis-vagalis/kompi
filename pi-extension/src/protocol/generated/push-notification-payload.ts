// GENERATED from Rust schemars schema (push-notification-payload); sha256 6a7f24a527c91be4eec691ea23f3cb339a52ce32eedf0861c3a02b24ad30ae0f. DO NOT EDIT.
import { z } from "zod";
export const PushNotificationPayloadSchema = z.object({"body": z.string().min(1).max(2000), "expiresAtMs": z.number().int(), "hostEndpoint": z.string().regex(new RegExp("^[a-f0-9]{64}$")), "issuedAtMs": z.number().int(), "notificationId": z.string().min(1).max(128).regex(new RegExp("^[A-Za-z0-9._:-]+$")), "registrationId": z.string().uuid(), "title": z.string().min(1).max(120), "version": z.literal(1)}).strict();
export type PushNotificationPayload = z.infer<typeof PushNotificationPayloadSchema>;
