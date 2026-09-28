# Arctic

Arctic connects Polarion ALM to AI assistants through a custom [Model Context Protocol](https://modelcontextprotocol.io) server and a small CLI. Authentication uses a Polarion personal access token (PAT) configured outside the LLM; tools never accept or return secrets.

Today the stack supports:

- **`whoami`** — verify REST access and show the Polarion user for your token
- **`list_projects` / `get_project`** — discover Polarion project ids
- **`create_work_item`** — create one work item (`dry_run` by default)
- **`get_work_item`** — read a work item back after create

## Repository layout

| Path | Role |
|------|------|
| [`packages/mcp-polarion`](packages/mcp-polarion) | MCP server (`mcp-polarion`) and Polarion REST client |
| [`packages/arctic`](packages/arctic) | CLI (`arctic`) |
| [`.mcp.json`](.mcp.json) | Example Cursor MCP config (stdio) |
| [`.env.example`](.env.example) | Template for local credentials |

## Prerequisites

- **[uv](https://docs.astral.sh/uv/)** — installs Python 3.12 and project dependencies
- A **Polarion** instance with the **REST API enabled**
- A **personal access token** for a Polarion user who can access the projects you care about

### Enable Polarion REST API

An administrator sets in `polarion.properties`:

```properties
com.siemens.polarion.rest.enabled=true
```

Restart Polarion after changing this file.

To confirm REST is available, open in a browser:

`https://<your-host>/polarion/rest/v1/projects/`

- **401 Unauthorized** — REST is up (you are not sending a token in the browser).
- **503** — REST is disabled or the service is unavailable.

In a cluster, use the load balancer / public **base URL**, not an individual node URL.

### Create a personal access token

1. Sign in to Polarion.
2. Open **My Account** → **Personal Access Tokens**.
3. Create a token and copy it (you may not see the full value again).

API calls run as that user (permissions, author, audit). Prefer each person’s own PAT rather than a shared service account unless you intend that.

## Install

From the repository root:

```bash
uv sync --all-packages --group dev
```

This creates `.venv` (Python 3.12+) and installs `mcp-polarion`, `arctic`, and dev tools such as pytest.

## Configure credentials

Copy the example env file and edit it:

```bash
cp .env.example .env
```

Set:

```env
POLARION_URL=https://your-polarion-host.example.com
POLARION_TOKEN=your-personal-access-token
```

`POLARION_URL` may be the server origin or a path that includes `/polarion` or `/polarion/rest/v1`; the client normalizes it to `https://<host>/polarion/rest/v1`.

Do not commit `.env` (it is gitignored). Shell environment variables override values from `.env` if both are set.

Run commands from the **repository root** so `.env` is found, or export `POLARION_URL` and `POLARION_TOKEN` in your shell.

## Verify with the CLI

```bash
uv run arctic whoami
uv run arctic projects
uv run arctic project ELK
uv run arctic create-work-item --project ELK --type task --title "Fix login"
uv run arctic create-work-item --project ELK --type task --title "Fix login" --apply
uv run arctic work-item ELK ELK-42
```

`create-work-item` is a **dry run** unless you pass `--apply`. `--type` is the Polarion work-item type id (`task`, `defect`, and so on), which must exist in that project.

On success `whoami` prints:

```text
id: <polarionUserId>
name: ...
email: ...
```

| Message | Likely cause |
|---------|----------------|
| Set `POLARION_URL` and `POLARION_TOKEN` | Missing or empty credentials |
| Polarion rejected the token | Invalid or expired PAT |
| REST API is not enabled | `com.siemens.polarion.rest.enabled` is false or REST unreachable |
| Could not reach Polarion | Wrong URL, network, or TLS issues |

## Use with Cursor (MCP)

The MCP server speaks stdio. Cursor starts it as a subprocess; you do not run it interactively in a terminal for normal use.

Project config is in [`.mcp.json`](.mcp.json):

```json
{
  "mcpServers": {
    "arctic": {
      "type": "stdio",
      "command": "uv",
      "args": ["run", "--directory", ".", "mcp-polarion"]
    }
  }
}
```

Requirements:

1. **Working directory** for the server must be this repo root (so `.env` loads).
2. **Credentials** — either `.env` in the repo root or an `env` block on the server entry in Cursor MCP settings (do not commit tokens).

Example with explicit env in Cursor (replace values):

```json
"env": {
  "POLARION_URL": "https://your-host.example.com",
  "POLARION_TOKEN": "your-pat"
}
```

On Windows, if Cursor cannot find `uv`, set `command` to the full path to `uv.exe`.

Reload MCP in Cursor. Example prompts:

- *Who am I in Polarion?*
- *List my Polarion projects, then dry-run a task titled Fix login in project ELK.*

The `whoami` and `list_projects` tools return ids and names, not the token. `create_work_item` defaults to `dry_run=true`; only set `dry_run=false` when you intend to create the item.

## Claude Code (optional)

```bash
claude mcp add arctic \
  -e POLARION_URL=https://your-host.example.com \
  -e POLARION_TOKEN=your-pat \
  -- uv run --directory /path/to/arctic mcp-polarion
```

Use your actual repo path on Windows or Unix.

## Tests

```bash
uv run pytest
```

Tests mock Polarion HTTP; they do not need a live server.

## Roadmap

- `arctic login` (local credential store)
- Work-item type and enum discovery
- Agent prompt path on `arctic` for natural-language commands with tool calling

## License

Not specified in this repository yet.
