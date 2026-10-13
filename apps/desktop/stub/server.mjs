// Dev stub for the Arctic local API (server/local-app/api/openapi.yaml). No dependencies.
import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";

const TOKEN = process.env.ARCTIC_SERVE_TOKEN || "dev-token";
const PORT = Number(process.env.PORT || 47100);
const HOST = "127.0.0.1";
const VERSION = "0.1.0-stub";

const state = {
  polarion: null, // { url, token }
  probe: { result: "not_configured" },
  model: null, // { baseUrl, model, apiKey }
};

function send(res, status, body) {
  if (body === undefined) {
    res.writeHead(status);
    res.end();
    return;
  }
  const json = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(json) });
  res.end(json);
}

function error(res, status, kind, message) {
  send(res, status, { kind, message });
}

function authorized(req) {
  const header = req.headers.authorization || "";
  const match = /^Bearer (.+)$/.exec(header);
  if (!match) return false;
  const given = Buffer.from(match[1]);
  const expected = Buffer.from(TOKEN);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString("utf8");
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function nonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function normalizePolarionUrl(raw) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  return `${url.origin}/polarion/rest/v1`;
}

function probeFor(pat) {
  switch (pat) {
    case "bad":
      return { result: "rejected_token", message: "Polarion returned 401 for the personal access token." };
    case "disabled":
      return { result: "rest_disabled", message: "Polarion REST API returned 404/503 (REST disabled)." };
    case "offline":
      return { result: "network_error", message: "Connection to Polarion timed out." };
    default:
      return { result: "ok", userId: "demo.user", userName: "Demo User" };
  }
}

function setupStatus() {
  const status = {
    polarionConfigured: state.polarion !== null,
    polarion: state.probe,
    modelConfigured: state.model !== null,
  };
  if (state.polarion) status.polarionUrl = state.polarion.url;
  if (state.model) {
    status.modelBaseUrl = state.model.baseUrl;
    status.model = state.model.model;
  }
  return status;
}

async function handle(req, res) {
  const path = new URL(req.url, `http://${HOST}`).pathname;
  const route = `${req.method} ${path}`;
  const known = ["GET /health", "GET /setup/status", "PUT /setup/polarion", "PUT /setup/model"];

  if (!authorized(req)) return error(res, 401, "unauthorized", "Missing or wrong bearer token.");
  if (!known.includes(route)) return error(res, 404, "not_found", `No route for ${req.method} ${path}`);

  switch (route) {
    case "GET /health":
      return send(res, 200, { status: "ready", version: VERSION });

    case "GET /setup/status":
      return send(res, 200, setupStatus());

    case "PUT /setup/polarion": {
      const body = await readJson(req);
      if (!body || !nonEmptyString(body.url) || !nonEmptyString(body.token)) {
        return error(res, 400, "bad_request", "url and token are required strings.");
      }
      const url = normalizePolarionUrl(body.url.trim());
      if (!url) return error(res, 400, "bad_request", "url must be an http or https URL.");
      state.polarion = { url, token: body.token };
      state.probe = probeFor(body.token);
      console.log(`[stub] polarion set to ${url}: ${state.probe.result}`);
      return send(res, 200, state.probe);
    }

    case "PUT /setup/model": {
      const body = await readJson(req);
      if (!body || !nonEmptyString(body.baseUrl) || !nonEmptyString(body.model) || !nonEmptyString(body.apiKey)) {
        return error(res, 400, "bad_request", "baseUrl, model and apiKey are required strings.");
      }
      state.model = { baseUrl: body.baseUrl.trim(), model: body.model.trim(), apiKey: body.apiKey };
      console.log(`[stub] model set to ${state.model.model} at ${state.model.baseUrl}`);
      return send(res, 204);
    }
  }
}

const server = createServer((req, res) => {
  handle(req, res).catch(() => error(res, 500, "internal", "Stub server error."));
});

server.listen(PORT, HOST, () => {
  console.log(`[stub] Arctic API stub listening on http://${HOST}:${PORT}`);
});
