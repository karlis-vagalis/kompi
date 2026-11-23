import { it } from "node:test";
import assert from "node:assert/strict";
import { attributeAssistantText, RunCorrelation } from "../src/correlation.js";

const submitted = "[Kompi request:abc:voice-1]\nTranscribe this";

it("attributes a Pi run only while its user messages remain the exact voice input", () => {
  const correlated = new RunCorrelation(submitted);
  correlated.observeBeforeAgentStart(submitted);
  correlated.observeUserMessage(submitted);
  assert.equal(correlated.canAttributeResult, true);

  const mixed = new RunCorrelation(submitted);
  mixed.observeBeforeAgentStart(submitted);
  mixed.observeUserMessage(submitted);
  mixed.observeUserMessage("A terminal follow-up joined the active run");
  assert.equal(mixed.canAttributeResult, false);
  assert.equal(mixed.contaminated, true);
  assert.deepEqual(attributeAssistantText(mixed, "Private answer to mixed input"), {
    kind: "withheld",
    reason: "Terminal input joined this Pi run; result withheld to avoid misattribution. See the Pi session.",
  });

  const repeated = new RunCorrelation(submitted);
  repeated.observeBeforeAgentStart(submitted);
  repeated.observeUserMessage(submitted);
  repeated.observeUserMessage(submitted);
  assert.equal(repeated.canAttributeResult, false);
  assert.deepEqual(attributeAssistantText(repeated, "Do not leak"), {
    kind: "withheld",
    reason: "Terminal input joined this Pi run; result withheld to avoid misattribution. See the Pi session.",
  });
});
