import { useCallback, useEffect, useState } from "react";
import { getServerLogs, restartServer, toApiError } from "../api";
import { serverStateText } from "../serverState";
import type { ApiError, ServerStatus } from "../types";
import ErrorText from "./ErrorText";

export default function ServerPanel({ status }: { status: ServerStatus | null }) {
  const [logsOpen, setLogsOpen] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);
  const [error, setError] = useState<ApiError | null>(null);

  const loadLogs = useCallback(async () => {
    try {
      setLogs(await getServerLogs());
    } catch (err) {
      setError(toApiError(err));
    }
  }, []);

  useEffect(() => {
    if (logsOpen) void loadLogs();
  }, [logsOpen, status?.seq, loadLogs]);

  async function retry() {
    setError(null);
    try {
      await restartServer();
    } catch (err) {
      setError(toApiError(err));
    }
  }

  const starting = status === null || status.state === "starting";

  return (
    <section>
      <header>
        <h2>Server</h2>
        {(status?.state === "failed" || status?.state === "stopped") && (
          <button type="button" onClick={retry}>
            Retry
          </button>
        )}
      </header>
      <p className={`server-state ${status?.state ?? "starting"}`} role="status">
        {starting && <span className="spinner" aria-hidden="true" />}
        {serverStateText(status)}
      </p>
      {status?.state === "failed" && <p className="error">{status.reason}</p>}
      {error && <ErrorText error={error} />}
      <details onToggle={(e) => setLogsOpen(e.currentTarget.open)}>
        <summary>Server logs</summary>
        <button type="button" className="small" onClick={loadLogs}>
          Refresh logs
        </button>
        <pre className="logs">{logs.length > 0 ? logs.join("\n") : "No output yet."}</pre>
      </details>
    </section>
  );
}
