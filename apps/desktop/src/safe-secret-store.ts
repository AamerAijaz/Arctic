import { app, safeStorage } from "electron";
import fs from "node:fs";
import path from "node:path";
import type { SecretSave, SecretStore } from "./host";

export class SafeSecretStore implements SecretStore {
  private file(): string {
    return path.join(app.getPath("userData"), "llm-credential.bin");
  }

  read(): string {
    const file = this.file();
    if (!safeStorage.isEncryptionAvailable() || !fs.existsSync(file)) {
      return "";
    }
    try {
      return safeStorage.decryptString(fs.readFileSync(file));
    } catch {
      return "";
    }
  }

  replace(credential: string): SecretSave {
    if (!safeStorage.isEncryptionAvailable()) {
      return { ok: false };
    }
    try {
      fs.mkdirSync(path.dirname(this.file()), { recursive: true });
      fs.writeFileSync(this.file(), safeStorage.encryptString(credential));
      return { ok: true };
    } catch {
      return { ok: false };
    }
  }
}
