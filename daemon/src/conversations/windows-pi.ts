import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { basename, dirname, extname, isAbsolute, join, relative, resolve } from "node:path";
import { TRANSCRIPT_LOCK_CONFLICT } from "./transcript-lock.ts";

// Exclude relative, current-drive and implicit current-directory search entries.
const drivePath = (p: string): boolean => /^[a-z]:[\\/]/i.test(p);
const javascript = (p: string): boolean => /\.(m?js|cjs)$/i.test(p);

// Never execute npm's batch shim. Resolve the installed package's JS entry,
// then pass each argument directly to Node (no cmd.exe expansion).
export function windowsPi(): { node: string; cli: string } {
  const dirs = (process.env.PATH ?? "").split(";").map(p => p.replace(/^"(.*)"$/, "$1")).filter(drivePath);
  const node = /[\\/]node\.exe$/i.test(process.execPath) ? process.execPath : dirs.map(p => join(p, "node.exe")).find(existsSync);
  if (!node) throw new Error("Node executable missing");
  const override = process.env.KOMPI_PI_CLI;
  if (override && drivePath(override) && javascript(override)) return { node, cli: override };
  const shim = override ?? dirs.map(p => join(p, "pi.cmd")).find(existsSync);
  if (!shim || !drivePath(shim) || basename(shim).toLowerCase() !== "pi.cmd" || !existsSync(shim)) {
    throw new Error("Expected an installed Pi npm shim or absolute JavaScript entry point");
  }
  const binDir = dirname(shim);
  const base = basename(binDir).toLowerCase() === ".bin" ? dirname(binDir) : join(binDir, "node_modules");
  for (const pkg of ["@earendil-works/pi-coding-agent", "@mariozechner/pi-coding-agent"]) {
    const root = join(base, pkg), manifest = join(root, "package.json");
    if (!existsSync(manifest)) continue;
    const { bin } = JSON.parse(readFileSync(manifest, "utf8"));
    if (typeof bin?.pi !== "string" || !javascript(bin.pi)) continue;
    const cli = resolve(root, bin.pi), path = relative(root, cli);
    if (!isAbsolute(path) && !path.startsWith("..") && existsSync(cli)) return { node, cli };
  }
  throw new Error("Cannot locate installed Pi JavaScript entry point");
}

export function acquireWindowsLease(path: string, mode: "fresh" | "resume", id?: string): unknown {
  const binary = process.env.KOMPI_NATIVE_BINARY;
  if (!binary || !isAbsolute(binary) || extname(binary).toLowerCase() !== ".exe") throw new Error("Missing native transcript helper");
  const run = spawnSync(binary, ["__pi-lease", String(process.pid), path, mode, ...(id ? [id] : [])], {
    encoding: "utf8", windowsHide: true, timeout: 15_000,
  });
  if (run.status === TRANSCRIPT_LOCK_CONFLICT) process.exit(TRANSCRIPT_LOCK_CONFLICT);
  if (run.status !== 0) throw new Error("Cannot pin Pi transcript");
  return JSON.parse(run.stdout);
}
