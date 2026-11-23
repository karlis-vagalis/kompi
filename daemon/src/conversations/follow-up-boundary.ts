import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

// Hold Pi's native continuation checkpoint while Kompi transfers its FIFO batch.
// The per-child title is handled by the adapter, never forwarded to the phone.
export default function followUpBoundary(pi: ExtensionAPI): void {
  const title = process.env.KOMPI_FOLLOW_UP_BOUNDARY;
  if (!title) return;
  pi.on("agent_before_settle", async (event, ctx) => {
    if (event.outcome !== "completed" || event.context.pendingMessages.length) return;
    const reply = await ctx.ui.input(title, undefined, { timeout: 60_000 });
    // Losing the adapter mid-transfer must not release a partial native queue.
    if (reply !== "continue" && reply !== "stop") { ctx.abort(); ctx.shutdown(); }
  });
}
