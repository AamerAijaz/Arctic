# Arctic

Arctic connects Polarion ALM to AI assistants through an MCP Server. Credentials stay outside the LLM; tools never accept or return secrets.

## Language

**MCP Server**:
The local process that exposes Polarion tools to hosts. A person starts it; hosts connect to it. The on-the-wire server name is `arctic`.
_Avoid_: the repo, a process each host launches

**Polarion Client**:
The component that speaks Polarion's REST API. Tools never see the personal access token.
_Avoid_: Polarion, HTTP client, REST client

**Polarion Credentials**:
The normalized REST root and personal access token supplied to the Polarion Client from outside it. Tools never accept or return them.
_Avoid_: secrets, config, connection settings

**Arctic CLI**:
The frozen command-line program. It is not part of the product surface.
_Avoid_: CLI, command, arctic when meaning the MCP Server

**Prototype**:
The frozen previous implementation, still the live MCP Server until the replacement exposes tools.
_Avoid_: snapshot, legacy, packages

**Tool**:
An MCP capability exposed to the host (`whoami`, `list_projects`, and the rest). Mutating tools default to `dry_run=true`.
_Avoid_: command, function, endpoint
