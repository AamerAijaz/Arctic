"""stdio MCP server. Credentials come from the process environment, not from tools."""

from __future__ import annotations

from fastmcp import FastMCP

from mcp_polarion.tools.auth import ClientFactory, register_auth_tools
from mcp_polarion.tools.links import register_link_tools
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
    return mcp


def main() -> None:
    build_server().run()


if __name__ == "__main__":
    main()
