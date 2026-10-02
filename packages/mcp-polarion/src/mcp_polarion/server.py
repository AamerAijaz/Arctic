"""stdio MCP server. Credentials come from the process environment, not from tools."""

from __future__ import annotations

import logging
import os
import sys

from fastmcp import FastMCP

from mcp_polarion.tools.auth import ClientFactory, register_auth_tools
from mcp_polarion.tools.documents import register_document_tools
from mcp_polarion.tools.links import register_link_tools
from mcp_polarion.tools.users import register_user_tools
from mcp_polarion.tools.workitems import register_workitem_tools
from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider


def default_client_factory() -> PolarionClient:
    return PolarionClient(EnvCredentialProvider())


def build_server(
    client_factory: ClientFactory | None = None,
) -> FastMCP:
    factory = client_factory or default_client_factory
    mcp = FastMCP(name="arctic")
    register_auth_tools(mcp, factory)
    register_workitem_tools(mcp, factory)
    register_link_tools(mcp, factory)
    register_document_tools(mcp, factory)
    register_user_tools(mcp, factory)
    return mcp


def _configure_logging() -> None:
    """Send client diagnostics to stderr so stdio MCP JSON stays on stdout."""
    level_name = os.environ.get("ARCTIC_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    logging.basicConfig(
        level=level,
        format="%(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
        force=True,
    )


def main() -> None:
    _configure_logging()
    build_server().run()


if __name__ == "__main__":
    main()
