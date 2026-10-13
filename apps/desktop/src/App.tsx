import { useCallback, useEffect, useRef, useState } from "react";
import { getHealth, getServerState, getSetupStatus, onServerState } from "./api";
import { becameReady, newerStatus } from "./serverState";
import type { ApiError, Health, ServerStatus, SetupStatus } from "./types";
import ServerPanel from "./components/ServerPanel";
import StatusPanel from "./components/StatusPanel";
import PolarionForm from "./components/PolarionForm";
import ModelForm from "./components/ModelForm";

export type Loaded<T> = { state: "loading" } | { state: "ok"; value: T } | { state: "error"; error: ApiError };

async function load<T>(fn: () => Promise<T>): Promise<Loaded<T>> {
  try {
    return { state: "ok", value: await fn() };
  } catch (err) {
    return { state: "error", error: err as ApiError };
  }
}

export default function App() {
  const [server, setServer] = useState<ServerStatus | null>(null);
  const [health, setHealth] = useState<Loaded<Health>>({ state: "loading" });
  const [status, setStatus] = useState<Loaded<SetupStatus>>({ state: "loading" });
  const previous = useRef<ServerStatus | null>(null);
  const ready = server?.state === "ready";

  const refresh = useCallback(async () => {
    setHealth({ state: "loading" });
    setStatus({ state: "loading" });
    const [h, s] = await Promise.all([load(getHealth), load(getSetupStatus)]);
    setHealth(h);
    setStatus(s);
  }, []);

  useEffect(() => {
    let disposed = false;
    const apply = (incoming: ServerStatus) => {
      if (!disposed) setServer((current) => newerStatus(current, incoming));
    };
    // Subscribe before taking the snapshot so no transition falls in between.
    const unlisten = onServerState(apply);
    void unlisten.then(() => getServerState()).then(apply, () => undefined);
    return () => {
      disposed = true;
      void unlisten.then((fn) => fn());
    };
  }, []);

  useEffect(() => {
    if (becameReady(previous.current, server)) void refresh();
    previous.current = server;
  }, [server, refresh]);

  return (
    <main>
      <h1>Arctic</h1>
      <ServerPanel status={server} />
      {ready && <StatusPanel health={health} status={status} onRefresh={refresh} />}
      <PolarionForm onSaved={refresh} disabled={!ready} />
      <ModelForm onSaved={refresh} disabled={!ready} />
    </main>
  );
}
