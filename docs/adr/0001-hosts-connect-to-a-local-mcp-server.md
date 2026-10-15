# Hosts connect to a local MCP Server

Each host used to launch its own MCP Server over stdio, so Cursor, Claude Code, Codex, and a custom harness could not share one process. The MCP Server is not hosted for other machines. It listens on loopback Streamable HTTP at `http://127.0.0.1:8080/mcp`. A person starts that process. Hosts connect to the URL. There is no MCP login; loopback is the boundary. Reversing it means every host config changes together, and someone who still expects stdio will look for a process the host launches.

## Considered Options

- Stdio, one process per host. A host launches the jar and talks on that process's stdin and stdout.
- Streamable HTTP on loopback, one shared process, no MCP login. This is the choice.
