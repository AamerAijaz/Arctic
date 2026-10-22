import type { HostView } from "./host";

export type HostSettings = {
  apiRoot: string;
  model: string;
  apiKey: string;
};

export type HostBridge = {
  loadConfig(): Promise<HostSettings>;
  saveConfig(settings: HostSettings): Promise<HostSettings>;
  load(): Promise<HostView>;
  start(): Promise<HostView>;
  open(id: string): Promise<HostView>;
  send(message: string): Promise<HostView>;
};
