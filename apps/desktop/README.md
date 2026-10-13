# Arctic desktop

Tauri 2 + React + TypeScript + Vite. The app is a supervisor and a view: it only talks to the
local Arctic server (`server/local-app/api/openapi.yaml`), and all of that HTTP happens in Rust
(`src-tauri/src/api.rs`). The bearer token never enters JavaScript.

Requires Node 20.17+, npm, and a Rust toolchain (MSVC on Windows).

## Install

```powershell
npm install
```

## Run with the real server (default)

On startup the Rust core (`src-tauri/src/supervisor/`) starts the server itself: it picks a free
port on 127.0.0.1, generates a random session token, runs
`java --enable-native-access=ALL-UNNAMED -jar <jar> serve --port <p> --parent-pid <app pid>`
in a Windows Job Object (so the server dies with the app), and polls `GET /health` until ready
(30 s timeout). The UI shows the state, the last 300 lines of server output, and a Retry button.

- `ARCTIC_JAVA`: path to a Java 25 `java.exe`. If unset, the first Java 25 found under
  `%USERPROFILE%\.gradle\jdks` or `%USERPROFILE%\.jv` is used.
- `ARCTIC_SERVER_JAR`: path to the server jar. If unset, debug builds use
  `server/local-app/build/libs/local-app-*.jar` from this repo
  (build it with `server\gradlew.bat :local-app:bootJar` from `server/`).

```powershell
npm run tauri dev
```

Only one instance runs: a second launch focuses the existing window.

## Run against the stub server

The stub (`stub/server.mjs`, no dependencies) implements the API in memory on `127.0.0.1`.
It reads `ARCTIC_SERVE_TOKEN` (default `dev-token`) and `PORT` (default `47100`).
The Polarion probe is simulated by the PAT you enter: `bad` gives `rejected_token`,
`disabled` gives `rest_disabled`, `offline` gives `network_error`, anything else gives `ok`.

```powershell
npm run stub
```

In a second terminal (setting `ARCTIC_SERVER_URL` means no server is spawned):

```powershell
$env:ARCTIC_SERVER_URL = "http://127.0.0.1:47100"
$env:ARCTIC_SERVE_TOKEN = "dev-token"
npm run tauri dev
```

## Test and build

```powershell
npm run build        # tsc + vite build
npx vitest run       # frontend unit tests
cd src-tauri
cargo test
cargo build
cargo clippy --all-targets
```

End-to-end supervisor checks against the real jar (skip when Java 25 or the jar is missing):

```powershell
cd src-tauri
cargo run --example server_e2e              # start, /health, restart, stop, nothing left behind
cargo run --example server_e2e -- orphan    # parent dies abruptly; the server must go away
```
