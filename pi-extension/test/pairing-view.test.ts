import { it } from "node:test";
import assert from "node:assert/strict";
import { visibleWidth } from "@earendil-works/pi-tui";
import type { ExtensionCommandContext } from "@earendil-works/pi-coding-agent";
import { layoutTerminalQr, showPairing } from "../src/pairing/view.js";

it("keeps every QR row and column intact when the terminal fits", () => {
  const qr = "\x1b[47m      \x1b[0m\n\x1b[47m ▄█▄  \x1b[0m\n\x1b[47m      \x1b[0m\n";
  const layout = layoutTerminalQr(qr, 6, 3);
  assert.equal(layout.kind, "fits");
  if (layout.kind === "fits") {
    assert.deepEqual(layout.lines, qr.slice(0, -1).split("\n"));
    assert.equal(layout.columns, 6); assert.equal(layout.rows, 3);
  }
  assert.equal(layoutTerminalQr(qr, 5, 3).kind, "too-small");
  assert.equal(layoutTerminalQr(qr, 6, 2).kind, "too-small");
});

it("uses a full-terminal overlay instead of the clipped editor widget", async () => {
  let cleared = false, options: unknown;
  const art = Array.from({ length: 30 }, () => "█".repeat(60)).join("\n") + "\n";
  const context = { mode: "tui", ui: {
    setWidget: (key: string, value: unknown) => { assert.equal(key, "kompi-pair"); assert.equal(value, undefined); cleared = true; },
    custom: async (factory: Function, configuration: unknown) => {
      options = configuration;
      const screen = factory({ terminal: { rows: 40 } }, { fg: (_color: string, value: string) => value }, {}, () => undefined);
      const lines = screen.render(80);
      assert.equal(lines.filter((line: string) => line === "█".repeat(60)).length, 30);
      assert.ok(lines.length <= 40);
      assert.ok(lines.every((line: string) => visibleWidth(line) <= 80));
    },
  } } as unknown as ExtensionCommandContext;
  await showPairing(context, { terminalQr: art, png: Buffer.from("test"), name: "Phone", expiresAt: Date.now() + 10 });
  assert.ok(cleared);
  assert.deepEqual(options, { overlay: true, overlayOptions: { width: "100%", maxHeight: "100%", margin: 0, anchor: "center" } });
});
