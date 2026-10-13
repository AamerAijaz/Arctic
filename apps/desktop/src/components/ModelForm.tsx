import { useState, type FormEvent } from "react";
import { putModel } from "../api";
import type { ApiError } from "../types";
import ErrorText from "./ErrorText";

export default function ModelForm({ onSaved, disabled = false }: { onSaved: () => void; disabled?: boolean }) {
  const [baseUrl, setBaseUrl] = useState("https://openrouter.ai/api/v1");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await putModel({ baseUrl: baseUrl.trim(), model: model.trim(), apiKey });
      setApiKey("");
      setSaved(true);
      onSaved();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>Model</h2>
      <form onSubmit={submit}>
        <label>
          Base URL
          <input type="url" required value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </label>
        <label>
          Model
          <input
            type="text"
            required
            placeholder="anthropic/claude-sonnet-4.5"
            value={model}
            onChange={(e) => setModel(e.target.value)}
          />
        </label>
        <label>
          API key
          <input
            type="password"
            required
            autoComplete="off"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
          />
        </label>
        <button type="submit" disabled={busy || disabled}>
          {busy ? "Saving…" : "Save"}
        </button>
      </form>
      {saved && (
        <div className="probe success" role="status">
          Model settings saved.
        </div>
      )}
      {error && <ErrorText error={error} />}
    </section>
  );
}
