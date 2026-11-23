import type { ExtensionCommandContext } from "@earendil-works/pi-coding-agent";
import { Image, getCapabilities, matchesKey, Key, truncateToWidth, visibleWidth, wrapTextWithAnsi } from "@earendil-works/pi-tui";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

export interface PairingDisplay {
  terminalQr: string;
  png: Buffer;
  name: string;
  expiresAt: number;
}

export interface PairingTokenDisplay { token: string; name: string; expiresAt: number; }

/** Never truncate/wrap QR modules: show all of them, or an explicit size fallback. */
export function layoutTerminalQr(qr: string, width: number, height: number):
  | { kind: "fits"; lines: string[]; columns: number; rows: number }
  | { kind: "too-small"; columns: number; rows: number } {
  const lines = qr.replace(/\r/g, "").replace(/\n$/, "").split("\n");
  const columns = Math.max(...lines.map(visibleWidth));
  const rows = lines.length;
  return columns <= width && rows <= height ? { kind: "fits", lines, columns, rows } : { kind: "too-small", columns, rows };
}

export async function showPairingToken(ctx: ExtensionCommandContext, code: PairingTokenDisplay): Promise<void> {
  if (ctx.mode !== "tui") {
    ctx.ui.notify(`Android: choose Paste Pairing Code, then paste this private token (whitespace is ignored):\n${code.token}\nExpires in 5 minutes.`, "info");
    return;
  }
  await ctx.ui.custom<void>((_tui, theme, _keys, done) => ({
    render(width: number): string[] {
      const title = truncateToWidth(theme.fg("accent", `Pair Kompi · ${code.name}`), width);
      const instruction = wrapTextWithAnsi("On Android, choose Paste Pairing Code and paste the token below. Whitespace/newlines are ignored; expires in 5 minutes.", width);
      const tokenWidth = Math.max(1, width);
      const token = Array.from({ length: Math.ceil(code.token.length / tokenWidth) }, (_, index) => code.token.slice(index * tokenWidth, (index + 1) * tokenWidth));
      const footer = truncateToWidth(theme.fg("dim", "Keep private · Esc closes"), width);
      return [title, "", ...instruction, "", ...token, "", footer];
    },
    handleInput(data: string): void {
      if (matchesKey(data, Key.escape) || matchesKey(data, Key.enter) || matchesKey(data, Key.ctrl("c"))) done();
    },
    invalidate(): void {},
  }), { overlay: true, overlayOptions: { width: "100%", maxHeight: "100%", margin: 0, anchor: "center" } });
}

export async function showPairing(ctx: ExtensionCommandContext, code: PairingDisplay): Promise<void> {
  // Keep the QR intact; if the native CLI supplied a private PNG, also offer it for short terminals.
  const directory = code.png.length ? await mkdtemp(join(tmpdir(), "kompi-pairing-")) : undefined;
  const imagePath = directory ? join(directory, "qr.png") : undefined;
  if (imagePath) await writeFile(imagePath, code.png, { mode: 0o600 });
  if (directory) {
    const cleanup = setTimeout(() => { void rm(directory, { recursive: true, force: true }); }, Math.max(1, code.expiresAt - Date.now()));
    cleanup.unref();
  }
  // Remove the old bounded editor widget, including one left from a previous version.
  ctx.ui.setWidget("kompi-pair", undefined);
  if (ctx.mode !== "tui") {
    ctx.ui.notify(`${code.terminalQr}\nPair with ${code.name}.${imagePath ? ` Private QR PNG: ${imagePath}.` : ""}\nExpires in 5 minutes.`, "info");
    return;
  }
  await ctx.ui.custom<void>((tui, theme, _keys, done) => {
    let image: Image | undefined;
    let imageSize = "";
    return {
      render(width: number): string[] {
        const height = tui.terminal.rows;
        const bodyHeight = Math.max(1, height - 5);
        const layout = layoutTerminalQr(code.terminalQr, width, bodyHeight);
        const title = truncateToWidth(theme.fg("accent", `Pair Kompi · ${code.name}`), width);
        const footer = truncateToWidth(theme.fg("dim", "Scan in Kompi · expires in 5 minutes · Esc closes"), width);
        if (layout.kind === "fits") return [title, "", ...layout.lines, "", footer];
        if (code.png.length > 0 && getCapabilities().images && bodyHeight >= 4) {
          const size = `${width}:${bodyHeight}`;
          if (!image || imageSize !== size) {
            imageSize = size;
            image = new Image(code.png.toString("base64"), "image/png", { fallbackColor: text => theme.fg("dim", text) }, { maxWidthCells: width, maxHeightCells: bodyHeight, filename: imagePath ?? "kompi-pairing.png" });
          }
          return [title, "", ...image.render(width), "", footer];
        }
        // Rendering a partial code would look valid but be impossible to scan.
        return [
          title,
          ...wrapTextWithAnsi(`Full QR needs ${layout.columns} columns × ${layout.rows + 5} rows. Enlarge the terminal or reduce its font size.`, width),
          ...(code.png.length > 0
            ? [...wrapTextWithAnsi("Or open this private PNG on the Pi machine (deleted at expiry):", width), ...(imagePath ? wrapTextWithAnsi(imagePath, width) : [])]
            : wrapTextWithAnsi("The native CLI did not provide a PNG fallback; the complete QR is still shown above when terminal space permits.", width)),
          truncateToWidth(theme.fg("dim", "Esc closes · never share this credential"), width),
        ].slice(0, height);
      },
      handleInput(data: string): void {
        if (matchesKey(data, Key.escape) || matchesKey(data, Key.enter) || matchesKey(data, Key.ctrl("c"))) done();
      },
      invalidate(): void { image?.invalidate(); },
    };
  }, { overlay: true, overlayOptions: { width: "100%", maxHeight: "100%", margin: 0, anchor: "center" } });
}
