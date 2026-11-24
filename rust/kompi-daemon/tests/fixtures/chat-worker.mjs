// Deterministic remote execution boundary for the real daemon E2E tests.
// No model, workspace commands, credentials or Pi settings are accessed.
import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { createConnection } from "node:net";
import { createInterface } from "node:readline";

let control;
const active = new Map();
let conversation;
const entries = [];
const emit = (id, type, payload) => process.stdout.write(JSON.stringify({ v: 4, id, message: { type, payload } }) + "\n");
const report = (event) => control.write(JSON.stringify(event) + "\n");
const stats = { assistantMessages: 1, textChunks: 0, totalMs: 1 };

function connectControl() {
  if (control) return;
  control = createConnection(process.env.KOMPI_TEST_CONTROL_SOCKET);
  createInterface({ input: control }).on("line", (line) => {
  const command = JSON.parse(line);
  if (command.action === "barrier") { report({ type: "barrier" }); return; }
  if (command.action === "crash") process.exit(17);
  const request = active.get(command.requestId);
  if (!request) throw new Error("Test released a request that never started");
  active.delete(command.requestId);
  if (command.action === "fail") {
    emit(request.id, "error", { requestId: request.payload.requestId, conversationId: conversation, code: "prompt-failed", message: "Deterministic test failure" });
  } else {
    entries.push({ id: "reply-" + request.payload.requestId, parentId: null, type: "message", message: {role:"assistant", content:[{type:"text", text:"Fixture complete."}]} });
    emit(request.id, "final", { requestId: request.payload.requestId, conversationId: conversation, stats });
  }
  });
}

createInterface({ input: process.stdin }).on("line", (line) => {
  const { id, message: { type, payload } } = JSON.parse(line);
  appendFileSync(join(process.env.HOME, "worker-stages"), type + "\n");
  switch (type) {
    case "models-get":
      setTimeout(() => emit(id, "models-result", { catalog: { models: [{ provider: "fixture", modelId: "controlled", name: "Controlled fixture", efforts: ["high"] }], defaultConfiguration: { provider: "fixture", modelId: "controlled", effort: "high" } } }), 100);
      break;
    case "create":
      connectControl();
      conversation = payload.conversationId;
      emit(id, "created", { conversationId: conversation, sessionId: "fixture-session", sessionFile: `${process.env.HOME}/fixture-session.jsonl`, sessionName: payload.sessionName });
      break;
    case "resume":
      connectControl();
      conversation = payload.conversationId;
      emit(id, "ready", { conversationId: conversation, sessionId: "fixture-session", sessionFile: payload.sessionFile, sessionName: "Behavior fixture" });
      break;
    case "prompt":
      active.set(payload.requestId, { id, payload });
      report({ type: "started", requestId: payload.requestId, conversationId: conversation, text: payload.text });
      break;
    case "abort": {
      report({ type: "aborted", requestId: payload.requestId });
      emit(id, "aborted", { requestId: payload.requestId, conversationId: payload.conversationId });
      const request = active.get(payload.requestId);
      if (request) {
        active.delete(payload.requestId);
        emit(request.id, "error", { requestId: payload.requestId, conversationId: conversation, code: "prompt-aborted", message: "Interrupted by test" });
      }
      break;
    }
    case "dispose":
      emit(id, "disposed", { conversationId: payload.conversationId });
      break;
    case "snapshot":
      emit(id, "pi-frame", {frame:{conversationId:conversation, sessionId:"fixture-session", epoch:"fixture-epoch",
        sequence:entries.length, kind:"snapshot", part:0, last:true, requestId:payload.requestId,
        json:JSON.stringify({entries, leafId:entries.at(-1)?.id ?? null})}});
      emit(id, "snapshot-done", { conversationId: payload.conversationId });
      break;
    default:
      throw new Error(`Unsupported test worker request: ${type}`);
  }
}).on("close", () => process.exit(0));
