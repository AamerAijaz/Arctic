import type { TranscriptEntry } from "./host";

export type HostSettings = {
  apiRoot: string;
  model: string;
  apiKey: string;
};

export type HostBridge = {
  loadConfig(): Promise<HostSettings>;
  saveConfig(settings: HostSettings): Promise<HostSettings>;
  send(message: string): Promise<TranscriptEntry[]>;
};
