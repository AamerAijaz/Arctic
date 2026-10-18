import { contextBridge, ipcRenderer } from "electron";
import type { HostBridge } from "./host-bridge";

const host: HostBridge = {
  setApiRoot: (apiRoot) => ipcRenderer.invoke("host:set-api-root", apiRoot),
  setModelName: (modelName) => ipcRenderer.invoke("host:set-model-name", modelName),
  setCredential: (credential) => ipcRenderer.invoke("host:set-credential", credential),
  send: (message) => ipcRenderer.invoke("host:send", message),
};

contextBridge.exposeInMainWorld("host", host);
