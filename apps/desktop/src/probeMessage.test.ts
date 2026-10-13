import { describe, expect, it } from "vitest";
import { probeMessage } from "./probeMessage";
import type { PolarionProbe } from "./types";

describe("probeMessage", () => {
  it("shows user name and id when ok", () => {
    const msg = probeMessage({ result: "ok", userId: "demo.user", userName: "Demo User" });
    expect(msg.tone).toBe("success");
    expect(msg.title).toContain("Demo User (demo.user)");
  });

  it("falls back to user id when name is missing", () => {
    expect(probeMessage({ result: "ok", userId: "demo.user" }).title).toContain("demo.user");
  });

  it("handles ok without any identity", () => {
    expect(probeMessage({ result: "ok" }).title).toBe("Connected to Polarion.");
  });

  it("is neutral when not configured", () => {
    expect(probeMessage({ result: "not_configured" }).tone).toBe("neutral");
  });

  it("asks for a new token when rejected", () => {
    const msg = probeMessage({ result: "rejected_token" });
    expect(msg.tone).toBe("error");
    expect(msg.title).toMatch(/rejected the token/);
    expect(msg.detail).toMatch(/new personal access token/);
  });

  it("names the admin setting when REST is disabled", () => {
    const msg = probeMessage({ result: "rest_disabled" });
    expect(msg.title).toMatch(/REST API is disabled/);
    expect(msg.detail).toContain("com.siemens.polarion.rest.enabled=true");
  });

  it("suggests URL and VPN on network error", () => {
    const msg = probeMessage({ result: "network_error" });
    expect(msg.title).toMatch(/Could not reach Polarion/);
    expect(msg.detail).toMatch(/URL/);
    expect(msg.detail).toMatch(/VPN/);
  });

  it("gives distinct copy for every result", () => {
    const results: PolarionProbe["result"][] = ["ok", "not_configured", "rejected_token", "rest_disabled", "network_error"];
    const titles = new Set(results.map((result) => probeMessage({ result }).title));
    expect(titles.size).toBe(results.length);
  });

  it("does not crash on an unknown result", () => {
    const msg = probeMessage({ result: "mystery" as PolarionProbe["result"], message: "detail" });
    expect(msg.tone).toBe("error");
    expect(msg.detail).toBe("detail");
  });
});
