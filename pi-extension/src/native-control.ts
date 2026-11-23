import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { ControlClient } from "./ipc.js";
import { runJson } from "./native-cli.js";

function stateDirectory(): string {
  const configured = process.env.KOMPI_STATE_DIR;
  return configured ? resolve(configured) : join(homedir(), ".pi", "agent", "kompi-native");
}

export function fallbackControlSocketPath(): string {
  const runtime = process.env.XDG_RUNTIME_DIR;
  return runtime ? join(runtime, "kompi", "control.sock") : join(stateDirectory(), "control.sock");
}

async function resolveControlSocketPath(): Promise<string> {
  const status = await runJson<{ controlSocket?: string }>(["status", "--json"]);
  return status.controlSocket || fallbackControlSocketPath();
}

/** Connect only to an already-running, externally supervised kompi serve process. */
export async function connectNative(): Promise<ControlClient> {
  let socketPath: string;
  try {
    socketPath = await resolveControlSocketPath();
  } catch (error) {
    throw new Error(
      `Could not query the native Kompi service: ${String(error)}. ` +
      "Run the externally supervised foreground process 'kompi serve'; the Pi extension never starts or stops it.",
    );
  }

  try {
    return await ControlClient.connect(socketPath);
  } catch (error) {
    throw new Error(
      `Native Kompi service is unavailable at ${socketPath}: ${String(error)}. ` +
      "Run the externally supervised foreground process 'kompi serve'; the Pi extension never starts or stops it.",
    );
  }
}
