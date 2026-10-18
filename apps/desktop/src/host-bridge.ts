import type { TranscriptEntry } from "./host";

export type HostBridge = {
  setApiRoot(apiRoot: string): Promise<void>;
  setModelName(modelName: string): Promise<void>;
  setCredential(credential: string): Promise<void>;
  send(message: string): Promise<TranscriptEntry[]>;
};
