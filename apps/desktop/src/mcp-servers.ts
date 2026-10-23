import fs from "node:fs";
import path from "node:path";

export type JsonObject = { [key: string]: Json };
export type Json = null | boolean | number | string | Json[] | JsonObject;

export type ToolDefinition = { name: string; description: string; inputSchema: JsonObject };

export type ToolCatalog = { tools: ToolDefinition[]; lines: string[] };

export type ToolOutcome =
  | { status: "ran"; text: string }
  | { status: "failed"; text: string };

export type McpRow = {
  name: string;
  url: string;
  label: "Saved" | "Connected" | "Unreachable" | "Not connected" | "Not an HTTP server";
  detail: string;
};

export type McpSettingsView = { servers: McpRow[]; error: string | null };

export interface McpSockets {
  open(url: string): McpSocket;
}
export interface McpSocket {
  list(): Promise<Array<{ name: string; description: string; inputSchema: JsonObject }>>;
  call(tool: string, args: JsonObject): Promise<ToolOutcome>;
  close(): void;
}

const corruptMessage = "MCP tools are unavailable. The server list could not be read.";
const polarionUnavailable = "Polarion tools are unavailable.";
const listWaitMs = 5_000;

type ListedTool = { name: string; description: string; inputSchema: JsonObject };

type Classified = {
  name: string;
  usable: boolean;
  url: string;
  label: McpRow["label"];
  detail: string;
};

type Loaded =
  | { status: "corrupt" }
  | { status: "ok"; doc: JsonObject; servers: JsonObject };

type ToolRoute = { server: string; url: string; tool: string };

type OpenSocket = { url: string; socket: McpSocket };

export class McpServers {
  private sockets: McpSockets | undefined;
  private readonly openSockets = new Map<string, OpenSocket>();
  private routes = new Map<string, ToolRoute>();

  constructor(
    private readonly directory: string,
    sockets?: McpSockets,
  ) {
    this.sockets = sockets;
  }

  async list(): Promise<McpSettingsView> {
    const loaded = this.load();
    if (loaded.status === "corrupt") {
      return { servers: [], error: corruptMessage };
    }
    const classified = entries(loaded.servers);
    const rows: McpRow[] = classified.map((entry) => savedRow(entry));
    await Promise.all(
      classified.map(async (entry, index) => {
        if (!entry.usable) {
          return;
        }
        const listed = await this.listFrom(entry.name, entry.url);
        if (listed === "unreachable") {
          rows[index] = {
            name: entry.name,
            url: entry.url,
            label: "Unreachable",
            detail: "Not reachable",
          };
          return;
        }
        const counted = qualify(entry.name, listed);
        rows[index] = {
          name: entry.name,
          url: entry.url,
          label: "Connected",
          detail: toolDetail(counted.tools.length, counted.hidden),
        };
      }),
    );
    return { servers: rows, error: null };
  }

  async add(name: string, url: string): Promise<McpSettingsView> {
    const loaded = this.load();
    if (loaded.status === "corrupt") {
      return { servers: [], error: corruptMessage };
    }
    const trimmedName = name.trim();
    const trimmedUrl = url.trim();
    const rows = entries(loaded.servers).map(savedRow);
    if (!/^[A-Za-z0-9_-]+$/.test(trimmedName)) {
      return {
        servers: rows,
        error: "Server names are letters, numbers, hyphens, and underscores.",
      };
    }
    if (Object.hasOwn(loaded.servers, trimmedName)) {
      return { servers: rows, error: `A server named "${trimmedName}" is already configured.` };
    }
    if (!isHttpUrl(trimmedUrl)) {
      return { servers: rows, error: "The URL must be an http or https address." };
    }
    loaded.servers[trimmedName] = { type: "http", url: trimmedUrl };
    loaded.doc.mcpServers = loaded.servers;
    this.write(loaded.doc);
    return { servers: entries(loaded.servers).map(savedRow), error: null };
  }

  async remove(name: string): Promise<McpSettingsView> {
    const loaded = this.load();
    if (loaded.status === "corrupt") {
      return { servers: [], error: corruptMessage };
    }
    const trimmedName = name.trim();
    if (!Object.hasOwn(loaded.servers, trimmedName)) {
      return { servers: entries(loaded.servers).map(savedRow), error: `No server named "${trimmedName}".` };
    }
    this.drop(trimmedName);
    delete loaded.servers[trimmedName];
    loaded.doc.mcpServers = loaded.servers;
    this.write(loaded.doc);
    return { servers: entries(loaded.servers).map(savedRow), error: null };
  }

  async tools(): Promise<ToolCatalog> {
    this.routes = new Map();
    const loaded = this.load();
    if (loaded.status === "corrupt") {
      return { tools: [], lines: [corruptMessage] };
    }
    const classified = entries(loaded.servers);
    if (classified.length === 0) {
      return { tools: [], lines: [polarionUnavailable] };
    }
    const usable = classified.filter((entry) => entry.usable);
    const listed = await Promise.all(
      usable.map(async (entry) => ({
        entry,
        tools: await this.listFrom(entry.name, entry.url),
      })),
    );
    const tools: ToolDefinition[] = [];
    let polarionFailed = false;
    const lines: string[] = [];
    for (const item of listed) {
      if (item.tools === "unreachable") {
        if (item.entry.name === "arctic") {
          polarionFailed = true;
        } else {
          lines.push(`Tools from "${item.entry.name}" are unavailable.`);
        }
        continue;
      }
      const counted = qualify(item.entry.name, item.tools);
      tools.push(...counted.tools);
      for (const route of counted.routes) {
        this.routes.set(route.qualified, {
          server: item.entry.name,
          url: item.entry.url,
          tool: route.tool,
        });
      }
    }
    if (polarionFailed) {
      lines.unshift(polarionUnavailable);
    }
    return { tools, lines };
  }

  async call(name: string, args: JsonObject): Promise<ToolOutcome> {
    const route = this.routes.get(name);
    if (route === undefined) {
      return { status: "failed", text: "That tool is not available." };
    }
    const current = this.currentUrl(route.server);
    if (current !== route.url) {
      return { status: "failed", text: "That tool is not available." };
    }
    const sockets = await this.socketsNow();
    const socket = this.socketFor(sockets, route.server, route.url, false);
    try {
      return await socket.call(route.tool, args);
    } catch {
      socket.close();
      this.drop(route.server, socket);
      return { status: "failed", text: "The MCP server could not be reached." };
    }
  }

  close(): void {
    for (const opened of this.openSockets.values()) {
      opened.socket.close();
    }
    this.openSockets.clear();
  }

  private currentUrl(name: string): string | undefined {
    const loaded = this.load();
    if (loaded.status === "corrupt") {
      return undefined;
    }
    const value = loaded.servers[name];
    if (!isJsonObject(value)) {
      return undefined;
    }
    const classified = classify(name, value);
    return classified.usable ? classified.url : undefined;
  }

  private async listFrom(name: string, url: string): Promise<ListedTool[] | "unreachable"> {
    const sockets = await this.socketsNow();
    for (let attempt = 0; attempt < 2; attempt++) {
      const socket = this.socketFor(sockets, name, url, attempt > 0);
      try {
        const listed = await withTimeout(socket.list(), listWaitMs);
        this.openSockets.set(name, { url, socket });
        return listed.map(normalizeTool);
      } catch {
        socket.close();
        this.drop(name, socket);
      }
    }
    return "unreachable";
  }

  private socketFor(sockets: McpSockets, name: string, url: string, fresh: boolean): McpSocket {
    const cached = this.openSockets.get(name);
    if (cached !== undefined && (fresh || cached.url !== url)) {
      cached.socket.close();
      this.openSockets.delete(name);
    } else if (cached !== undefined) {
      return cached.socket;
    }
    return sockets.open(url);
  }

  private drop(name: string, socket?: McpSocket): void {
    const cached = this.openSockets.get(name);
    if (cached === undefined) {
      return;
    }
    if (socket !== undefined && cached.socket !== socket) {
      return;
    }
    if (socket === undefined) {
      cached.socket.close();
    }
    this.openSockets.delete(name);
  }

  private async socketsNow(): Promise<McpSockets> {
    if (this.sockets !== undefined) {
      return this.sockets;
    }
    const { httpSockets } = await import("./mcp-http");
    this.sockets = httpSockets();
    return this.sockets;
  }

  private load(): Loaded {
    const file = path.join(this.directory, "mcp.json");
    if (!fs.existsSync(file)) {
      const servers: JsonObject = {};
      return { status: "ok", doc: { mcpServers: servers }, servers };
    }
    try {
      const parsed: unknown = JSON.parse(fs.readFileSync(file, "utf8"));
      if (!isJsonObject(parsed)) {
        return { status: "corrupt" };
      }
      if (isJsonObject(parsed.mcpServers)) {
        return { status: "ok", doc: parsed, servers: parsed.mcpServers };
      }
      const servers: JsonObject = {};
      return { status: "ok", doc: parsed, servers };
    } catch {
      return { status: "corrupt" };
    }
  }

  private write(doc: JsonObject): void {
    const file = path.join(this.directory, "mcp.json");
    const temporary = path.join(this.directory, "mcp.json.tmp");
    fs.mkdirSync(this.directory, { recursive: true });
    fs.writeFileSync(temporary, JSON.stringify(doc, null, 2));
    fs.renameSync(temporary, file);
  }
}

function savedRow(entry: Classified): McpRow {
  return {
    name: entry.name,
    url: entry.url,
    label: entry.usable ? "Saved" : entry.label,
    detail: entry.usable ? "" : entry.detail,
  };
}

function entries(servers: JsonObject): Classified[] {
  const classified: Classified[] = [];
  for (const [name, value] of Object.entries(servers)) {
    if (!isJsonObject(value)) {
      continue;
    }
    classified.push(classify(name, value));
  }
  return classified;
}

function classify(name: string, entry: JsonObject): Classified {
  const type = typeof entry.type === "string" ? entry.type : "";
  const url = typeof entry.url === "string" ? entry.url.trim() : "";
  const httpType = type === "http" || type === "streamable-http";
  if (httpType) {
    if (url !== "" && isHttpUrl(url)) {
      return { name, usable: true, url, label: "Saved", detail: "" };
    }
    return { name, usable: false, url, label: "Not connected", detail: "No URL" };
  }
  if (type === "stdio" || Object.hasOwn(entry, "command")) {
    return {
      name,
      usable: false,
      url,
      label: "Not an HTTP server",
      detail: "Arctic does not start stdio servers",
    };
  }
  if (url !== "" && type === "") {
    return {
      name,
      usable: false,
      url,
      label: "Not an HTTP server",
      detail: 'Needs "type": "http"',
    };
  }
  if (type !== "") {
    return {
      name,
      usable: false,
      url,
      label: "Not an HTTP server",
      detail: "Only http servers are supported",
    };
  }
  return { name, usable: false, url, label: "Not connected", detail: "No URL" };
}

function qualify(
  server: string,
  listed: ListedTool[],
): { tools: ToolDefinition[]; hidden: number; routes: Array<{ qualified: string; tool: string }> } {
  const candidates: Array<{ qualified: string; tool: ListedTool }> = [];
  let hidden = 0;
  for (const tool of listed) {
    const qualified = `mcp__${server}__${tool.name.replace(/[^A-Za-z0-9_-]/g, "_")}`;
    if (qualified.length > 64) {
      hidden += 1;
      continue;
    }
    candidates.push({ qualified, tool });
  }
  const groups = new Map<string, Array<{ qualified: string; tool: ListedTool }>>();
  for (const candidate of candidates) {
    const group = groups.get(candidate.qualified) ?? [];
    group.push(candidate);
    groups.set(candidate.qualified, group);
  }
  const tools: ToolDefinition[] = [];
  const routes: Array<{ qualified: string; tool: string }> = [];
  for (const [qualified, group] of groups) {
    if (group.length !== 1) {
      hidden += group.length;
      continue;
    }
    const item = group[0];
    tools.push({
      name: qualified,
      description: item.tool.description,
      inputSchema: item.tool.inputSchema,
    });
    routes.push({ qualified, tool: item.tool.name });
  }
  return { tools, hidden, routes };
}

function toolDetail(visible: number, hidden: number): string {
  const tools = `${visible} tool${visible === 1 ? "" : "s"}`;
  if (hidden > 0) {
    return `${tools}, ${hidden} hidden`;
  }
  return tools;
}

function normalizeTool(value: { name: string; description: string; inputSchema: JsonObject }): ListedTool {
  return {
    name: typeof value.name === "string" ? value.name : "",
    description: typeof value.description === "string" ? value.description : "",
    inputSchema: isJsonObject(value.inputSchema) ? value.inputSchema : {},
  };
}

function isHttpUrl(value: string): boolean {
  try {
    const parsed = new URL(value);
    return (
      (parsed.protocol === "http:" || parsed.protocol === "https:") &&
      parsed.username === "" &&
      parsed.password === ""
    );
  } catch {
    return false;
  }
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error("timeout"));
    }, ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}
