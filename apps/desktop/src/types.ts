// Mirrors components/schemas in server/local-app/api/openapi.yaml.

export interface Health {
  status: "ready";
  version: string;
}

export type ProbeResult = "ok" | "not_configured" | "rejected_token" | "rest_disabled" | "network_error";

export interface PolarionProbe {
  result: ProbeResult;
  userId?: string;
  userName?: string;
  message?: string;
}

export interface SetupStatus {
  polarionConfigured: boolean;
  polarionUrl?: string;
  polarion: PolarionProbe;
  modelConfigured: boolean;
  modelBaseUrl?: string;
  model?: string;
}

export interface PolarionSettings {
  url: string;
  token: string;
}

export interface ModelSettings {
  baseUrl: string;
  model: string;
  apiKey: string;
}

// Mirrors ServerStatus in src-tauri/src/supervisor/state.rs. `seq` grows with every transition.
export type ServerState =
  | { state: "stopped" }
  | { state: "starting" }
  | { state: "ready"; version: string }
  | { state: "failed"; reason: string };

export type ServerStatus = ServerState & { seq: number };

export type ApiErrorKind =
  | "server_not_ready"
  | "server_unreachable"
  | "unauthorized"
  | "bad_request"
  | "server_error"
  | "invalid_response"
  | "config"
  | "internal";

export interface ApiError {
  kind: ApiErrorKind | string;
  message: string;
}
