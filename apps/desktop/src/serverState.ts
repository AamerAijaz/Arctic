import type { ServerStatus } from "./types";

/** Keeps whichever status is newer; snapshots and events can arrive out of order. */
export function newerStatus(current: ServerStatus | null, incoming: ServerStatus): ServerStatus {
  return current !== null && current.seq >= incoming.seq ? current : incoming;
}

/** True when the server has just become ready, which is when setup data should be (re)loaded. */
export function becameReady(previous: ServerStatus | null, next: ServerStatus | null): boolean {
  return next?.state === "ready" && (previous?.state !== "ready" || previous.seq !== next.seq);
}

export function serverStateText(status: ServerStatus | null): string {
  switch (status?.state) {
    case undefined:
    case "starting":
      return "Starting the Arctic server…";
    case "ready":
      return `Running (version ${status.version})`;
    case "stopped":
      return "Stopped";
    case "failed":
      return "The Arctic server could not start.";
    default:
      return "Unknown";
  }
}
