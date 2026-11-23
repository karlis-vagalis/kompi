import { expect, test } from "bun:test";
import { PiReadPressure } from "../src/pi-read-pressure.ts";

test("a queued snapshot crossing the low watermark releases the preceding event reader", () => {
  const pressure = new PiReadPressure();
  const transitions: boolean[] = [];
  const eventBytes = 1024 * 1024 + 1, snapshotBytes = 512 * 1024 + 1;
  pressure.enqueue(eventBytes, "pi", paused => transitions.push(paused));
  pressure.enqueue(snapshotBytes, "pi");
  pressure.complete(eventBytes);
  expect(transitions).toEqual([true]);
  pressure.complete(snapshotBytes);
  expect(transitions).toEqual([true, false]);
});
