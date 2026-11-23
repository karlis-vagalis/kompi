// GENERATED from Rust schemars schema (reply-push-notification-payload); sha256 8df9e8791d88fe40df39f5a50e8ea3d379a69b548ea4d9bc8203442b151ad469. DO NOT EDIT.
import { z } from "zod";
export const ReplyPushNotificationPayloadSchema = z.object({"conversationId": z.string().uuid(), "expiresAtMs": z.number().int(), "hostEndpoint": z.string().regex(new RegExp("^[a-f0-9]{64}$")), "issuedAtMs": z.number().int(), "notificationId": z.string().min(1).max(128).regex(new RegExp("^[A-Za-z0-9._:-]+$")), "registrationId": z.string().uuid(), "version": z.literal(3)}).strict();
export type ReplyPushNotificationPayload = z.infer<typeof ReplyPushNotificationPayloadSchema>;
