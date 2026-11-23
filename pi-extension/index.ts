import { Type } from "typebox";
import { defineTool, type ExtensionAPI, type ExtensionCommandContext } from "@earendil-works/pi-coding-agent";
import { describeDeviceNotificationStatus, NativeBridge, notificationRequestId } from "./src/service.js";
import { showPairing, showPairingToken } from "./src/pairing/view.js";
import { AgentNotificationJsonSchema, type AgentNotification } from "./src/protocol/generated.js";

type Command =
  | { kind: "pair"; name?: string; token: boolean }
  | { kind: "status" }
  | { kind: "devices-list" }
  | { kind: "devices-revoke"; endpoint: string };

function parseCommand(raw: string): Command {
  const [verb, ...args] = raw.trim().split(/\s+/).filter(Boolean);
  switch (verb) {
    case "pair": {
      let token = false;
      const name: string[] = [];
      for (const arg of args) {
        if (arg === "--token") token = true;
        else if (arg.startsWith("-")) throw new Error(`Unknown pair option: ${arg}`);
        else name.push(arg);
      }
      if (name.length > 8) throw new Error("Pairing name is too long");
      return { kind: "pair", ...(name.length ? { name: name.join(" ") } : {}), token };
    }
    case "status":
      if (args.length) throw new Error("/kompi status takes no arguments");
      return { kind: verb };
    case "devices":
      if (args[0] === "list" && args.length === 1) return { kind: "devices-list" };
      if (args[0] === "revoke" && args.length === 2 && /^[a-fA-F0-9]{64}$/.test(args[1]!)) {
        return { kind: "devices-revoke", endpoint: args[1]!.toLowerCase() };
      }
      throw new Error("Usage: /kompi devices list | /kompi devices revoke <full-endpoint-id>");
    default:
      throw new Error("Usage: /kompi pair [--token] [name] | status | devices list | devices revoke <full-endpoint-id>");
  }
}

export default function (pi: ExtensionAPI): void {
  if (process.env.KOMPI_SDK_WORKER === "1") return;

  const bridge = new NativeBridge();

  pi.registerTool(defineTool({
    name: "android_send_notification",
    label: "Send Android notification",
    description: "Send a notification to one explicitly selected paired Android device. Use the exact 64-character endpoint from `kompi devices list`; never choose or broadcast implicitly. If the phone has a UnifiedPush provider/registration, the daemon may queue durable background delivery; without it, delivery remains connected-only. queued means durable daemon acceptance, push-accepted means provider acceptance, and neither confirms Android posted it. unavailable means no background provider or live route is available. Unknown may already have been accepted or posted; do not retry automatically. Posted is not a read receipt. Optional rich supports big-text/inbox/big-picture (canonical base64url PNG/JPEG, 12 KiB compressed, connected-only), up to three user-navigation buttons (HTTPS/open-Kompi), progress, tag updates, group, subtext, silent and timeout. A new tool call/ID updates a stable tag owned by this computer; never reuse an ID with changed content. Rich delivery requires a capable phone and may exceed the unchanged encrypted push budget. No arbitrary intents, remote input, tool approval or privileged APIs.",
    parameters: Type.Unsafe<AgentNotification & { endpoint: string }>({
      ...AgentNotificationJsonSchema,
      properties: { ...AgentNotificationJsonSchema.properties, endpoint: Type.String({ pattern: "^[a-f0-9]{64}$", description: "Exact paired endpoint from kompi devices list." }) },
      required: [...AgentNotificationJsonSchema.required, "endpoint"],
    }),
    async execute(_id, params, signal) {
      if (signal?.aborted) throw new Error("Notification request was cancelled before dispatch; no phone request was sent.");
      const { endpoint, ...notification } = params;
      const result = await bridge.sendRichNotification(endpoint, notification, notificationRequestId(endpoint, _id), signal);
      const text = [
        `Notification to ${result.endpoint}: ${result.status}.`,
        describeDeviceNotificationStatus(result.status),
        result.code ? `Code: ${result.code}` : "",
      ].filter(Boolean).join("\n");
      return { content: [{ type: "text" as const, text }], details: result };
    },
  }));

  pi.registerCommand("kompi", {
    description: "Manage native Kompi pairing and devices: pair, status, devices list, devices revoke",
    handler: async (raw, ctx: ExtensionCommandContext) => {
      let command: Command;
      try { command = parseCommand(raw); }
      catch (error) { ctx.ui.notify(String(error instanceof Error ? error.message : error), "warning"); return; }

      try {
        switch (command.kind) {
          case "pair": {
            const pairing = await bridge.createPairing(command.name, command.token);
            if (command.token) {
              if (!pairing.token) throw new Error("Native Kompi CLI did not provide the requested token");
              await showPairingToken(ctx, { token: pairing.token, name: pairing.name, expiresAt: pairing.expiresAt });
            } else {
              await showPairing(ctx, pairing);
            }
            return;
          }
          case "status":
            ctx.ui.notify(await bridge.status(), "info");
            return;
          case "devices-list": {
            const devices = await bridge.listDevices();
            ctx.ui.notify(devices.length
              ? devices.map((device) => `${device.name}  ${device.endpoint}  paired ${new Date(device.pairedAt).toISOString()}`).join("\n")
              : "No Kompi devices are paired.", "info");
            return;
          }
          case "devices-revoke": {
            const devices = await bridge.listDevices();
            const device = devices.find((entry) => entry.endpoint === command.endpoint);
            if (!device) { ctx.ui.notify("No paired device has that exact endpoint ID.", "warning"); return; }
            const confirmed = await ctx.ui.confirm("Revoke paired Kompi device?", `${device.name}\n${device.endpoint}`);
            if (!confirmed) return;
            await bridge.revokeDevice(device.endpoint);
            ctx.ui.notify(`Revoked ${device.name} (${device.endpoint.slice(0, 12)}…).`, "info");
            return;
          }
        }
      } catch (error) {
        ctx.ui.notify(`Kompi command failed: ${String(error)}`, "error");
      }
    },
  });
}
