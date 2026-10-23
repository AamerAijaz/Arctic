import { contextBridge, ipcRenderer } from "electron";
import type { HostBridge } from "./host-bridge";

const host: HostBridge = {
  loadConfig: () => ipcRenderer.invoke("host:load-config"),
  saveConfig: (settings) => ipcRenderer.invoke("host:save-config", settings),
  load: () => ipcRenderer.invoke("host:load"),
  start: () => ipcRenderer.invoke("host:start"),
  open: (id) => ipcRenderer.invoke("host:open", id),
  send: (message) => ipcRenderer.invoke("host:send", message),
  listMcpServers: () => ipcRenderer.invoke("host:mcp-list"),
  addMcpServer: (name, url) => ipcRenderer.invoke("host:mcp-add", { name, url }),
  removeMcpServer: (name) => ipcRenderer.invoke("host:mcp-remove", name),
};

contextBridge.exposeInMainWorld("host", host);
