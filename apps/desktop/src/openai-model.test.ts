import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import type { ModelCall } from "./host";
import { OpenAiModel } from "./openai-model";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function assistantOk(text = "ok"): Response {
  return jsonResponse(200, { choices: [{ message: { content: text } }] });
}

function baseCall(overrides: Partial<ModelCall> = {}): ModelCall {
  return {
    apiRoot: "https://api.example.test/v1",
    modelName: "gpt-4o-mini",
    credential: "sk-test-secret",
    instructions: "Stay on the ice.",
    tools: [],
    messages: [{ role: "person", text: "Hello" }],
    ...overrides,
  };
}

function mockFetch(respond: (url: string, init: RequestInit) => Response | Promise<Response>) {
  const requests: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    requests.push({ url, init });
    return respond(url, init);
  };
  return requests;
}

function requestBody(init: RequestInit): Record<string, unknown> {
  return JSON.parse(String(init.body)) as Record<string, unknown>;
}

test("instructions become the system message and the bearer token is the credential", async () => {
  const requests = mockFetch(() => assistantOk());
  const result = await new OpenAiModel().complete(baseCall());
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, "https://api.example.test/v1/chat/completions");
  assert.equal(requests[0].init.method, "POST");
  const headers = new Headers(requests[0].init.headers);
  assert.equal(headers.get("Authorization"), "Bearer sk-test-secret");
  const body = requestBody(requests[0].init);
  assert.equal(JSON.stringify(body).includes("sk-test-secret"), false);
  assert.deepEqual((body.messages as unknown[])[0], { role: "system", content: "Stay on the ice." });
  assert.deepEqual((body.messages as unknown[])[1], { role: "user", content: "Hello" });
  assert.equal(result.kind, "assistant");
});

test("empty tools omits the tools field and a tool definition is sent as a function tool", async () => {
  const requests = mockFetch(() => assistantOk());
  const model = new OpenAiModel();
  await model.complete(baseCall({ tools: [] }));
  assert.equal("tools" in requestBody(requests[0].init), false);

  const schema = { type: "object", properties: { city: { type: "string" } } };
  await model.complete(
    baseCall({
      tools: [{ name: "weather", description: "Look up weather", inputSchema: schema }],
    }),
  );
  assert.deepEqual(requestBody(requests[1].init).tools, [
    {
      type: "function",
      function: { name: "weather", description: "Look up weather", parameters: schema },
    },
  ]);
});

test("a tool transcript entry is replayed as assistant tool_calls plus a tool message", async () => {
  const requests = mockFetch(() => assistantOk());
  await new OpenAiModel().complete(
    baseCall({
      messages: [
        { role: "person", text: "Look this up" },
        {
          role: "tool",
          callId: "call_1",
          name: "search",
          arguments: null,
          status: "ran",
          text: "found ice",
        },
        {
          role: "tool",
          callId: "call_2",
          name: "read",
          arguments: { path: "notes.md" },
          status: "ran",
          text: "notes",
        },
        { role: "assistant", text: "Here it is." },
      ],
    }),
  );
  const messages = requestBody(requests[0].init).messages as unknown[];
  assert.deepEqual(messages, [
    { role: "system", content: "Stay on the ice." },
    { role: "user", content: "Look this up" },
    {
      role: "assistant",
      content: null,
      tool_calls: [
        { id: "call_1", type: "function", function: { name: "search", arguments: "{}" } },
        {
          id: "call_2",
          type: "function",
          function: { name: "read", arguments: JSON.stringify({ path: "notes.md" }) },
        },
      ],
    },
    { role: "tool", tool_call_id: "call_1", content: "found ice" },
    { role: "tool", tool_call_id: "call_2", content: "notes" },
    { role: "assistant", content: "Here it is." },
  ]);
});

test("a response with tool_calls becomes kind tool_calls with parsed object arguments", async () => {
  mockFetch(() =>
    jsonResponse(200, {
      choices: [
        {
          message: {
            content: "Calling weather",
            tool_calls: [
              {
                id: "call_weather",
                type: "function",
                function: { name: "weather", arguments: "{\"city\":\"Oslo\"}" },
              },
            ],
          },
        },
      ],
    }),
  );
  const result = await new OpenAiModel().complete(baseCall());
  assert.deepEqual(result, {
    kind: "tool_calls",
    text: "Calling weather",
    calls: [{ id: "call_weather", name: "weather", arguments: { city: "Oslo" } }],
  });
});

test("malformed arguments JSON becomes arguments null and the call is still returned", async () => {
  mockFetch(() =>
    jsonResponse(200, {
      choices: [
        {
          message: {
            content: 12,
            tool_calls: [
              {
                id: "call_bad",
                type: "function",
                function: { name: "search", arguments: "{" },
              },
            ],
          },
        },
      ],
    }),
  );
  const result = await new OpenAiModel().complete(baseCall());
  assert.deepEqual(result, {
    kind: "tool_calls",
    text: "",
    calls: [{ id: "call_bad", name: "search", arguments: null }],
  });
});

test("401 is rejected and the body is not in the result", async () => {
  mockFetch(() => jsonResponse(401, { error: "invalid_api_key_body" }));
  const result = await new OpenAiModel().complete(baseCall());
  assert.deepEqual(result, { kind: "rejected" });
  assert.equal(JSON.stringify(result).includes("invalid_api_key_body"), false);
});

test("a thrown fetch is unreachable", async () => {
  mockFetch(() => {
    throw new Error("network down");
  });
  const result = await new OpenAiModel().complete(baseCall());
  assert.deepEqual(result, { kind: "unreachable" });
});
