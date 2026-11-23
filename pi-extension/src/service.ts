import { createHash } from "node:crypto";
import { z } from "zod";
import { AgentNotificationSchema, type AgentNotification } from "./protocol/generated.js";
import { NativeCliError, NotificationNotDispatchedError, runStatusJson, runPairJson, runDevicesJson, runRevokeJson, runNotificationJsonOutput, serviceUnavailable } from "./native-cli.js";
type Device = Awaited<ReturnType<typeof runDevicesJson>>["devices"][number];
const DeviceNotificationResultSchema = z.object({
  endpoint: z.string().regex(/^[a-f0-9]{64}$/),
  status: z.enum(["queued", "push-accepted", "posted", "not-posted", "unknown", "offline", "unavailable"]),
  code: z.string().max(64).regex(/^[a-z0-9-]+$/).nullable().optional(),
});
export type DeviceNotificationResult = z.infer<typeof DeviceNotificationResultSchema>;

export function notificationRequestId(endpoint: string, toolCallId: string): string {
  return createHash("sha256")
    .update("kompi-terminal-notification\0")
    .update(endpoint).update("\0")
    .update(toolCallId)
    .digest("hex");
}

export function describeDeviceNotificationStatus(status: DeviceNotificationResult["status"]): string {
  switch (status) {
    case "queued": return "Durably queued for phone delivery; this does not mean Android posted it.";
    case "push-accepted": return "Push provider accepted delivery; this does not mean Android received or posted it.";
    case "posted": return "Android confirmed posting; this does not mean the user saw or read it.";
    case "not-posted": return "Android did not post the notification.";
    case "unknown": return "Delivery outcome is unknown; the phone may have acted. Do not retry automatically.";
    case "offline": return "The phone is offline and no live notification route is available.";
    case "unavailable": return "No background provider or live route is available. Chat remains available; connected notifications work when the phone reconnects.";
  }
}
interface PairingDisplay { terminalQr: string; png: Buffer; name: string; expiresAt: number; token?: string; }

/** Operator CLI adapter only. Phone sessions are daemon-owned and need no terminal Pi process. */
export class NativeBridge {

  async status(): Promise<string> {
    const status = await this.cliJson(runStatusJson());
    return [
      `Native kompi: ${status.running ? "running" : "stopped"}`,
      status.endpoint ? `Endpoint: ${status.endpoint}` : "",
      `Paired devices: ${status.pairedDevices?.length ?? 0}`,
    ].filter(Boolean).join("\n");
  }

  async createPairing(name: string | undefined, tokenRequested: boolean): Promise<PairingDisplay> {
    const args = ["pair", "--json"];
    if (tokenRequested) args.push("--token");
    if (name) args.push(name);
    const result = await this.cliJson(runPairJson(args));
    const pairing = result.pairing;
    if (!pairing.terminalQr) throw new Error("Native kompi did not include its full terminal QR rendering");
    if (tokenRequested && !pairing.token) throw new Error("Native kompi did not return the requested pairing token");
    const expiresAt = Date.parse(pairing.expiresAt);
    if (!Number.isFinite(expiresAt)) throw new Error("Native kompi returned an invalid pairing expiry");
    return {
      terminalQr: pairing.terminalQr,
      png: pairing.pngBase64 ? Buffer.from(pairing.pngBase64, "base64") : Buffer.alloc(0),
      name: pairing.name || name || "This machine",
      expiresAt,
      ...(pairing.token ? { token: pairing.token } : {}),
    };
  }

  async listDevices(): Promise<readonly Device[]> {
    const result = await this.cliJson(runDevicesJson());
    return result.devices;
  }

  async revokeDevice(endpoint: string): Promise<void> {
    await this.cliJson(runRevokeJson(endpoint));
  }

  async sendNotification(endpoint: string, title: string, body: string, requestId: string, signal?: AbortSignal): Promise<DeviceNotificationResult> {
    return this.sendRichNotification(endpoint, { title, body }, requestId, signal);
  }

  async sendRichNotification(endpoint: string, notification: AgentNotification, requestId: string, signal?: AbortSignal): Promise<DeviceNotificationResult> {
    notification = AgentNotificationSchema.parse(notification);
    let stdout: string;
    try {
      stdout = await runNotificationJsonOutput([
        "devices", "notify", endpoint, "--request-id", requestId, "--json", "--payload-stdin",
      ], JSON.stringify(notification), { operationLabel: "kompi devices notify", redactInput: true, signal });
    } catch (error) {
      if (error instanceof NotificationNotDispatchedError) {
        return { endpoint, status: "not-posted", code: error.code };
      }
      if (error instanceof NativeCliError) {
        return { endpoint, status: "unknown", code: "delivery-unknown" };
      }
      return { endpoint, status: "not-posted", code: "native-cli-unavailable" };
    }

    let parsed: DeviceNotificationResult;
    try { parsed = DeviceNotificationResultSchema.parse(JSON.parse(stdout)); }
    catch {
      return {
        endpoint,
        status: "unknown",
        code: "invalid-delivery-receipt",
      };
    }
    if (parsed.endpoint !== endpoint) {
      return {
        endpoint,
        status: "unknown",
        code: "endpoint-mismatch",
      };
    }
    return parsed;
  }

  private async cliJson<T>(operation: Promise<T>): Promise<T> {
    try { return await operation; }
    catch (error) { throw serviceUnavailable(error); }
  }

}
