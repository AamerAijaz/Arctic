import type { HostView } from "./host";

export type HostSettings = {
  apiRoot: string;
  model: string;
  apiKey: string;
};

export type McpSettingsView = {
  servers: {
    name: string;
    url: string;
    label: string;
    detail: string;
  }[];
  error: string | null;
};

export type HostBridge = {
  loadConfig(): Promise<HostSettings>;
  saveConfig(settings: HostSettings): Promise<HostSettings>;
  load(): Promise<HostView>;
  start(): Promise<HostView>;
  open(id: string): Promise<HostView>;
  send(message: string): Promise<HostView>;
  listMcpServers(): Promise<McpSettingsView>;
  addMcpServer(name: string, url: string): Promise<McpSettingsView>;
  removeMcpServer(name: string): Promise<McpSettingsView>;
};
