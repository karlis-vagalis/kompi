import { expect, test } from "bun:test";
import { randomBytes } from "node:crypto";
import { open, seal } from "../src/conversations/tool-frame";

test("tool bridge frames require the worker key, connection challenge and direction", () => {
  const key = randomBytes(32);
  const sealed = seal(key, "request:first", "phone request");
  expect(open(key, "request:first", sealed)).toBe("phone request");
  expect(() => open(key, "request:second", sealed)).toThrow();
  expect(() => open(key, "reply:first", sealed)).toThrow();
  expect(() => open(randomBytes(32), "request:first", sealed)).toThrow();
  const altered = Buffer.from(sealed, "base64"); altered[altered.length - 1] ^= 1;
  expect(() => open(key, "request:first", altered.toString("base64"))).toThrow();
});
