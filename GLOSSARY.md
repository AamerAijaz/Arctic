# Arctic

Arctic connects Polarion ALM to AI assistants through an MCP Server. Credentials stay outside the LLM; tools never accept or return secrets.

## Language

**MCP Server**:
The stdio process a host such as Cursor launches so an assistant can call Polarion tools. The on-the-wire server name is `arctic`.
_Avoid_: the repo, packages repository

**Polarion Client**:
The component that speaks Polarion's REST API. Tools never see the personal access token.
_Avoid_: Polarion, HTTP client, REST client

**Arctic CLI**:
The frozen command-line program. It is not part of the product surface.
_Avoid_: CLI, command, arctic when meaning the MCP Server

**Prototype**:
The frozen previous implementation, still the live MCP Server until the replacement exposes tools.
_Avoid_: snapshot, legacy, packages

**Tool**:
An MCP capability exposed to the host (`whoami`, `list_projects`, and the rest). Mutating tools default to `dry_run=true`.
_Avoid_: command, function, endpoint
