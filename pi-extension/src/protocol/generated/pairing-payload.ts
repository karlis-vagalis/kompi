// GENERATED from Rust schemars schema (pairing-payload); sha256 97cf5cedd5147dbdcb84309742166faf1c5c6e19bff03840bbd3fded2e2c9b59. DO NOT EDIT.
import { z } from "zod";
export const PairingPayloadSchema = z.object({"endpointTicket": z.string().min(1).max(2048), "name": z.string().min(1).max(128), "secret": z.string().min(43).max(256).regex(new RegExp("^[A-Za-z0-9_-]+$")), "v": z.literal(1)}).strict();
export type PairingPayload = z.infer<typeof PairingPayloadSchema>;
