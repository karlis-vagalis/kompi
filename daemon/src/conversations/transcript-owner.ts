import { spawnSync } from "node:child_process";
import { constants, fstatSync, openSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { TRANSCRIPT_LOCK_CONFLICT, transcriptLockPath } from "./transcript-lock.ts";

/** Pi creates its transcript lazily and exclusively on the first write. Hold
 * the sidecar before that write without creating the transcript ourselves. */
export default function installTranscriptOwner(pi: ExtensionAPI): void {
  const launch = process.env.KOMPI_PI_TRANSCRIPT_LAUNCH;
  if (launch !== "fresh" && launch !== "locked") return;
  pi.on("session_before_switch", () => ({ cancel: true }));
  if (launch !== "fresh") return; // fd 4 already owns the inherited sidecar
  pi.on("session_start", (_event, ctx) => {
    const path = ctx.sessionManager.getSessionFile();
    if (!path) return; // transcript-free model catalog worker
    try {
      const fd = openSync(transcriptLockPath(path), constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW | constants.O_NONBLOCK, 0o600);
      const stat = fstatSync(fd);
      if (!stat.isFile() || stat.nlink !== 1) process.exit(TRANSCRIPT_LOCK_CONFLICT);
      const lock = spawnSync("flock", ["-x", "-n", "-E", String(TRANSCRIPT_LOCK_CONFLICT), "3"], {
        stdio: ["ignore", "ignore", "ignore", fd],
      });
      if (lock.status !== 0) process.exit(TRANSCRIPT_LOCK_CONFLICT);
      // The lease follows the Pi process, including after an adapter crash.
      // Closing or unlinking the sidecar while Pi runs would split ownership.
    } catch {
      // Pi extension errors can be nonfatal. Reject before acknowledging a
      // writer whose exclusive ownership could not be established.
      process.exit(TRANSCRIPT_LOCK_CONFLICT);
    }
  });
}
