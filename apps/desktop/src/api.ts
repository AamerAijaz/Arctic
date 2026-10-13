import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  ApiError,
  Health,
  ModelSettings,
  PolarionProbe,
  PolarionSettings,
  ServerStatus,
  SetupStatus,
} from "./types";

export const SERVER_STATE_EVENT = "server-state";

function isApiError(value: unknown): value is ApiError {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as ApiError).kind === "string" &&
    typeof (value as ApiError).message === "string"
  );
}

export function toApiError(value: unknown): ApiError {
  if (isApiError(value)) return value;
  return { kind: "internal", message: typeof value === "string" ? value : "Unexpected error" };
}

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  try {
    return await invoke<T>(command, args);
  } catch (err) {
    throw toApiError(err);
  }
}

export function getServerState(): Promise<ServerStatus> {
  return call("get_server_state");
}

export function getServerLogs(): Promise<string[]> {
  return call("get_server_logs");
}

export function restartServer(): Promise<void> {
  return call("restart_server");
}

export function onServerState(handler: (status: ServerStatus) => void): Promise<UnlistenFn> {
  return listen<ServerStatus>(SERVER_STATE_EVENT, (event) => handler(event.payload));
}

export function getHealth(): Promise<Health> {
  return call("get_health");
}

export function getSetupStatus(): Promise<SetupStatus> {
  return call("get_setup_status");
}

export function putPolarion(settings: PolarionSettings): Promise<PolarionProbe> {
  return call("put_polarion", { url: settings.url, token: settings.token });
}

export function putModel(settings: ModelSettings): Promise<void> {
  return call("put_model", { baseUrl: settings.baseUrl, model: settings.model, apiKey: settings.apiKey });
}
