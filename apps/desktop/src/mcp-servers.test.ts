import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import {
  McpServers,
  type JsonObject,
  type McpSocket,
  type McpSockets,
  type ToolOutcome,
} from "./mcp-servers";

function tempDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), "arctic-mcp-"));
}

function mcpPath(directory: string): string {
  return path.join(directory, "mcp.json");
}

function writeMcp(directory: string, value: unknown): void {
  fs.writeFileSync(mcpPath(directory), JSON.stringify(value, null, 2));
}

function readMcp(directory: string): unknown {
  return JSON.parse(fs.readFileSync(mcpPath(directory), "utf8"));
}

class ScriptedSocket implements McpSocket {
  readonly calls: Array<{ tool: string; args: JsonObject }> = [];
  closed = false;

  constructor(
    private readonly listed: Array<{ name: string; description: string; inputSchema: JsonObject }>,
    private readonly handler?: (tool: string, args: JsonObject) => ToolOutcome,
  ) {}

  async list(): Promise<Array<{ name: string; description: string; inputSchema: JsonObject }>> {
    return this.listed;
  }

  async call(tool: string, args: JsonObject): Promise<ToolOutcome> {
    this.calls.push({ tool, args });
    if (this.handler !== undefined) {
      return this.handler(tool, args);
    }
    return { status: "ran", text: "" };
  }

  close(): void {
    this.closed = true;
  }
}

class FailingSocket implements McpSocket {
  closed = false;

  async list(): Promise<Array<{ name: string; description: string; inputSchema: JsonObject }>> {
    throw new Error("unreachable");
  }

  async call(): Promise<ToolOutcome> {
    throw new Error("unreachable");
  }

  close(): void {
    this.closed = true;
  }
}

class RecordingSockets implements McpSockets {
  readonly opened: string[] = [];

  constructor(private readonly create: (url: string) => McpSocket) {}

  open(url: string): McpSocket {
    this.opened.push(url);
    return this.create(url);
  }
}

test("add writes the Claude JSON shape and a second add keeps the first entry", async () => {
  const directory = tempDir();
  const sockets = new RecordingSockets(() => {
    throw new Error("add must not open sockets");
  });
  const servers = new McpServers(directory, sockets);

  const first = await servers.add("arctic", " http://127.0.0.1:8080/mcp ");
  assert.equal(first.error, null);
  assert.deepEqual(first.servers, [
    { name: "arctic", url: "http://127.0.0.1:8080/mcp", label: "Saved", detail: "" },
  ]);
  assert.deepEqual(readMcp(directory), {
    mcpServers: {
      arctic: { type: "http", url: "http://127.0.0.1:8080/mcp" },
    },
  });

  const second = await servers.add("notes", "http://127.0.0.1:9090/mcp");
  assert.equal(second.error, null);
  assert.deepEqual(second.servers, [
    { name: "arctic", url: "http://127.0.0.1:8080/mcp", label: "Saved", detail: "" },
    { name: "notes", url: "http://127.0.0.1:9090/mcp", label: "Saved", detail: "" },
  ]);
  assert.deepEqual(readMcp(directory), {
    mcpServers: {
      arctic: { type: "http", url: "http://127.0.0.1:8080/mcp" },
      notes: { type: "http", url: "http://127.0.0.1:9090/mcp" },
    },
  });
  assert.deepEqual(sockets.opened, []);
});

test("a duplicate add leaves the file byte-for-byte unchanged", async () => {
  const directory = tempDir();
  const sockets = new RecordingSockets(() => {
    throw new Error("add must not open sockets");
  });
  const servers = new McpServers(directory, sockets);
  await servers.add("arctic", "http://127.0.0.1:8080/mcp");
  const before = fs.readFileSync(mcpPath(directory));

  const duplicate = await servers.add("arctic", "http://127.0.0.1:9090/mcp");

  assert.equal(duplicate.error, 'A server named "arctic" is already configured.');
  assert.deepEqual(duplicate.servers, [
    { name: "arctic", url: "http://127.0.0.1:8080/mcp", label: "Saved", detail: "" },
  ]);
  assert.deepEqual(fs.readFileSync(mcpPath(directory)), before);
  assert.deepEqual(sockets.opened, []);
});

test("a stdio entry is never passed to open", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      proto: { type: "stdio", command: "uv", args: ["run"], env: { TOKEN: "secret" } },
    },
  });
  const sockets = new RecordingSockets(() => {
    throw new Error("stdio servers must not be opened");
  });
  const servers = new McpServers(directory, sockets);

  const listed = await servers.list();
  assert.deepEqual(listed.servers, [
    {
      name: "proto",
      url: "",
      label: "Not an HTTP server",
      detail: "Arctic does not start stdio servers",
    },
  ]);
  const catalog = await servers.tools();
  assert.deepEqual(catalog.tools, []);
  assert.deepEqual(catalog.lines, []);
  assert.deepEqual(sockets.opened, []);
});

test("a url without type is never passed to open", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      arctic: { url: "http://127.0.0.1:8080/mcp" },
    },
  });
  const sockets = new RecordingSockets(() => {
    throw new Error("entries without type must not be opened");
  });
  const servers = new McpServers(directory, sockets);

  const listed = await servers.list();
  assert.deepEqual(listed.servers, [
    {
      name: "arctic",
      url: "http://127.0.0.1:8080/mcp",
      label: "Not an HTTP server",
      detail: 'Needs "type": "http"',
    },
  ]);
  const catalog = await servers.tools();
  assert.deepEqual(catalog.tools, []);
  assert.deepEqual(catalog.lines, []);
  assert.deepEqual(sockets.opened, []);
});

test("when arctic is unreachable, notes tools are still listed and callable", async () => {
  const directory = tempDir();
  const arcticUrl = "http://127.0.0.1:8080/mcp";
  const notesUrl = "http://127.0.0.1:9090/mcp";
  writeMcp(directory, {
    mcpServers: {
      arctic: { type: "http", url: arcticUrl },
      notes: { type: "http", url: notesUrl },
    },
  });
  const notes = new ScriptedSocket([
    { name: "find", description: "Find a note", inputSchema: {} },
  ]);
  const sockets = new RecordingSockets((url) => {
    if (url === notesUrl) {
      return notes;
    }
    return new FailingSocket();
  });
  const servers = new McpServers(directory, sockets);

  const catalog = await servers.tools();
  assert.deepEqual(catalog.lines, ["Polarion tools are unavailable."]);
  assert.deepEqual(catalog.tools, [
    { name: "mcp__notes__find", description: "Find a note", inputSchema: {} },
  ]);
  assert.equal(
    sockets.opened.some((url) => url === notesUrl),
    true,
  );
  assert.equal(
    sockets.opened.some((url) => url === arcticUrl),
    true,
  );

  const outcome = await servers.call("mcp__notes__find", {});
  assert.deepEqual(outcome, { status: "ran", text: "" });
  assert.deepEqual(notes.calls, [{ tool: "find", args: {} }]);
});

test("no servers yields the Polarion sentence and does not open a socket", async () => {
  const directory = tempDir();
  const sockets = new RecordingSockets(() => {
    throw new Error("open must not be called when no servers are configured");
  });
  const servers = new McpServers(directory, sockets);

  const catalog = await servers.tools();
  assert.deepEqual(catalog, {
    tools: [],
    lines: ["Polarion tools are unavailable."],
  });
  const listed = await servers.list();
  assert.deepEqual(listed, { servers: [], error: null });
  assert.deepEqual(sockets.opened, []);
});

test("corrupt json is not overwritten by add", async () => {
  const directory = tempDir();
  const raw = "{ not json";
  fs.writeFileSync(mcpPath(directory), raw);
  const sockets = new RecordingSockets(() => {
    throw new Error("corrupt json must not open sockets");
  });
  const servers = new McpServers(directory, sockets);

  const catalog = await servers.tools();
  assert.deepEqual(catalog, {
    tools: [],
    lines: ["MCP tools are unavailable. The server list could not be read."],
  });
  const listed = await servers.list();
  assert.deepEqual(listed, {
    servers: [],
    error: "MCP tools are unavailable. The server list could not be read.",
  });

  const added = await servers.add("arctic", "http://127.0.0.1:8080/mcp");
  assert.equal(added.error, "MCP tools are unavailable. The server list could not be read.");
  assert.deepEqual(added.servers, []);
  assert.equal(fs.readFileSync(mcpPath(directory), "utf8"), raw);

  const removed = await servers.remove("arctic");
  assert.equal(removed.error, "MCP tools are unavailable. The server list could not be read.");
  assert.equal(fs.readFileSync(mcpPath(directory), "utf8"), raw);
  assert.deepEqual(sockets.opened, []);
});

test("add preserves other top-level keys and unknown fields on other entries", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    extra: true,
    mcpServers: {
      arctic: {
        type: "http",
        url: "http://127.0.0.1:8080/mcp",
        headers: { "X-Debug": "1" },
        env: { TOKEN: "secret" },
      },
    },
  });
  const servers = new McpServers(
    directory,
    new RecordingSockets(() => {
      throw new Error("add must not open sockets");
    }),
  );

  const added = await servers.add("notes", "http://127.0.0.1:9090/mcp");
  assert.equal(added.error, null);
  assert.deepEqual(readMcp(directory), {
    extra: true,
    mcpServers: {
      arctic: {
        type: "http",
        url: "http://127.0.0.1:8080/mcp",
        headers: { "X-Debug": "1" },
        env: { TOKEN: "secret" },
      },
      notes: { type: "http", url: "http://127.0.0.1:9090/mcp" },
    },
  });
});

test("a duplicate unusable name is rejected without rewriting", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      arctic: { type: "stdio", command: "uv" },
    },
  });
  const before = fs.readFileSync(mcpPath(directory));
  const servers = new McpServers(
    directory,
    new RecordingSockets(() => {
      throw new Error("add must not open sockets");
    }),
  );

  const duplicate = await servers.add("arctic", "http://127.0.0.1:8080/mcp");
  assert.equal(duplicate.error, 'A server named "arctic" is already configured.');
  assert.deepEqual(fs.readFileSync(mcpPath(directory)), before);
});

test("invalid names and urls set an error and leave the file unchanged", async () => {
  const directory = tempDir();
  const servers = new McpServers(
    directory,
    new RecordingSockets(() => {
      throw new Error("validation must not open sockets");
    }),
  );
  await servers.add("arctic", "http://127.0.0.1:8080/mcp");
  const before = fs.readFileSync(mcpPath(directory));

  const badName = await servers.add("polarion tools", "http://127.0.0.1:9090/mcp");
  assert.equal(badName.error, "Server names are letters, numbers, hyphens, and underscores.");
  assert.deepEqual(badName.servers, [
    { name: "arctic", url: "http://127.0.0.1:8080/mcp", label: "Saved", detail: "" },
  ]);
  assert.deepEqual(fs.readFileSync(mcpPath(directory)), before);

  const badUrl = await servers.add("notes", "not-a-url");
  assert.equal(badUrl.error, "The URL must be an http or https address.");
  assert.deepEqual(fs.readFileSync(mcpPath(directory)), before);

  const emptyUrl = await servers.add("notes", "  ");
  assert.equal(emptyUrl.error, "The URL must be an http or https address.");

  const userinfo = await servers.add("notes", "http://user:pass@127.0.0.1:9090/mcp");
  assert.equal(userinfo.error, "The URL must be an http or https address.");
  assert.deepEqual(fs.readFileSync(mcpPath(directory)), before);
});

test("removing an unknown name does not rewrite the file", async () => {
  const directory = tempDir();
  const servers = new McpServers(
    directory,
    new RecordingSockets(() => {
      throw new Error("remove must not open sockets");
    }),
  );
  await servers.add("arctic", "http://127.0.0.1:8080/mcp");
  const before = fs.readFileSync(mcpPath(directory));

  const missing = await servers.remove("nope");
  assert.equal(missing.error, 'No server named "nope".');
  assert.deepEqual(missing.servers, [
    { name: "arctic", url: "http://127.0.0.1:8080/mcp", label: "Saved", detail: "" },
  ]);
  assert.deepEqual(fs.readFileSync(mcpPath(directory)), before);
});

test("remove deletes a saved server and closes its socket", async () => {
  const directory = tempDir();
  const arctic = new ScriptedSocket([{ name: "whoami", description: "Who", inputSchema: {} }]);
  const sockets = new RecordingSockets(() => arctic);
  const servers = new McpServers(directory, sockets);
  await servers.add("arctic", "http://127.0.0.1:8080/mcp");
  await servers.list();
  assert.equal(arctic.closed, false);

  const removed = await servers.remove("arctic");
  assert.equal(removed.error, null);
  assert.deepEqual(removed.servers, []);
  assert.deepEqual(readMcp(directory), { mcpServers: {} });
  assert.equal(arctic.closed, true);
});

test("list probes usable servers and counts hidden tools", async () => {
  const directory = tempDir();
  const longName = "a".repeat(53);
  writeMcp(directory, {
    mcpServers: {
      arctic: { type: "streamable-http", url: "http://127.0.0.1:8080/mcp" },
    },
  });
  const sockets = new RecordingSockets(
    () =>
      new ScriptedSocket([
        { name: "whoami", description: "Who", inputSchema: {} },
        { name: "find", description: "Find", inputSchema: {} },
        { name: longName, description: "Hidden", inputSchema: {} },
      ]),
  );
  const servers = new McpServers(directory, sockets);

  const listed = await servers.list();
  assert.deepEqual(listed, {
    servers: [
      {
        name: "arctic",
        url: "http://127.0.0.1:8080/mcp",
        label: "Connected",
        detail: "2 tools, 1 hidden",
      },
    ],
    error: null,
  });
});

test("list marks a throwing server unreachable after one retry", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      arctic: { type: "http", url: "http://127.0.0.1:8080/mcp" },
    },
  });
  const sockets = new RecordingSockets(() => new FailingSocket());
  const servers = new McpServers(directory, sockets);

  const listed = await servers.list();
  assert.deepEqual(listed.servers, [
    {
      name: "arctic",
      url: "http://127.0.0.1:8080/mcp",
      label: "Unreachable",
      detail: "Not reachable",
    },
  ]);
  assert.equal(sockets.opened.length, 2);
});

test("list retries once on a fresh socket after a throw", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      arctic: { type: "http", url: "http://127.0.0.1:8080/mcp" },
    },
  });
  let opens = 0;
  const sockets = new RecordingSockets(() => {
    opens += 1;
    if (opens === 1) {
      return new FailingSocket();
    }
    return new ScriptedSocket([{ name: "whoami", description: "Who", inputSchema: {} }]);
  });
  const servers = new McpServers(directory, sockets);

  const listed = await servers.list();
  assert.equal(listed.servers[0]?.label, "Connected");
  assert.equal(listed.servers[0]?.detail, "1 tool");
  assert.equal(sockets.opened.length, 2);
});

test("sse and command entries stay listed and are never opened", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      events: { type: "sse", url: "http://127.0.0.1:8080/sse" },
      local: { command: "uv" },
      empty: { type: "http", url: "" },
    },
  });
  const sockets = new RecordingSockets(() => {
    throw new Error("unusable entries must not be opened");
  });
  const servers = new McpServers(directory, sockets);

  const listed = await servers.list();
  assert.deepEqual(listed.servers, [
    {
      name: "events",
      url: "http://127.0.0.1:8080/sse",
      label: "Not an HTTP server",
      detail: "Only http servers are supported",
    },
    {
      name: "local",
      url: "",
      label: "Not an HTTP server",
      detail: "Arctic does not start stdio servers",
    },
    { name: "empty", url: "", label: "Not connected", detail: "No URL" },
  ]);
  assert.deepEqual(sockets.opened, []);
});

test("tools collapse colliding names and omit overlong names", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      notes: { type: "http", url: "http://127.0.0.1:9090/mcp" },
    },
  });
  const longName = "a".repeat(60);
  const sockets = new RecordingSockets(
    () =>
      new ScriptedSocket([
        { name: "x.y", description: "Dotted", inputSchema: {} },
        { name: "x_y", description: "Underscore", inputSchema: {} },
        { name: "find", description: "Find", inputSchema: {} },
        { name: longName, description: "Long", inputSchema: {} },
      ]),
  );
  const servers = new McpServers(directory, sockets);

  const catalog = await servers.tools();
  assert.deepEqual(catalog.lines, []);
  assert.deepEqual(catalog.tools, [{ name: "mcp__notes__find", description: "Find", inputSchema: {} }]);
});

test("an unknown tool or a changed url does not open a socket", async () => {
  const directory = tempDir();
  const notesUrl = "http://127.0.0.1:9090/mcp";
  writeMcp(directory, {
    mcpServers: {
      notes: { type: "http", url: notesUrl },
    },
  });
  const notes = new ScriptedSocket([{ name: "find", description: "Find", inputSchema: {} }]);
  const sockets = new RecordingSockets(() => notes);
  const servers = new McpServers(directory, sockets);
  await servers.tools();
  const openedAfterTools = sockets.opened.length;

  const missing = await servers.call("mcp__notes__missing", {});
  assert.deepEqual(missing, { status: "failed", text: "That tool is not available." });
  assert.equal(sockets.opened.length, openedAfterTools);
  assert.deepEqual(notes.calls, []);

  writeMcp(directory, {
    mcpServers: {
      notes: { type: "http", url: "http://127.0.0.1:9191/mcp" },
    },
  });
  const changed = await servers.call("mcp__notes__find", {});
  assert.deepEqual(changed, { status: "failed", text: "That tool is not available." });
  assert.equal(sockets.opened.length, openedAfterTools);
  assert.deepEqual(notes.calls, []);
});

test("a thrown tool call fails without retry and drops the socket", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      notes: { type: "http", url: "http://127.0.0.1:9090/mcp" },
    },
  });
  const notes = new ScriptedSocket([{ name: "find", description: "Find", inputSchema: {} }], () => {
    throw new Error("transport");
  });
  const sockets = new RecordingSockets(() => notes);
  const servers = new McpServers(directory, sockets);
  await servers.tools();

  const outcome = await servers.call("mcp__notes__find", { q: "n" });
  assert.deepEqual(outcome, { status: "failed", text: "The MCP server could not be reached." });
  assert.equal(notes.closed, true);
  assert.deepEqual(notes.calls, [{ tool: "find", args: { q: "n" } }]);
});

test("close closes open sockets", async () => {
  const directory = tempDir();
  writeMcp(directory, {
    mcpServers: {
      arctic: { type: "http", url: "http://127.0.0.1:8080/mcp" },
    },
  });
  const arctic = new ScriptedSocket([{ name: "whoami", description: "Who", inputSchema: {} }]);
  const servers = new McpServers(
    directory,
    new RecordingSockets(() => arctic),
  );
  await servers.list();
  servers.close();
  assert.equal(arctic.closed, true);
});
