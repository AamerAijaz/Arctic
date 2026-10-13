import type { Loaded } from "../App";
import type { Health, SetupStatus } from "../types";
import { probeMessage } from "../probeMessage";
import ErrorText from "./ErrorText";

interface Props {
  health: Loaded<Health>;
  status: Loaded<SetupStatus>;
  onRefresh: () => void;
}

export default function StatusPanel({ health, status, onRefresh }: Props) {
  return (
    <section>
      <header>
        <h2>Status</h2>
        <button type="button" onClick={onRefresh}>
          Refresh
        </button>
      </header>
      <dl>
        <dt>Server</dt>
        <dd>
          {health.state === "loading" && "Checking…"}
          {health.state === "ok" && `${health.value.status} (version ${health.value.version})`}
          {health.state === "error" && <ErrorText error={health.error} />}
        </dd>
        {status.state === "ok" && <SetupDetails status={status.value} />}
        {status.state === "error" && health.state !== "error" && (
          <>
            <dt>Setup</dt>
            <dd>
              <ErrorText error={status.error} />
            </dd>
          </>
        )}
      </dl>
    </section>
  );
}

function SetupDetails({ status }: { status: SetupStatus }) {
  const probe = probeMessage(status.polarion);
  return (
    <>
      <dt>Polarion</dt>
      <dd>
        {status.polarionConfigured ? status.polarionUrl ?? "Configured" : "Not configured"}
        <div className={`probe ${probe.tone}`}>{probe.title}</div>
      </dd>
      <dt>Model</dt>
      <dd>
        {status.modelConfigured ? `${status.model ?? "?"} at ${status.modelBaseUrl ?? "?"}` : "Not configured"}
      </dd>
    </>
  );
}
