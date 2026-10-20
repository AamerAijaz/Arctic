import { contextBridge, ipcRenderer } from "electron";
import type { HostBridge } from "./host-bridge";

const host: HostBridge = {
  loadConfig: () => ipcRenderer.invoke("host:load-config"),
  saveConfig: (settings) => ipcRenderer.invoke("host:save-config", settings),
  send: (message) => ipcRenderer.invoke("host:send", message),
};

contextBridge.exposeInMainWorld("host", host);
