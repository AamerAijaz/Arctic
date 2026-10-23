import fs from "node:fs";
import path from "node:path";

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type JsonObject = { [key: string]: Json };

export type StoredEntry =
  | { role: "person"; text: string }
  | { role: "assistant"; text: string }
  | { role: "failure"; text: string }
  | {
      role: "tool";
      callId: string;
      name: string;
      arguments: JsonObject | null;
      status: "ran" | "failed" | "held";
      text: string;
    };

export type ConversationRecord = {
  id: string;
  entries: StoredEntry[];
};

export type ConversationSnapshot = {
  openId: string | null;
  conversations: ConversationRecord[];
};

export interface ConversationStore {
  read(): ConversationSnapshot;
  write(next: ConversationSnapshot): void;
}

const empty: ConversationSnapshot = { openId: null, conversations: [] };

export class FileConversationStore implements ConversationStore {
  constructor(private readonly directory: string) {}

  read(): ConversationSnapshot {
    const file = path.join(this.directory, "conversations.json");
    if (!fs.existsSync(file)) {
      return { openId: null, conversations: [] };
    }
    let parsed: unknown;
    try {
      const raw = fs.readFileSync(file, "utf8");
      if (raw.trim() === "") {
        return { openId: null, conversations: [] };
      }
      parsed = JSON.parse(raw);
    } catch {
      return { openId: null, conversations: [] };
    }
    return normalize(parsed);
  }

  write(next: ConversationSnapshot): void {
    const file = path.join(this.directory, "conversations.json");
    const temporary = path.join(this.directory, "conversations.json.tmp");
    fs.mkdirSync(this.directory, { recursive: true });
    fs.writeFileSync(temporary, JSON.stringify(normalize(next), null, 2));
    fs.renameSync(temporary, file);
  }
}

function normalize(value: unknown): ConversationSnapshot {
  if (typeof value !== "object" || value === null) {
    return { ...empty, conversations: [] };
  }
  const record = value as Record<string, unknown>;
  const conversations = Array.isArray(record.conversations)
    ? record.conversations.flatMap(normalizeConversation)
    : [];
  const openId = typeof record.openId === "string" ? record.openId : null;
  return {
    openId: conversations.some((conversation) => conversation.id === openId) ? openId : null,
    conversations,
  };
}

function normalizeConversation(value: unknown): ConversationRecord[] {
  if (typeof value !== "object" || value === null) {
    return [];
  }
  const record = value as Record<string, unknown>;
  if (typeof record.id !== "string" || record.id.trim() === "") {
    return [];
  }
  const entries = Array.isArray(record.entries) ? record.entries.flatMap(normalizeEntry) : [];
  return [{ id: record.id, entries }];
}

function normalizeEntry(value: unknown): StoredEntry[] {
  if (typeof value !== "object" || value === null) {
    return [];
  }
  const record = value as Record<string, unknown>;
  if (record.role === "tool") {
    if (typeof record.callId !== "string" || typeof record.name !== "string" || typeof record.text !== "string") {
      return [];
    }
    if (record.status !== "ran" && record.status !== "failed" && record.status !== "held") {
      return [];
    }
    if (record.arguments !== null && !isPlainObject(record.arguments)) {
      return [];
    }
    return [
      {
        role: "tool",
        callId: record.callId,
        name: record.name,
        arguments: record.arguments,
        status: record.status,
        text: record.text,
      },
    ];
  }
  if (typeof record.text !== "string") {
    return [];
  }
  if (record.role === "person" || record.role === "assistant" || record.role === "failure") {
    return [{ role: record.role, text: record.text }];
  }
  return [];
}

function isPlainObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
