export type TranscriptEntry =
  | { role: "person"; text: string }
  | { role: "assistant"; text: string }
  | { role: "failure"; text: string };

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

export class Host {
  private apiRoot = "";
  private modelName = "";
  private credential = "";
  private saveFailed = false;
  private readonly entries: TranscriptEntry[] = [];

  constructor(
    private readonly model: Model,
    private readonly secrets?: SecretStore,
  ) {
    this.credential = this.secrets?.read() ?? "";
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
    this.entries.push({ role: "person", text: message });
    if (this.saveFailed) {
      this.saveFailed = false;
      this.entries.push({ role: "failure", text: "The Host could not save the credential." });
      return;
    }
    if (this.credential.trim() === "") {
      this.entries.push({ role: "failure", text: "The model credential is missing." });
      return;
    }
    const result = await this.model.complete({
      apiRoot: this.apiRoot,
      modelName: this.modelName,
      credential: this.credential,
      messages: this.entries.flatMap((entry) =>
        entry.role === "failure" ? [] : [{ role: entry.role, text: entry.text }],
      ),
    });
    if (result.kind === "rejected") {
      this.entries.push({ role: "failure", text: "The model rejected the credential." });
      return;
    }
    if (result.kind === "unreachable") {
      this.entries.push({ role: "failure", text: "The model could not be reached." });
      return;
    }
    if (result.kind === "tool_call") {
      this.entries.push({
        role: "failure",
        text: "The model asked for a tool, and this Host cannot run tools.",
      });
      return;
    }
    this.entries.push({ role: "assistant", text: result.text });
  }

  transcript(): TranscriptEntry[] {
    return this.entries.map((entry) => ({ ...entry }));
  }
}
