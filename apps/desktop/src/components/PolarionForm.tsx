import { useState, type FormEvent } from "react";
import { putPolarion } from "../api";
import { probeMessage } from "../probeMessage";
import type { ApiError, PolarionProbe } from "../types";
import ErrorText from "./ErrorText";

export default function PolarionForm({ onSaved, disabled = false }: { onSaved: () => void; disabled?: boolean }) {
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [probe, setProbe] = useState<PolarionProbe | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setProbe(null);
    try {
      const result = await putPolarion({ url: url.trim(), token });
      setProbe(result);
      setToken("");
      onSaved();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }

  const message = probe ? probeMessage(probe) : null;

  return (
    <section>
      <h2>Polarion</h2>
      <form onSubmit={submit}>
        <label>
          URL
          <input
            type="url"
            required
            placeholder="https://polarion.example.com/polarion"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
        </label>
        <label>
          Personal access token
          <input
            type="password"
            required
            autoComplete="off"
            value={token}
            onChange={(e) => setToken(e.target.value)}
          />
        </label>
        <button type="submit" disabled={busy || disabled}>
          {busy ? "Checking…" : "Save and test"}
        </button>
      </form>
      {message && (
        <div className={`probe ${message.tone}`} role="status">
          <strong>{message.title}</strong>
          {message.detail && <p>{message.detail}</p>}
        </div>
      )}
      {error && <ErrorText error={error} />}
    </section>
  );
}
