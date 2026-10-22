import { randomUUID } from "node:crypto";
import type { ConversationRecord, ConversationStore } from "./conversation-store";

export type TranscriptEntry =
  | { role: "person"; text: string }
  | { role: "assistant"; text: string }
  | { role: "failure"; text: string };

export type ConversationSummary = {
  id: string;
  title: string;
  open: boolean;
};

export type HostView = {
  conversations: ConversationSummary[];
  transcript: TranscriptEntry[];
};

export type ConversationTurn = Exclude<TranscriptEntry, { role: "failure" }>;

export type ModelCall = {
  apiRoot: string;
  modelName: string;
  credential: string;
  messages: ConversationTurn[];
};

export type ModelResult =
  | { kind: "assistant"; text: string }
  | { kind: "rejected" }
  | { kind: "unreachable" }
  | { kind: "tool_call" };

export interface Model {
  complete(call: ModelCall): Promise<ModelResult>;
}

export type SecretSave = { ok: true } | { ok: false };

export interface SecretStore {
  read(): string;
  replace(credential: string): SecretSave;
}

const titleLimit = 60;

export class Host {
  private apiRoot = "";
  private modelName = "";
  private credential = "";
  private saveFailed = false;
  private openId: string | null = null;
  private records: ConversationRecord[] = [];

  constructor(
    private readonly model: Model,
    private readonly secrets?: SecretStore,
    private readonly conversationStore?: ConversationStore,
  ) {
    this.credential = this.secrets?.read() ?? "";
    const saved = this.conversationStore?.read();
    if (saved) {
      this.openId = saved.openId;
      this.records = saved.conversations;
    }
  }

  setApiRoot(apiRoot: string): void {
    this.apiRoot = apiRoot;
  }

  setModelName(modelName: string): void {
    this.modelName = modelName;
  }

  setCredential(credential: string): void {
    if (credential.trim() === "") {
      return;
    }
    const saved = this.secrets?.replace(credential) ?? { ok: true };
    if (!saved.ok) {
      this.saveFailed = true;
      return;
    }
    this.saveFailed = false;
    this.credential = credential;
  }

  hasCredential(): boolean {
    return this.credential.trim() !== "";
  }

  async send(message: string): Promise<void> {
    if (message.trim() === "") {
      return;
    }
    const open = this.ensureOpen();
    open.entries.push({ role: "person", text: message });
    this.persist();
    if (this.saveFailed) {
      this.saveFailed = false;
      open.entries.push({ role: "failure", text: "The Host could not save the credential." });
      this.persist();
      return;
    }
    if (this.credential.trim() === "") {
      open.entries.push({ role: "failure", text: "The model credential is missing." });
      this.persist();
      return;
    }
    const result = await this.model.complete({
      apiRoot: this.apiRoot,
      modelName: this.modelName,
      credential: this.credential,
      messages: open.entries.flatMap((entry) =>
        entry.role === "failure" ? [] : [{ role: entry.role, text: entry.text }],
      ),
    });
    if (result.kind === "rejected") {
      open.entries.push({ role: "failure", text: "The model rejected the credential." });
      this.persist();
      return;
    }
    if (result.kind === "unreachable") {
      open.entries.push({ role: "failure", text: "The model could not be reached." });
      this.persist();
      return;
    }
    if (result.kind === "tool_call") {
      open.entries.push({
        role: "failure",
        text: "The model asked for a tool, and this Host cannot run tools.",
      });
      this.persist();
      return;
    }
    open.entries.push({ role: "assistant", text: result.text });
    this.persist();
  }

  start(): void {
    const open = this.records.find((record) => record.id === this.openId);
    if (open && open.entries.length === 0) {
      return;
    }
    const created: ConversationRecord = { id: randomUUID(), entries: [] };
    this.records.push(created);
    this.openId = created.id;
    this.persist();
  }

  open(id: string): void {
    if (!this.records.some((record) => record.id === id)) {
      return;
    }
    this.openId = id;
    this.persist();
  }

  transcript(): TranscriptEntry[] {
    return this.openEntries().map((entry) => ({ ...entry }));
  }

  view(): HostView {
    return {
      conversations: this.records.map((record) => ({
        id: record.id,
        title: conversationTitle(record),
        open: record.id === this.openId,
      })),
      transcript: this.transcript(),
    };
  }

  private ensureOpen(): ConversationRecord {
    const existing = this.records.find((record) => record.id === this.openId);
    if (existing) {
      return existing;
    }
    const created: ConversationRecord = { id: randomUUID(), entries: [] };
    this.records.push(created);
    this.openId = created.id;
    return created;
  }

  private openEntries(): TranscriptEntry[] {
    return this.records.find((record) => record.id === this.openId)?.entries ?? [];
  }

  private persist(): void {
    this.conversationStore?.write({ openId: this.openId, conversations: this.records });
  }
}

function conversationTitle(record: ConversationRecord): string {
  const person = record.entries.find((entry) => entry.role === "person");
  if (!person) {
    return "New conversation";
  }
  const trimmed = person.text.trim().replace(/\s+/g, " ");
  if (trimmed.length <= titleLimit) {
    return trimmed;
  }
  return trimmed.slice(0, titleLimit).trimEnd();
}
