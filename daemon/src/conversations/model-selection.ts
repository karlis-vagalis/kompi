import { homedir } from "node:os";
import { z } from "zod";
import { VERSION, parseWorkerEvent, type WorkerRequest, type WorkerEvent } from "../../../pi-extension/src/protocol/generated.ts";
import { PiRpcClient } from "./pi-rpc-client.ts";

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

/** Apply only to this idle RPC session; Pi's RPC setters do not persist global defaults. */
export async function applyModel(client: PiRpcClient, configuration: ChatConfiguration): Promise<void> {
  const { models } = availableModels.parse(await client.request({ type: "get_available_models" }));
  if (!models.some((model) => model.provider === configuration.provider && model.id === configuration.modelId)) {
    throw new Error("The selected model is no longer available");
  }
  await client.request({ type: "set_model", provider: configuration.provider, modelId: configuration.modelId });
  const { levels } = thinkingLevels.parse(await client.request({ type: "get_available_thinking_levels" }));
  if (!levels.includes(configuration.effort)) throw new Error("The selected model does not support that effort");
  await client.request({ type: "set_thinking_level", level: configuration.effort });
  const state = z.object({ model: z.object({ provider: z.string(), id: z.string() }), thinkingLevel: z.string() })
    .parse(await client.request({ type: "get_state" }));
  if (state.model.provider !== configuration.provider || state.model.id !== configuration.modelId || state.thinkingLevel !== configuration.effort) {
    throw new Error("Pi did not apply the requested model configuration");
  }
}
