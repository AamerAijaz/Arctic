import type { ApiError } from "../types";

export function errorText(error: ApiError): string {
  switch (error.kind) {
    case "server_not_ready":
      return "The Arctic server is not ready yet.";
    case "server_unreachable":
      return "The Arctic server is not reachable.";
    case "unauthorized":
      return "The Arctic server rejected the app's session token.";
    default:
      return error.message;
  }
}

export default function ErrorText({ error }: { error: ApiError }) {
  return (
    <p className="error" role="alert">
      {errorText(error)}
    </p>
  );
}
