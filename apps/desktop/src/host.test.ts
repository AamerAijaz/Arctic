import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { FileConversationStore } from "./conversation-store";
import { Host } from "./host";

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

test("a person message is followed by the scripted reply", async () => {
  const host = new Host({
    complete: async () => ({ kind: "assistant", text: "Polarion is an ALM tool." }),
  });
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
  const host = new Host({
    complete: async (call) => {
      const remembered = call.messages.some((message) => message.text === "What is Polarion?");
      if (call.messages.length > 1 && remembered) {
        return { kind: "assistant", text: "Still about Polarion." };
      }
      return { kind: "assistant", text: "Polarion is an ALM tool." };
    },
  });
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
  const host = new Host({
    complete: async () => ({ kind: "assistant", text: "Polarion is an ALM tool." }),
  });
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("What is Polarion?");

  assert.equal(JSON.stringify(host.transcript()).includes("sk-test-secret"), false);
});

test("a missing credential records a failure and does not call the model", async () => {
  const host = new Host({
    complete: async () => {
      throw new Error("the model was called");
    },
  });
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");

  await host.send("What is Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "What is Polarion?" },
    { role: "failure", text: "The model credential is missing." },
  ]);
});

test("a rejected credential records a failure without the credential or a raw body", async () => {
  const host = new Host({
    complete: async () => ({ kind: "rejected", body: "sk-test-secret raw-401-body" }),
  });
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
  const host = new Host({
    complete: async () => ({ kind: "unreachable", body: "sk-test-secret connection reset" }),
  });
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
  const host = new Host({
    complete: async () => {
      throw new Error("the model was called");
    },
  });
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("   ");

  assert.deepEqual(host.transcript(), []);
});

test("a tool call from the model becomes a failure", async () => {
  const host = new Host({
    complete: async () => ({ kind: "tool_call", name: "whoami" }),
  });
  host.setApiRoot("https://api.openai.com/v1");
  host.setModelName("gpt-4o-mini");
  host.setCredential("sk-test-secret");

  await host.send("Who am I in Polarion?");

  assert.deepEqual(host.transcript(), [
    { role: "person", text: "Who am I in Polarion?" },
    { role: "failure", text: "The model asked for a tool, and this Host cannot run tools." },
  ]);
  assert.equal(JSON.stringify(host.transcript()).includes("whoami"), false);
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
  const first = new Host(model, secrets);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");

  const restarted = new Host(model, secrets);
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
  const first = new Host(model, secrets);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");
  first.setCredential("sk-rotated-secret");

  const restarted = new Host(model, secrets);
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
  const first = new Host(model, secrets);
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");

  const restarted = new Host(model, secrets);
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
  const first = new Host(model, undefined, new FileConversationStore(directory));
  first.setApiRoot("https://api.openai.com/v1");
  first.setModelName("gpt-4o-mini");
  first.setCredential("sk-test-secret");
  await first.send("What is Polarion?");

  const restarted = new Host(model, undefined, new FileConversationStore(directory));
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
  const first = new Host(model, undefined, new FileConversationStore(directory));
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

  const restarted = new Host(model, undefined, new FileConversationStore(directory));
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
  const first = new Host(model, undefined, new FileConversationStore(directory));
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
