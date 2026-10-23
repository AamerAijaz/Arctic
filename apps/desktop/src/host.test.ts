import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { FileConversationStore } from "./conversation-store";
import { Host, type McpServer, type ModelCall, type ModelResult } from "./host";

class MemorySecretStore {
  private credential = "";

  read(): string {
    return this.credential;
  }

  replace(credential: string): { ok: true } {
    this.credential = credential;
    return { ok: true };
  }
}

const quiet: McpServer = {
  async tools() {
    return { tools: [], lines: [] };
  },
  async call() {
    throw new Error("call");
  },
};

test("a person message is followed by the scripted reply", async () => {
  const host = new Host(
    {
      complete: async () => ({ kind: "assistant", text: "Polarion is an ALM tool." }),
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("What is Polarion?");
  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "assistant", text: "Polarion is an ALM tool." },
  ]);
});

test("a second turn still contains the first", async () => {
  const host = new Host(
    {
      complete: async (call) => {
        const remembered = call.messages.some((message) => message.text === "What is Polarion?");
        if (call.messages.length > 1 && remembered) {
          return { kind: "assistant", text: "Still about Polarion." };
        }
        return { kind: "assistant", text: "Polarion is an ALM tool." };
      },
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("What is Polarion?");
  await host.send("And the second question?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "assistant", text: "Polarion is an ALM tool." },
    { role: "person", text: "And the second question?" },
    { role: "assistant", text: "Still about Polarion." },
  ]);
});

test("the transcript does not contain the credential", async () => {
  const host = new Host(
    {
      complete: async () => ({ kind: "assistant", text: "Polarion is an ALM tool." }),
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("What is Polarion?");

  assert.equal(JSON.stringify(host.transcript()).includes("sk-test-secret"), false);
});

test("a missing credential records a failure and does not call the model", async () => {
  const host = new Host(
    {
      complete: async () => {
        throw new Error("the model was called");
      },
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");

  await host.send("What is Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The model credential is missing." },
  ]);
});

test("a rejected credential records a failure without the credential or a raw body", async () => {
  const host = new Host(
    {
      complete: async () => ({ kind: "rejected", body: "sk-test-secret raw-401-body" }),
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("What is Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The model rejected the credential." },
  ]);
});

test("an unreachable model records a failure without the credential or a raw body", async () => {
  const host = new Host(
    {
      complete: async () => ({ kind: "unreachable", body: "sk-test-secret connection reset" }),
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("What is Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The model could not be reached." },
  ]);
});

test("a blank message leaves the transcript unchanged", async () => {
  const host = new Host(
    {
      complete: async () => {
        throw new Error("the model was called");
      },
    },
    undefined,
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("   ");

  assert.deepEqual(host.transcript(), []);
});

test("a tool call from the model becomes a failure", async () => {
  let usedCall = false;
  const host = new Host(
    {
      complete: async (call) => {
        if (call.messages.some((message) => message.role === "tool")) {
          return { kind: "assistant" as const, text: "I cannot look that up." };
        }
        return {
          kind: "tool_calls" as const,
          text: "",
          calls: [{ id: "c1", name: "whoami", arguments: {} }],
        };
      },
    },
    undefined,
    undefined,
    {
      async tools() {
        return { tools: [], lines: [] };
      },
      async call() {
        usedCall = true;
        throw new Error("call");
      },
    },
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("Who am I in Polarion?");

  assert.equal(
    host.transcript().some(
      (entry) =>
        entry.role === "tool" &&
        entry.name === "whoami" &&
        entry.status === "failed" &&
        entry.text === "That tool is not available." &&
        entry.callId === "c1",
    ),
    true,
  );
  assert.equal(usedCall, false);
});

test("a restarted Host uses the saved LLM Credential", async () => {
  const secrets = new MemorySecretStore();
  const model = {
    complete: async (call: { credential: string }) => {
      if (call.credential === "sk-test-secret") {
        return { kind: "assistant" as const, text: "Polarion is an ALM tool." };
      }
      throw new Error("the model was called with a different credential");
    },
  };
  const first = new Host(model, secrets, undefined, quiet);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");

  const restarted = new Host(model, secrets, undefined, quiet);
  restarted.setApiRoot("https://api.openai.com/v1");
  restarted.setModelName("gpt-4o-mini");
  await restarted.send("What is Polarion?");

  assert.deepEqual(restarted.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "assistant", text: "Polarion is an ALM tool." },
  ]);
  assert.equal(JSON.stringify(restarted.transcript()).includes("sk-test-secret"), false);
});

test("replacing the LLM Credential changes which credential the next call uses", async () => {
  const secrets = new MemorySecretStore();
  const model = {
    complete: async (call: { credential: string }) => {
      if (call.credential === "sk-rotated-secret") {
        return { kind: "assistant" as const, text: "Rotated Polarion reply." };
      }
      if (call.credential === "sk-test-secret") {
        return { kind: "assistant" as const, text: "Polarion is an ALM tool." };
      }
      throw new Error("the model was called with a different credential");
    },
  };
  const first = new Host(model, secrets, undefined, quiet);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");
  first.setCredential("sk-rotated-secret");

  const restarted = new Host(model, secrets, undefined, quiet);
  restarted.setApiRoot("https://api.openai.com/v1");
  restarted.setModelName("gpt-4o-mini");
  await restarted.send("What is Polarion?");

  assert.deepEqual(restarted.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "assistant", text: "Rotated Polarion reply." },
  ]);
  assert.equal(JSON.stringify(restarted.transcript()).includes("sk-test-secret"), false);
  assert.equal(JSON.stringify(restarted.transcript()).includes("sk-rotated-secret"), false);
});

test("an empty credential field keeps the saved LLM Credential", async () => {
  const secrets = new MemorySecretStore();
  const model = {
    complete: async (call: { credential: string }) => {
      if (call.credential === "sk-test-secret") {
        return { kind: "assistant" as const, text: "Polarion is an ALM tool." };
      }
      throw new Error("the model was called with a different credential");
    },
  };
  const first = new Host(model, secrets, undefined, quiet);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");

  const restarted = new Host(model, secrets, undefined, quiet);
  restarted.setApiRoot("https://api.openai.com/v1");
  restarted.setModelName("gpt-4o-mini");
  restarted.setCredential("");
  await restarted.send("What is Polarion?");

  assert.deepEqual(restarted.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "assistant", text: "Polarion is an ALM tool." },
  ]);
  assert.equal(JSON.stringify(restarted.transcript()).includes("sk-test-secret"), false);
});

test("an empty store still fails the turn without calling the model", async () => {
  const host = new Host(
    {
      complete: async () => {
        throw new Error("the model was called");
      },
    },
    new MemorySecretStore(),
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");

  await host.send("What is Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The model credential is missing." },
  ]);
});

test("a Host that cannot save the LLM Credential records a failure and does not call the model", async () => {
  const host = new Host(
    {
      complete: async () => {
        throw new Error("the model was called");
      },
    },
    {
      read: () => "",
      replace: () => ({ ok: false as const }),
    },
    undefined,
    quiet,
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("What is Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The Host could not save the credential." },
  ]);
  assert.equal(JSON.stringify(host.transcript()).includes("sk-test-secret"), false);
});

test("a Conversation is still there after the Host restarts", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const model = {
    complete: async () => ({ kind: "assistant" as const, text: "Polarion is an ALM tool." }),
  };
  const first = new Host(model, undefined, new FileConversationStore(directory), quiet);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");

  const restarted = new Host(model, undefined, new FileConversationStore(directory), quiet);
  const seen = first.view();

  assert.deepEqual(restarted.view(), {
    conversations: [{ id: seen.conversations[0].id, title: "What is Polarion?", open: true }],
    transcript: [
      { role: "person", text: "What is Polarion?" },
      { role: "assistant", text: "Polarion is an ALM tool." },
    ],
  });
  assert.equal(JSON.stringify(restarted.view()).includes("sk-test-secret"), false);
});

test("several Conversations stay on disk and only one is open", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const model = {
    complete: async (call: { messages: { text: string }[] }) => {
      const latest = call.messages[call.messages.length - 1]?.text;
      if (latest === "What is Polarion?") {
        return { kind: "assistant" as const, text: "Polarion is an ALM tool." };
      }
      return { kind: "assistant" as const, text: "Still about Polarion." };
    },
  };
  const first = new Host(model, undefined, new FileConversationStore(directory), quiet);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");
  first.start();
  await first.send("And the second question?");

  const seen = first.view();
  assert.deepEqual(
    seen.conversations.map((conversation) => ({ title: conversation.title, open: conversation.open })),
    [
      { title: "What is Polarion?", open: false },
      { title: "And the second question?", open: true },
    ],
  );
  assert.deepEqual(seen.transcript, [
    { role: "person", text: "And the second question?" },
    { role: "assistant", text: "Still about Polarion." },
  ]);

  const restarted = new Host(model, undefined, new FileConversationStore(directory), quiet);
  const restored = restarted.view();
  assert.deepEqual(
    restored.conversations.map((conversation) => ({ title: conversation.title, open: conversation.open })),
    [
      { title: "What is Polarion?", open: false },
      { title: "And the second question?", open: true },
    ],
  );
  assert.deepEqual(restored.transcript, [
    { role: "person", text: "And the second question?" },
    { role: "assistant", text: "Still about Polarion." },
  ]);
  restarted.open(restored.conversations[0].id);
  assert.deepEqual(restarted.view().transcript, [
    { role: "person", text: "What is Polarion?" },
    { role: "assistant", text: "Polarion is an ALM tool." },
  ]);
});

test("starting again while the open Conversation is empty keeps that one", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const host = new Host(
    { complete: async () => ({ kind: "assistant" as const, text: "unused" }) },
    undefined,
    new FileConversationStore(directory),
    quiet,
  );
  host.start();
  const firstId = host.view().conversations[0].id;
  host.start();
  const view = host.view();
  assert.deepEqual(view.conversations, [{ id: firstId, title: "New conversation", open: true }]);
  assert.deepEqual(view.transcript, []);
});

test("opening an unknown Conversation changes nothing", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const host = new Host(
    { complete: async () => ({ kind: "assistant" as const, text: "Polarion is an ALM tool." }) },
    undefined,
    new FileConversationStore(directory),
    quiet,
  );
  host.setCredential("sk-test-secret");
  await host.send("What is Polarion?");
  const before = host.view();
  host.open("missing");
  assert.deepEqual(host.view(), before);
});

test("a blank message does not start a Conversation", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const host = new Host(
    { complete: async () => ({ kind: "assistant" as const, text: "unused" }) },
    undefined,
    new FileConversationStore(directory),
    quiet,
  );
  await host.send("   ");
  assert.deepEqual(host.view(), { conversations: [], transcript: [] });
});

test("a failure stays after restart and is left out of the next model call", async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const calls: { text: string }[][] = [];
  const model = {
    complete: async (call: { messages: { text: string }[] }) => {
      calls.push(call.messages);
      return { kind: "unreachable" as const };
    },
  };
  const first = new Host(model, undefined, new FileConversationStore(directory), quiet);
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");
  const restarted = new Host(
    {
      complete: async (call: { messages: { text: string }[] }) => {
        calls.push(call.messages);
        return { kind: "assistant" as const, text: "Polarion is an ALM tool." };
      },
    },
    undefined,
    new FileConversationStore(directory),
    quiet,
  );
  restarted.setCredential("sk-test-secret");
  await restarted.send("Try again");
  assert.deepEqual(restarted.view().transcript, [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The model could not be reached." },
    { role: "person", text: "Try again" },
    { role: "assistant", text: "Polarion is an ALM tool." },
  ]);
  assert.deepEqual(calls[1], [
    { role: "person", text: "What is Polarion?" },
    { role: "person", text: "Try again" },
  ]);
});

test("unavailable Polarion tools are a failure and the model still answers", async () => {
  let usedCall = false;
  const completes: ModelCall[] = [];
  const host = new Host(
    {
      complete: async (call) => {
        completes.push(call);
        return { kind: "assistant" as const, text: "Polarion is an ALM tool." };
      },
    },
    undefined,
    undefined,
    {
      async tools() {
        return { tools: [], lines: ["Polarion tools are unavailable."] };
      },
      async call() {
        usedCall = true;
        throw new Error("call");
      },
    },
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("What is Polarion?");
  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "Polarion tools are unavailable." },
    { role: "assistant", text: "Polarion is an ALM tool." },
  ]);
  assert.equal(usedCall, false);
  assert.equal(completes.length, 1);
  assert.equal(completes[0].instructions.includes("Do not invent tool results"), true);
  assert.deepEqual(completes[0].tools, []);
});

test("a listed tool with no dry_run runs and the next complete sees the tool entry", async () => {
  const completes: ModelCall[] = [];
  let mcpCalls = 0;
  const host = new Host(
    {
      complete: async (call) => {
        completes.push(call);
        if (call.messages.some((message) => message.role === "tool")) {
          return { kind: "assistant" as const, text: "You are tester." };
        }
        return {
          kind: "tool_calls" as const,
          text: "",
          calls: [{ id: "c1", name: "whoami", arguments: {} }],
        };
      },
    },
    undefined,
    undefined,
    {
      async tools() {
        return {
          tools: [{ name: "whoami", description: "Current Polarion user", inputSchema: {} }],
          lines: [],
        };
      },
      async call(name, args) {
        mcpCalls += 1;
        assert.equal(name, "whoami");
        assert.deepEqual(args, {});
        return { status: "ran", text: "tester@example.com" };
      },
    },
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("Who am I?");
  assert.deepEqual(host.transcript(), [
    { role: "person", text: "Who am I?" },
    {
      role: "tool",
      callId: "c1",
      name: "whoami",
      arguments: {},
      status: "ran",
      text: "tester@example.com",
    },
    { role: "assistant", text: "You are tester." },
  ]);
  assert.equal(mcpCalls, 1);
  assert.equal(completes.length, 2);
  assert.equal(
    completes[0].instructions.includes("Use only those tools"),
    true,
  );
  assert.equal(completes[0].instructions.includes("Do not invent tool results"), true);
  const toolMessage = completes[1].messages.find((message) => message.role === "tool");
  assert.deepEqual(toolMessage, {
    role: "tool",
    callId: "c1",
    name: "whoami",
    arguments: {},
    status: "ran",
    text: "tester@example.com",
  });
});

test("dry_run false holds the call and still runs a sibling", async () => {
  const called: string[] = [];
  const followUp: ModelCall["messages"] = [];
  const host = new Host(
    {
      complete: async (call): Promise<ModelResult> => {
        if (call.messages.some((message) => message.role === "tool")) {
          followUp.push(...call.messages.filter((message) => message.role === "tool"));
          return { kind: "assistant", text: "Done." };
        }
        return {
          kind: "tool_calls",
          text: "Working.",
          calls: [
            { id: "c1", name: "write_work_item", arguments: { dry_run: false, id: "WI-1" } },
            { id: "c2", name: "whoami", arguments: {} },
          ],
        };
      },
    },
    undefined,
    undefined,
    {
      async tools() {
        return {
          tools: [
            { name: "write_work_item", description: "Write a work item", inputSchema: {} },
            { name: "whoami", description: "Current Polarion user", inputSchema: {} },
          ],
          lines: [],
        };
      },
      async call(name) {
        called.push(name);
        return { status: "ran", text: "ok" };
      },
    },
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("Apply that and who am I?");
  assert.deepEqual(called, ["whoami"]);
  assert.deepEqual(host.transcript(), [
    { role: "person", text: "Apply that and who am I?" },
    { role: "assistant", text: "Working." },
    {
      role: "tool",
      callId: "c1",
      name: "write_work_item",
      arguments: { dry_run: false, id: "WI-1" },
      status: "held",
      text: "Not run. This call waits for Apply.",
    },
    {
      role: "tool",
      callId: "c2",
      name: "whoami",
      arguments: {},
      status: "ran",
      text: "ok",
    },
    { role: "assistant", text: "Done." },
  ]);
  assert.deepEqual(followUp, [
    {
      role: "tool",
      callId: "c1",
      name: "write_work_item",
      arguments: { dry_run: false, id: "WI-1" },
      status: "held",
      text: "Not run. This call waits for Apply.",
    },
    {
      role: "tool",
      callId: "c2",
      name: "whoami",
      arguments: {},
      status: "ran",
      text: "ok",
    },
  ]);
});

test("a failed tool result is stored and the model is called again", async () => {
  let completes = 0;
  const host = new Host(
    {
      complete: async (call) => {
        completes += 1;
        if (call.messages.some((message) => message.role === "tool")) {
          return { kind: "assistant" as const, text: "The tool failed." };
        }
        return {
          kind: "tool_calls" as const,
          text: "",
          calls: [{ id: "c1", name: "whoami", arguments: {} }],
        };
      },
    },
    undefined,
    undefined,
    {
      async tools() {
        return {
          tools: [{ name: "whoami", description: "Current Polarion user", inputSchema: {} }],
          lines: [],
        };
      },
      async call() {
        return { status: "failed" as const, text: "Polarion said no." };
      },
    },
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("Who am I?");
  assert.deepEqual(host.transcript(), [
    { role: "person", text: "Who am I?" },
    {
      role: "tool",
      callId: "c1",
      name: "whoami",
      arguments: {},
      status: "failed",
      text: "Polarion said no.",
    },
    { role: "assistant", text: "The tool failed." },
  ]);
  assert.equal(completes, 2);
});

test("seven tool_calls responses stop after six executed rounds", async () => {
  const names = ["tool_a", "tool_b", "tool_c", "tool_d", "tool_e", "tool_f", "tool_g"];
  let completeCount = 0;
  let mcpCalls = 0;
  const host = new Host(
    {
      complete: async () => {
        if (completeCount > 10) {
          throw new Error("too many complete calls");
        }
        const name = names[Math.min(completeCount, names.length - 1)];
        completeCount += 1;
        return {
          kind: "tool_calls" as const,
          text: "",
          calls: [{ id: `c${completeCount}`, name, arguments: {} }],
        };
      },
    },
    undefined,
    undefined,
    {
      async tools() {
        return {
          tools: names.map((name) => ({ name, description: name, inputSchema: {} })),
          lines: [],
        };
      },
      async call(name) {
        mcpCalls += 1;
        return { status: "ran", text: name };
      },
    },
  );
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");
  await host.send("Keep going");
  assert.equal(completeCount, 7);
  assert.equal(mcpCalls, 6);
  assert.equal(
    host.transcript().some(
      (entry) =>
        entry.role === "failure" &&
        entry.text === "The model asked for tools too many times on this message, so the Host stopped.",
    ),
    true,
  );
  assert.equal(
    host.transcript().some((entry) => entry.role === "tool" && entry.name === "tool_g"),
    false,
  );
  assert.equal(JSON.stringify(host.transcript()).includes("tool_g"), false);
});

test("FileConversationStore round trip keeps a tool entry and failure entries", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "arctic-conversations-"));
  const store = new FileConversationStore(directory);
  const tool = {
    role: "tool" as const,
    callId: "c1",
    name: "whoami",
    arguments: { login: true },
    status: "ran" as const,
    text: "tester@example.com",
  };
  store.write({
    openId: "conv-1",
    conversations: [
      {
        id: "conv-1",
        entries: [
          { role: "person", text: "Who am I?" },
          tool,
          { role: "failure", text: "Polarion tools are unavailable." },
        ],
      },
    ],
  });
  const file = path.join(directory, "conversations.json");
  const onDisk = JSON.parse(fs.readFileSync(file, "utf8")) as {
    conversations: { entries: unknown[] }[];
  };
  onDisk.conversations[0].entries.push({ role: "narrator", text: "drop" });
  onDisk.conversations[0].entries.push({
    role: "tool",
    name: "whoami",
    arguments: {},
    status: "ran",
    text: "missing callId",
  });
  fs.writeFileSync(file, JSON.stringify(onDisk, null, 2));

  const roundTrip = new FileConversationStore(directory).read();
  assert.deepEqual(roundTrip.conversations[0].entries, [
    { role: "person", text: "Who am I?" },
    tool,
    { role: "failure", text: "Polarion tools are unavailable." },
  ]);

  const host = new Host(
    { complete: async () => ({ kind: "assistant" as const, text: "unused" }) },
    undefined,
    new FileConversationStore(directory),
    quiet,
  );
  assert.deepEqual(host.transcript(), [
    { role: "person", text: "Who am I?" },
    tool,
    { role: "failure", text: "Polarion tools are unavailable." },
  ]);
});
