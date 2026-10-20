import fs from "node:fs";
import path from "node:path";

export type HostConfig = {
  apiRoot: string;
  model: string;
  apiKey: string;
};

const empty: HostConfig = { apiRoot: "", model: "", apiKey: "" };

export class HostConfigFile {
  constructor(private readonly directory: string) {}

  read(): HostConfig {
    const file = path.join(this.directory, "config.json");
    if (!fs.existsSync(file)) {
      return { ...empty };
    }
    let parsed: unknown;
    try {
      const raw = fs.readFileSync(file, "utf8");
      if (raw.trim() === "") {
        return { ...empty };
      }
      parsed = JSON.parse(raw);
    } catch {
      return { ...empty };
    }
    if (typeof parsed !== "object" || parsed === null) {
      return { ...empty };
    }
    const record = parsed as Record<string, unknown>;
    return {
      apiRoot: typeof record.apiRoot === "string" ? record.apiRoot : "",
      model: typeof record.model === "string" ? record.model : "",
      apiKey: typeof record.apiKey === "string" ? record.apiKey : "",
    };
  }

  write(next: HostConfig): void {
    const current = this.read();
    const saved: HostConfig = {
      apiRoot: next.apiRoot,
      model: next.model,
      apiKey: next.apiKey.trim() === "" ? current.apiKey : next.apiKey,
    };
    fs.mkdirSync(this.directory, { recursive: true });
    fs.writeFileSync(path.join(this.directory, "config.json"), JSON.stringify(saved, null, 2));
  }
}
