import type { PolarionProbe } from "./types";

export type ProbeTone = "success" | "neutral" | "error";

export interface ProbeMessage {
  tone: ProbeTone;
  title: string;
  detail?: string;
}

export function probeMessage(probe: PolarionProbe): ProbeMessage {
  switch (probe.result) {
    case "ok": {
      const who = probe.userName && probe.userId ? `${probe.userName} (${probe.userId})` : (probe.userName ?? probe.userId);
      return { tone: "success", title: who ? `Connected to Polarion as ${who}.` : "Connected to Polarion." };
    }
    case "not_configured":
      return { tone: "neutral", title: "Polarion is not configured yet.", detail: "Enter the Polarion URL and a personal access token." };
    case "rejected_token":
      return {
        tone: "error",
        title: "Polarion rejected the token.",
        detail: "Create a new personal access token in Polarion and enter it here.",
      };
    case "rest_disabled":
      return {
        tone: "error",
        title: "The Polarion REST API is disabled.",
        detail: "A Polarion administrator must set com.siemens.polarion.rest.enabled=true.",
      };
    case "network_error":
      return {
        tone: "error",
        title: "Could not reach Polarion.",
        detail: "Check the URL and your VPN connection.",
      };
    default:
      return { tone: "error", title: "Unexpected probe result.", detail: probe.message };
  }
}
