"""stdio MCP server. Credentials come from the process environment, not from tools."""

from __future__ import annotations

import asyncio
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
    """Send diagnostics to stderr so stdio MCP JSON stays on stdout.

    Cursor tags every stderr line as an MCP error, including FastMCP INFO
    banners. Keep the default quiet; set ARCTIC_LOG_LEVEL=INFO to debug.
    """
    level_name = os.environ.get("ARCTIC_LOG_LEVEL", "WARNING").upper()
    level = getattr(logging, level_name, logging.WARNING)
    logging.basicConfig(
        level=level,
        format="%(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
        force=True,
    )
    logging.getLogger("fastmcp").setLevel(level)
    logging.getLogger("mcp").setLevel(max(level, logging.WARNING))


def main() -> None:
    os.environ.setdefault("FASTMCP_SHOW_SERVER_BANNER", "false")
    os.environ.setdefault("FASTMCP_LOG_LEVEL", "WARNING")
    _configure_logging()
    mcp = build_server()
    tools = asyncio.run(mcp.list_tools())
    names = sorted(getattr(tool, "name", "") for tool in tools)
    logging.getLogger("mcp_polarion").debug(
        "Starting arctic MCP with %d tools: %s",
        len(names),
        ", ".join(names),
    )
    mcp.run(show_banner=False)


if __name__ == "__main__":
    main()
