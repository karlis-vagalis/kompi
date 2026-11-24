import { expect, test } from "bun:test";
import { applyModel, ModelSelectionError, type ChatConfiguration } from "../src/conversations/model-selection.ts";
import { PiRpcCommandError, type PiRpcClient } from "../src/conversations/pi-rpc-client.ts";

const choice: ChatConfiguration = { provider: "fixture", modelId: "first", effort: "high" };

test("cold, idle, drift and manual change apply only the exact selection without catalog discovery", async () => {
  let model = "default", effort = "off";
  const calls: string[] = [];
  const client: Pick<PiRpcClient, "request"> = { async request(command) {
    calls.push(command.type);
    switch (command.type) {
      case "set_model":
        if (command.modelId === "removed") throw new PiRpcCommandError();
        model = command.modelId; return {};
      case "set_thinking_level": effort = command.level; return {};
      case "get_state": return { model: { provider: "fixture", id: model }, thinkingLevel: effort };
      default: throw new Error("Unexpected RPC");
    }
  } };
  await applyModel(client, choice);
  expect(calls.splice(0)).toEqual(["set_model", "set_thinking_level", "get_state"]);
  await applyModel(client, choice, choice);
  expect(calls.splice(0)).toEqual(["get_state"]);
  model = "extension-change";
  await applyModel(client, choice, choice);
  expect(model).toBe("first");
  await applyModel(client, { ...choice, modelId: "second" }, choice);
  expect(model).toBe("second");
  await expect(applyModel(client, { ...choice, modelId: "removed" }, choice)).rejects.toBeInstanceOf(ModelSelectionError);
  expect(model).toBe("second");
});
