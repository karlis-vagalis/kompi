import type { AgentMessage } from "@earendil-works/pi-agent-core";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/** Preserve every block and its data; only move user images ahead of user text. */
function imagesFirst(message: AgentMessage): AgentMessage {
  if (message.role !== "user" || !Array.isArray(message.content)) return message;
  const images = message.content.filter((block) => block.type === "image");
  if (images.length === 0) return message;
  const rest = message.content.filter((block) => block.type !== "image");
  return { ...message, content: [...images, ...rest] };
}

/** Phone-runtime content ordering only; no tools, model, settings, or credentials. */
export default function attachmentOrder(pi: ExtensionAPI): void {
  // Keep newly persisted Pi messages in the same order as the model input.
  pi.on("message_end", (event) => {
    const message = imagesFirst(event.message);
    if (message !== event.message) return { message };
  });
  // Also cover resumed history and Pi's queued-input normalization, which creates
  // text-first content before this supported, request-local context boundary.
  pi.on("context", (event) => ({ messages: event.messages.map(imagesFirst) }));
}
