import { z } from "zod";

// Worker-side semantic identifiers. The generated Rust worker envelope is
// parsed at the process boundary; brands prevent mixing SDK conversation IDs
// with project directories after that parse.
type Brand<T, Name extends string> = T & { readonly __brand: Name };
export type ConversationId = Brand<string, "ConversationId">;

export function conversationId(raw: string): ConversationId {
  return z.string().uuid().parse(raw) as ConversationId;
}
