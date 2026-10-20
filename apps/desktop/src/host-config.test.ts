import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { HostConfigFile } from "./host-config";

test("a missing config file reads as empty settings", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-config-"));
  const config = new HostConfigFile(dir);
  assert.deepEqual(config.read(), { apiRoot: "", model: "", apiKey: "" });
});

test("config.json round-trips the api root, model, and api key", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-config-"));
  const config = new HostConfigFile(dir);
  config.write({
    apiRoot: "https://api.openai.com/v1",
    model: "gpt-4o-mini",
    apiKey: "sk-test-secret",
  });
  assert.deepEqual(config.read(), {
    apiRoot: "https://api.openai.com/v1",
    model: "gpt-4o-mini",
    apiKey: "sk-test-secret",
  });
});

test("an empty config file reads as empty settings", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-config-"));
  fs.writeFileSync(path.join(dir, "config.json"), "");
  const config = new HostConfigFile(dir);
  assert.deepEqual(config.read(), { apiRoot: "", model: "", apiKey: "" });
});

test("writing an empty api key keeps the saved key", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-config-"));
  const config = new HostConfigFile(dir);
  config.write({
    apiRoot: "https://api.openai.com/v1",
    model: "gpt-4o-mini",
    apiKey: "sk-test-secret",
  });
  config.write({
    apiRoot: "https://example.test/v1",
    model: "other-model",
    apiKey: "",
  });
  assert.deepEqual(config.read(), {
    apiRoot: "https://example.test/v1",
    model: "other-model",
    apiKey: "sk-test-secret",
  });
});
