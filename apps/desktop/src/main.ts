import { app, BrowserWindow, ipcMain } from "electron";
import path from "node:path";
import { Host } from "./host";
import { OpenAiModel } from "./openai-model";

const host = new Host(new OpenAiModel());

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
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

ipcMain.handle("host:set-api-root", (_event, apiRoot: unknown) => {
  host.setApiRoot(text(apiRoot));
});

ipcMain.handle("host:set-model-name", (_event, modelName: unknown) => {
  host.setModelName(text(modelName));
});

ipcMain.handle("host:set-credential", (_event, credential: unknown) => {
  host.setCredential(text(credential));
});

ipcMain.handle("host:send", async (_event, message: unknown) => {
  await host.send(text(message));
  return host.transcript();
});

void app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  app.quit();
});
