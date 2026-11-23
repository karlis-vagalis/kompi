// GENERATED from Rust schemars schema (push-authenticated-payload); sha256 5b9c58966aa65d53cfba80dc949c1ac54d53d53fd42eea692f71ccee0e7a6ebc. DO NOT EDIT.
import { z } from "zod";
export const PushAuthenticatedPayloadSchema = z.object({"mac": z.string().min(43).max(43), "payload": z.string().max(32768), "version": z.literal(1)}).strict();
export type PushAuthenticatedPayload = z.infer<typeof PushAuthenticatedPayloadSchema>;
