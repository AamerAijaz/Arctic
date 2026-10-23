import type { JsonObject, McpSocket, McpSockets, ToolOutcome } from "./mcp-servers";

type SdkClient = {
  connect(transport: unknown): Promise<void>;
  listTools(): Promise<{
    tools: Array<{ name?: unknown; description?: unknown; inputSchema?: unknown }>;
  }>;
  callTool(request: { name: string; arguments?: JsonObject }): Promise<{
    content?: unknown;
    isError?: boolean;
  }>;
  close(): Promise<void>;
};

export function httpSockets(): McpSockets {
  return {
    open(url: string): McpSocket {
      return new HttpMcpSocket(url);
    },
  };
}

class HttpMcpSocket implements McpSocket {
  private client: SdkClient | undefined;

  constructor(private readonly url: string) {}

  async list(): Promise<Array<{ name: string; description: string; inputSchema: JsonObject }>> {
    const result = await (await this.connected()).listTools();
    return result.tools.map((tool) => ({
      name: typeof tool.name === "string" ? tool.name : "",
      description: typeof tool.description === "string" ? tool.description : "",
      inputSchema: isJsonObject(tool.inputSchema) ? tool.inputSchema : {},
    }));
  }

  async call(tool: string, args: JsonObject): Promise<ToolOutcome> {
    const result = await (await this.connected()).callTool({ name: tool, arguments: args });
    const text = textOf(result.content);
    if (result.isError === true) {
      return { status: "failed", text };
    }
    return { status: "ran", text };
  }

  close(): void {
    const client = this.client;
    this.client = undefined;
    if (client !== undefined) {
      void client.close();
    }
  }

  private async connected(): Promise<SdkClient> {
    if (this.client !== undefined) {
      return this.client;
    }
    const clientSpec = "@modelcontextprotocol/sdk/client/index.js";
    const httpSpec = "@modelcontextprotocol/sdk/client/streamableHttp.js";
    const clientMod = (await import(clientSpec)) as {
      Client: new (info: { name: string; version: string }) => SdkClient;
    };
    const httpMod = (await import(httpSpec)) as {
      StreamableHTTPClientTransport: new (url: URL) => unknown;
    };
    const client = new clientMod.Client({ name: "arctic-desktop", version: "0.1.0" });
    await client.connect(new httpMod.StreamableHTTPClientTransport(new URL(this.url)));
    this.client = client;
    return client;
  }
}

function textOf(content: unknown): string {
  if (!Array.isArray(content)) {
    return "";
  }
  const parts: string[] = [];
  for (const item of content) {
    if (typeof item !== "object" || item === null) {
      continue;
    }
    const part = item as { type?: unknown; text?: unknown };
    if (part.type === "text" && typeof part.text === "string") {
      parts.push(part.text);
    }
  }
  return parts.join("");
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
