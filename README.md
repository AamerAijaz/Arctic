# Arctic

Arctic connects Polarion ALM to AI assistants. It runs locally: a Spring Boot server exposes Polarion tools over MCP to Cursor and Claude, and a desktop app provides setup and a chat for users without an IDE model.

| Path | Role |
|------|------|
| [`prototype/`](prototype) | Python MCP server and CLI. Behavior spec for the product. See [prototype/README.md](prototype/README.md). |
| [`server/`](server) | Java 25, Spring Boot 4.1 server: Polarion client, tools, agent loop, stdio MCP, and localhost API. |
| [`apps/desktop/`](apps/desktop) | Tauri 2 and React desktop app: setup, MCP registration, and chat. |
