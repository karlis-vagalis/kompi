import { homedir } from "node:os";
import { z } from "zod";
import { VERSION, parseWorkerEvent, type WorkerRequest, type WorkerEvent } from "../../../pi-extension/src/protocol/generated.ts";
import { PiRpcClient, PiRpcCommandError } from "./pi-rpc-client.ts";

export type ChatConfiguration = Extract<WorkerRequest, { type: "prompt" }>["payload"]["configuration"];
export type ModelCatalog = Extract<WorkerEvent, { type: "models-result" }>["payload"]["catalog"];
const availableModels = z.object({ models: z.array(z.object({ provider: z.string(), id: z.string(), name: z.string() })).max(128) });
const thinkingLevels = z.object({ levels: z.array(z.string()).min(1).max(7) });

/** Discover through ordinary CLI configuration without creating a transcript or a phone session. */
export async function discoverModels(): Promise<ModelCatalog> {
  const client = new PiRpcClient(homedir(), ["--no-session"]);
  client.onDialog = (dialog) => { void client.respond({ type: "extension_ui_response", id: dialog.id, cancelled: true }).catch(() => undefined); };
  try {
    const state = z.object({ model: z.object({ provider: z.string(), id: z.string() }).optional(), thinkingLevel: z.string() })
      .parse(await client.request({ type: "get_state" }, 60_000));
    const { models } = availableModels.parse(await client.request({ type: "get_available_models" }));
    const catalog = [];
    for (const model of models) {
      await client.request({ type: "set_model", provider: model.provider, modelId: model.id });
      const { levels } = thinkingLevels.parse(await client.request({ type: "get_available_thinking_levels" }));
      catalog.push({ provider: model.provider, modelId: model.id, name: model.name, efforts: levels });
    }
    const current = catalog.find((model) => model.provider === state.model?.provider && model.modelId === state.model.id);
    const parsed = parseWorkerEvent({ v: VERSION, id: "models", message: { type: "models-result", payload: { catalog: {
      models: catalog,
      defaultConfiguration: current ? { provider: current.provider, modelId: current.modelId, effort: state.thinkingLevel } : null,
    } } } });
    if (parsed.message.type !== "models-result") throw new Error("Unexpected model catalog response");
    return parsed.message.payload.catalog;
  } finally { await client.stop(); }
}

export class ModelSelectionError extends Error {}

/** set_model itself resolves the exact ID against Pi's available snapshot. Never
 * launch discovery, choose a fallback, or assume a previous session's state. */
export async function applyModel(client: Pick<PiRpcClient, "request">, configuration: ChatConfiguration, previous?: ChatConfiguration): Promise<void> {
  const readSelection = async () => z.object({ model: z.object({ provider: z.string(), id: z.string() }).optional(), thinkingLevel: z.string() })
    .parse(await client.request({ type: "get_state" }));
  const matches = (state: Awaited<ReturnType<typeof readSelection>>) => state.model?.provider === configuration.provider &&
    state.model.id === configuration.modelId && state.thinkingLevel === configuration.effort;
  if (previous?.provider === configuration.provider && previous.modelId === configuration.modelId && previous.effort === configuration.effort &&
      matches(await readSelection())) return;
  try {
    await client.request({ type: "set_model", provider: configuration.provider, modelId: configuration.modelId });
    await client.request({ type: "set_thinking_level", level: configuration.effort });
  } catch (error) {
    if (error instanceof PiRpcCommandError) throw new ModelSelectionError();
    throw error;
  }
  if (!matches(await readSelection())) throw new ModelSelectionError();
}
