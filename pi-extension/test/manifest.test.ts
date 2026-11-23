import { it } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

it("declares a loadable Pi package extension entrypoint", async () => {
  const packageUrl = new URL("../package.json", import.meta.url);
  const manifest = JSON.parse(await readFile(packageUrl, "utf8"));
  assert.deepEqual(manifest.pi.extensions, ["./index.ts"]);
  assert.ok(manifest.keywords.includes("pi-package"));
  assert.equal(manifest.peerDependencies["@earendil-works/pi-coding-agent"], "*");
  const entry = await import(new URL(manifest.pi.extensions[0], packageUrl).href);
  assert.equal(typeof entry.default, "function");
  const tools: string[] = [], commands: string[] = [];
  entry.default({
    registerTool: (tool: { name: string }) => { tools.push(tool.name); },
    registerCommand: (name: string) => { commands.push(name); },
  });
  assert.deepEqual(tools, ["android_send_notification"]);
  assert.deepEqual(commands, ["kompi"]);
});
