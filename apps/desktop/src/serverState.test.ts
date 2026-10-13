import { describe, expect, it } from "vitest";
import { becameReady, newerStatus, serverStateText } from "./serverState";
import type { ServerStatus } from "./types";

const starting: ServerStatus = { seq: 1, state: "starting" };
const ready: ServerStatus = { seq: 2, state: "ready", version: "0.1.0" };
const failed: ServerStatus = { seq: 2, state: "failed", reason: "exit code 1" };

describe("newerStatus", () => {
  it("takes the first status", () => {
    expect(newerStatus(null, starting)).toBe(starting);
  });

  it("takes a newer status", () => {
    expect(newerStatus(starting, ready)).toBe(ready);
  });

  it("ignores an older snapshot that arrives late", () => {
    expect(newerStatus(ready, starting)).toBe(ready);
  });

  it("keeps the current one on equal seq", () => {
    expect(newerStatus(ready, { ...ready })).toBe(ready);
  });
});

describe("becameReady", () => {
  it("fires on the transition into ready", () => {
    expect(becameReady(starting, ready)).toBe(true);
    expect(becameReady(null, ready)).toBe(true);
  });

  it("does not fire while staying ready", () => {
    expect(becameReady(ready, ready)).toBe(false);
  });

  it("fires again for a new ready after a restart that was missed", () => {
    expect(becameReady(ready, { seq: 5, state: "ready", version: "0.1.0" })).toBe(true);
  });

  it("does not fire for other states", () => {
    expect(becameReady(starting, failed)).toBe(false);
    expect(becameReady(ready, null)).toBe(false);
  });
});

describe("serverStateText", () => {
  it("describes each state", () => {
    expect(serverStateText(null)).toMatch(/Starting/);
    expect(serverStateText(starting)).toMatch(/Starting/);
    expect(serverStateText(ready)).toBe("Running (version 0.1.0)");
    expect(serverStateText({ seq: 3, state: "stopped" })).toBe("Stopped");
    expect(serverStateText(failed)).toMatch(/could not start/);
  });
});
