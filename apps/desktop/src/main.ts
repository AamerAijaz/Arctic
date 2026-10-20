import { app, BrowserWindow, ipcMain } from "electron";
import path from "node:path";
import { Host } from "./host";
import { HostConfigFile, type HostConfig } from "./host-config";
import { OpenAiModel } from "./openai-model";

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asConfig(value: unknown): HostConfig {
  const record = typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
  return {
    apiRoot: text(record.apiRoot),
    model: text(record.model),
    apiKey: text(record.apiKey),
  };
}

function createWindow(): void {
  const window = new BrowserWindow({
    width: 1100,
    height: 760,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  void window.loadFile(path.join(__dirname, "index.html"));
}

void app.whenReady().then(() => {
  const config = new HostConfigFile(app.getPath("userData"));
  const host = new Host(new OpenAiModel());
  const saved = config.read();
  if (saved.apiRoot.trim() !== "") {
    host.setApiRoot(saved.apiRoot);
  }
  if (saved.model.trim() !== "") {
    host.setModelName(saved.model);
  }
  if (saved.apiKey.trim() !== "") {
    host.setCredential(saved.apiKey);
  }

  ipcMain.handle("host:load-config", () => config.read());

  ipcMain.handle("host:save-config", (_event, value: unknown) => {
    config.write(asConfig(value));
    const next = config.read();
    host.setApiRoot(next.apiRoot);
    host.setModelName(next.model);
    if (next.apiKey.trim() !== "") {
      host.setCredential(next.apiKey);
    }
    return next;
  });

  ipcMain.handle("host:send", async (_event, message: unknown) => {
    await host.send(text(message));
    return host.transcript();
  });

  createWindow();
});

app.on("window-all-closed", () => {
  app.quit();
});
