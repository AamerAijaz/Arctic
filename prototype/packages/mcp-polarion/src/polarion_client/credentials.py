"""Where the Polarion URL and personal access token come from."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse, urlunparse

from polarion_client.errors import MissingCredentialsError

_MISSING = (
    "Set POLARION_URL and POLARION_TOKEN. "
    "Create the token in Polarion under My Account > Personal Access Tokens."
)


@dataclass(frozen=True)
class PolarionCredentials:
    """Connection settings for one Polarion REST client."""

    rest_root: str
    token: str


class EnvCredentialProvider:
    """Read POLARION_URL and POLARION_TOKEN from the environment or a local .env file."""

    def get(self) -> PolarionCredentials:
        load_dotenv()
        raw_url = os.environ.get("POLARION_URL", "").strip()
        token = os.environ.get("POLARION_TOKEN", "").strip()
        if not raw_url or not token:
            raise MissingCredentialsError(_MISSING)
        return PolarionCredentials(rest_root=normalize_rest_root(raw_url), token=token)


def load_dotenv(path: Path | None = None) -> None:
    """Load KEY=VALUE lines into the environment without overriding existing values."""
    env_file = path or Path.cwd() / ".env"
    if not env_file.is_file():
        return
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            os.environ.setdefault(key, value)


def normalize_rest_root(url: str) -> str:
    """Return ``https://host/polarion/rest/v1`` from common Polarion URL forms."""
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise MissingCredentialsError(
            "POLARION_URL must be an http(s) URL of the Polarion server."
        )
    path = parsed.path.rstrip("/")
    if path.endswith("/polarion/rest/v1"):
        rest_path = path
    elif path.endswith("/polarion"):
        rest_path = f"{path}/rest/v1"
    elif path.endswith("/rest/v1"):
        rest_path = path
    elif path in {"", "/"}:
        rest_path = "/polarion/rest/v1"
    else:
        rest_path = f"{path}/polarion/rest/v1"
    return urlunparse((parsed.scheme, parsed.netloc, rest_path, "", "", ""))
