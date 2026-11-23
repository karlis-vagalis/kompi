import { it } from "node:test";
import assert from "node:assert/strict";
import type { ExtensionCommandContext } from "@earendil-works/pi-coding-agent";
import { showPairingToken } from "../src/pairing/view.js";

it("shows the entire pairing token in the full-screen viewer, wrapping without truncation", async () => {
  const token = `kompi-pair-v1:${"aB9_-".repeat(80)}`;
  let rendered: string[] = [];
  const context = { mode: "tui", ui: {
    custom: async (factory: Function) => {
      const screen = factory({ terminal: { rows: 40 } }, { fg: (_color: string, value: string) => value }, {}, () => undefined);
      rendered = screen.render(32);
    },
  } } as unknown as ExtensionCommandContext;

  await showPairingToken(context, { token, name: "Phone", expiresAt: Date.now() + 300_000 });
  assert.ok(rendered.join("").replace(/\s/g, "").includes(token));
});
