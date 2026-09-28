"""Identity tools. These never accept or return a Polarion token."""

from __future__ import annotations

from collections.abc import Callable

from fastmcp import FastMCP

from polarion_client.client import PolarionClient
from polarion_client.errors import PolarionError

ClientFactory = Callable[[], PolarionClient]


def whoami_payload(client: PolarionClient) -> dict[str, str | None]:
    """Return the current Polarion user. Raises PolarionError on failure."""
    return client.get_current_user().to_dict()


def register_auth_tools(mcp: FastMCP, client_factory: ClientFactory) -> None:
    @mcp.tool
    def whoami() -> dict[str, str | None]:
        """Return the Polarion user for the configured personal access token.

        Call this before creating or changing Polarion data so you know which
        user the action will run as. This tool does not take a token.
        """
        try:
            return whoami_payload(client_factory())
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
