type Row = Record<string, unknown>;
const isRow = (value: unknown): value is Row => value !== null && typeof value === "object" && !Array.isArray(value);

// Android never renders system messages. Keep their identity/order, not the model's
// instructions and full tool catalog. Never mutate Pi's original event or transcript.
function message(value: unknown): unknown {
  if (!isRow(value) || value.role !== "system") return value;
  return { role: "system", content: [], timestamp: value.timestamp };
}
function entry(value: unknown): unknown {
  if (!isRow(value) || value.type !== "message") return value;
  const projected = message(value.message);
  return projected === value.message ? value : { ...value, message: projected };
}
export function phoneProjection(kind: string, value: unknown): unknown {
  if (!isRow(value)) return value;
  if (kind !== "event") return Array.isArray(value.entries) ? { ...value, entries: value.entries.map(entry) } : value;
  switch (value.type) {
    case "entry_appended": return { ...value, entry: entry(value.entry) };
    case "message_start": case "message_end": case "turn_end": return { ...value, message: message(value.message) };
    case "agent_end": return Array.isArray(value.messages) ? { ...value, messages: value.messages.map(message) } : value;
    default: return value;
  }
}
