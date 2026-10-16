# Arctic

Arctic connects Polarion ALM to AI assistants through a custom [Model Context Protocol](https://modelcontextprotocol.io) server. Authentication uses a Polarion personal access token (PAT) configured outside the LLM; tools never accept or return secrets.

A Java 25 / Spring Boot MCP Server listens on loopback Streamable HTTP. It exposes the tools below except `upload_work_item_attachment`, `import_document`, `convert_heading_to_work_item`, `list_requirement_blocks`, `convert_headings_to_work_items`, `promote_document_requirements`, and `assign_work_items_round_robin`, which stay on the Prototype under [`prototype/`](prototype/). The Arctic CLI is frozen there and is not part of the Java product.

Today the stack supports:

- **`whoami`** — verify REST access and show the Polarion user for your token
- **`list_projects` / `get_project`** — discover Polarion project ids
- **`list_work_items`** — query work items (Lucene, for example `type:requirement`)
- **`list_work_item_types`** — work-item type ids for a project (`userneed`, `hardware`, …)
- **`create_work_item_type`** — add a work-item type option on a project (`dry_run` by default)
- **`create_work_item`** — create one work item (`dry_run` by default)
- **`update_work_item`** — update title, description, status, and/or type (`change_type_to`, `dry_run` by default)
- **`get_work_item`** — read a work item back after create or update
- **`list_link_roles`** — link-role ids and `linkRules` for a project
- **`list_work_item_links` / `list_work_item_backlinks`** — outgoing and incoming links
- **`create_work_item_link` / `delete_work_item_link`** — add or remove a link (`dry_run` by default)
- **`list_documents` / `get_document`** — LiveDocs in a project space
- **`create_document`** — create a LiveDoc (`dry_run` by default)
- **`list_document_parts`** — headings, text, and embedded work items in a document (`work_item_id` when Polarion linked the part)
- **`convert_heading_to_work_item`** — change a heading work item's type in place (`dry_run` by default)
- **`create_document_work_item`** — create a work item in a document (`dry_run` by default)
- **`import_document`** — parse Word (`.docx`) or ReqIF (`.reqif` / `.reqifz`) locally and create a LiveDoc (`dry_run` shows the mapping preview; `--apply` creates data in Polarion)
- **`list_project_users`** — users with a project role (default `project_assignable`)
- **`assign_work_item`** — replace the assignee list on a work item (`dry_run` by default)
- **`list_requirement_blocks`** — parse H2 requirement blocks under H1 sections after import
- **`convert_headings_to_work_items`** — batch convert heading parts to a work-item type (`dry_run` by default)
- **`promote_document_requirements`** — insert hardware work items under H2 headings (Recycle Bin + in-place part), round-robin assign (`dry_run` by default; source paragraphs are deleted unless `delete_source_text` is false)
- **`assign_work_items_round_robin`** — assign many work items across users in order (`dry_run` by default)

Polarion **requirements are work items** (type ids such as `requirement` or `systemrequirement`). Linking a task to a requirement uses `create_work_item_link`. Requirements can also live in **LiveDocs**; use document commands to list, create, import, or add in-document work items.

Word files use Polarion's REST `importWordDocument` action (native LiveDoc conversion on the server). ReqIF is still parsed locally. Without `--apply`, `import-document` prints a JSON preview. With `--apply`, Arctic starts the Polarion import job; `portal_url` in the output is the Polarion wiki editor for that module.

## Repository layout

| Path | Role |
|------|------|
| [`src/main/java/com/arctic/mcp`](src/main/java/com/arctic/mcp) | Java MCP Server (Spring Boot, Streamable HTTP on `127.0.0.1:8080`) |
| [`prototype/`](prototype/) | Frozen Python Prototype (`mcp-polarion`); still hosts the tools the Java server does not expose |
| [`prototype/packages/arctic`](prototype/packages/arctic) | Frozen Arctic CLI (not ported) |
| [`.mcp.json`](.mcp.json) | MCP config: Java `arctic` is a loopback URL; the Prototype stays stdio |
| [`.env.example`](.env.example) | Template for local credentials |

## Prerequisites

- **Java 25** (LTS) — for the MCP Server (`./gradlew bootRun`)
- **[uv](https://docs.astral.sh/uv/)** — installs Python 3.12 and the Prototype
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

From the repository root, sync the Python Prototype:

```bash
uv sync --project prototype --all-packages --group dev
```

This creates a `.venv` and installs `mcp-polarion`, the frozen Arctic CLI, and pytest.

The Java MCP Server uses the Gradle wrapper (no global Gradle install required). Start it from the repository root so `.env` loads:

```bash
./gradlew bootRun
```

It listens at `http://127.0.0.1:8080/mcp` until you stop it. Cursor, Claude Code, Codex, and a custom harness connect to that URL.

If port 8080 is already taken, set `server.port` and use that same port in every host URL. The path stays `/mcp`.

An executable jar is the same server:

```bash
./gradlew bootJar
java -jar build/libs/arctic-mcp-0.1.0.jar
```

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

The Arctic CLI is frozen in `prototype/` and is not part of the Java product. It still runs from the **repository root** (so `.env` loads):

```bash
uv run --project prototype arctic whoami
uv run --project prototype arctic projects
uv run --project prototype arctic project ELK
uv run --project prototype arctic create-work-item --project ELK --type task --title "Fix login"
uv run --project prototype arctic create-work-item --project ELK --type task --title "Fix login" --apply
uv run --project prototype arctic work-item ELK ELK-42
uv run --project prototype arctic work-items ELK --query "type:requirement"
uv run --project prototype arctic work-item-types ELK
uv run --project prototype arctic create-work-item-type --project ELK --id ux --name UX
uv run --project prototype arctic create-work-item-type --project ELK --id ux --name UX --apply
uv run --project prototype arctic link-roles ELK
uv run --project prototype arctic link --project ELK --from ELK-42 --to ELK-12 --role implements
uv run --project prototype arctic link --project ELK --from ELK-42 --to ELK-12 --role implements --apply
uv run --project prototype arctic links ELK ELK-42
uv run --project prototype arctic backlinks ELK ELK-12
uv run --project prototype arctic unlink --project ELK --from ELK-42 --to ELK-12 --role implements --apply
uv run --project prototype arctic update-work-item --project ELK --id ELK-42 --title "Fix login again"
uv run --project prototype arctic update-work-item --project ELK --id ELK-42 --status done --apply
uv run --project prototype arctic update-work-item --project ELK --id ELK-5 --change-type-to hardware
uv run --project prototype arctic documents ELK
uv run --project prototype arctic document ELK Specs
uv run --project prototype arctic create-document --project ELK --module-name Specs --type req_specification --structure-link-role has_parent --title "Specifications"
uv run --project prototype arctic create-document --project ELK --module-name Specs --type req_specification --structure-link-role has_parent --apply
uv run --project prototype arctic document-parts ELK Specs
uv run --project prototype arctic create-document-work-item --project ELK --document Specs --type requirement --title "REQ-1"
uv run --project prototype arctic import-document --project ELK --file ./spec.docx
uv run --project prototype arctic import-document --project ELK --file ./spec.docx --apply
uv run --project prototype arctic requirement-blocks ELK Specs --sections Functional,Physical
uv run --project prototype arctic promote-requirements --project ELK --document Specs --sections Functional,Physical --type hardware
uv run --project prototype arctic promote-requirements --project ELK --document Specs --sections Functional,Physical --type hardware --apply
uv run --project prototype arctic convert-headings --project ELK --document Specs --type hardware --items-file ./items.json
uv run --project prototype arctic assign-round-robin --project ELK --ids DP-1,DP-2 --users alice,bob --apply
uv run --project prototype arctic project-users ELK
uv run --project prototype arctic assign-work-item --project ELK --id ELK-42 --users alice,bob
uv run --project prototype arctic assign-work-item --project ELK --id ELK-42 --users alice,bob --apply
```

`create-work-item`, `create-work-item-type`, `update-work-item`, `link`, `unlink`, `create-document`, `create-document-work-item`, `import-document`, `convert-headings`, `promote-requirements`, `assign-round-robin`, and `assign-work-item` are **dry runs** unless you pass `--apply`. `--type` on work-item commands is the Polarion work-item type id (`task`, `defect`, `requirement`, and so on), which must exist in that project. `--role` is a Polarion link-role id from `link-roles`; Polarion rejects combinations that violate that project's `linkRules`.

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

Start the Java MCP Server first (`./gradlew bootRun` from the repo root). Cursor connects to the URL. The `arctic` entry is only that URL.

Project config is in [`.mcp.json`](.mcp.json):

```json
{
  "mcpServers": {
    "mcp-polarion": {
      "type": "stdio",
      "command": "uv",
      "args": ["run", "--project", "prototype", "mcp-polarion"]
    },
    "arctic": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

`arctic` is the Java MCP Server. `mcp-polarion` is the Prototype and still speaks stdio.

Requirements:

1. **Java MCP Server** — running on this machine, started from the repo root so `.env` supplies `POLARION_URL` and `POLARION_TOKEN`. The personal access token stays in that process. It is not placed in MCP config.
2. **Prototype** — working directory is this repo root (so `.env` loads), or an `env` block on that server entry (do not commit tokens).

Example with explicit env for the Prototype (replace values):

```json
"env": {
  "POLARION_URL": "https://your-host.example.com",
  "POLARION_TOKEN": "your-pat"
}
```

On Windows, if Cursor cannot find `uv`, set the Prototype `command` to the full path to `uv.exe`.

Reload MCP in Cursor after the Java MCP Server is listening. Example prompts:

- *Who am I in Polarion?*
- *List work-item types in drivepilot, then dry-run a userneed titled UND-104 Caregivers.*
- *List my Polarion projects, then dry-run a task titled Fix login in project ELK.*
- *List requirements in ELK, then dry-run an implements link from ELK-42 to that requirement.*
- *Dry-run importing this attached .docx as a LiveDoc in ELK, then show the preview URL after apply.*
- *Import `/path/on/host/spec.docx` into ELK with `import_document` (dry_run first), then `promote_document_requirements` on module Specs with sections Functional, Physical, Power, Performance, Non-Functional and type hardware (dry_run first).*

Word import needs a filesystem path the MCP **host** can read (for example the repo root on your machine). Browsers and chat UIs do not upload the `.docx` through MCP; put the file where the `mcp-polarion` process can open it and pass that path to `import_document`.

The `whoami` and `list_projects` tools return ids and names, not the token. `create_work_item`, `create_work_item_type`, `update_work_item`, `create_work_item_link`, `delete_work_item_link`, `create_document`, `create_document_work_item`, `convert_heading_to_work_item`, `convert_headings_to_work_items`, `import_document`, `promote_document_requirements`, `assign_work_item`, and `assign_work_items_round_robin` default to `dry_run=true`; only set `dry_run=false` when you intend to create or change data. `list_requirement_blocks` is read-only. Call `list_work_item_types` before creating or promoting work items. Call `list_link_roles` before linking or creating a document. Call `list_project_users` before assigning.

## Claude Code and Codex

The Java MCP Server must already be listening. Point either host at the same URL. Polarion Credentials stay in the MCP Server process.

```bash
claude mcp add --transport http arctic http://127.0.0.1:8080/mcp
codex mcp add arctic --url http://127.0.0.1:8080/mcp
```

The Prototype is still a stdio process. To register that instead:

```bash
claude mcp add arctic \
  -e POLARION_URL=https://your-host.example.com \
  -e POLARION_TOKEN=your-pat \
  -- uv run --project /path/to/arctic/prototype mcp-polarion
```

Use your actual repo path on Windows or Unix.

## Tests

```bash
./gradlew test
cd prototype && uv run pytest
```

Java tests mock Polarion HTTP. One test boots the MCP Server on an ephemeral loopback port and calls `whoami` over Streamable HTTP. Python tests mock Polarion HTTP as well. Neither suite needs a live Polarion server.

## Roadmap

- `arctic login` (local credential store)
- Documents — list/get/create/import, document parts, and in-document work items are available via CLI and MCP
- Agent prompt path on `arctic` for natural-language commands with tool calling

## License

Not specified in this repository yet.
