# arctic-server

Java server for Arctic. Gradle runs on Java 17+; the code compiles and tests on a Java 25 toolchain,
which Gradle downloads automatically (Eclipse Temurin via foojay) if no local JDK 25 is found.

## Build

```powershell
cd server
.\gradlew.bat build
```

The runnable jar is `local-app/build/libs/local-app-<version>.jar`.

## Run the local app

The first argument selects the mode.

### stdio (MCP server for a local client)

```powershell
.\gradlew.bat :local-app:bootRun --args=stdio
# or
java -jar local-app\build\libs\local-app-0.1.0-SNAPSHOT.jar stdio
```

stdout carries only MCP JSON-RPC; all logs go to stderr. The process exits when stdin closes.
Use `java -jar` when wiring it into an MCP client, since Gradle itself prints to stdout.
`java` must be a Java 25 runtime.

Tools: `whoami`.

### Polarion credentials

Arctic keeps its files in `%LOCALAPPDATA%\Arctic` (override with `ARCTIC_HOME`). `config.json` there holds
the non-secret settings:

```json
{"polarionUrl": "https://polarion.example.com/polarion", "projectAllowlist": [], "model": {"baseUrl": "...", "model": "..."}}
```

Secrets are generic credentials in Windows Credential Manager: `Arctic/polarion-token` and `Arctic/model-key`.
Both are read on every Polarion request, so changes apply without a restart.

For development against a real Polarion without going through setup, set `ARCTIC_DEV=1`. Arctic then reads
`POLARION_URL` and `POLARION_TOKEN` from the environment instead (never from a `.env` file):

```powershell
.\gradlew.bat :local-app:bootJar
$env:ARCTIC_DEV = "1"
$env:POLARION_URL = "https://polarion.example.com/polarion"
$env:POLARION_TOKEN = "<personal access token>"
java -jar local-app\build\libs\local-app-0.1.0-SNAPSHOT.jar stdio
```

In an MCP client config, pass the same three variables in the server's `env` block.

### serve (local HTTP API on 127.0.0.1)

```powershell
$env:ARCTIC_SERVE_TOKEN = "<random secret>"
java -jar local-app\build\libs\local-app-0.1.0-SNAPSHOT.jar serve --port 8765 [--parent-pid <pid>]
# or
.\gradlew.bat :local-app:bootRun --args="serve --port 8765"
```

Every request needs `Authorization: Bearer <token>` and `Host: 127.0.0.1:<port>`.
`GET /health` returns `{"status":"ready","version":"..."}`. With `--parent-pid`, the server exits
when that process ends.

## Modules


| Module            | Kind                                                                     |
| ----------------- | ------------------------------------------------------------------------ |
| `polarion-client` | Java library, no Spring web or MCP                                       |
| `tools`           | Java library (Spring AI tools)                                           |
| `agent`           | Java library                                                             |
| `local-app`       | Spring Boot app: `stdio` MCP server or `serve` HTTP API                  |
| `completions`     | Spring Boot web app; `check` fails if it ever pulls in `polarion-client` |
| `license-tool`    | Command-line application                                                 |


