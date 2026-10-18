# MCP security for Arctic

Research against the MCP specification revision 2026-07-28, the official MCP security guides for that revision, the Spring AI MCP Server Boot Starter docs, Spring AI 2.0.1 / MCP Java SDK 2.0.0 as this build resolves them, and this repo. This is not a decision record.

## Recommendation

Do not build an MCP login. Do bother with the Streamable HTTP listener, because that listener — not the Polarion personal access token (PAT) sitting in the environment — is the hole.

The PAT is already held the way a local server is supposed to hold an upstream credential: the server process reads `POLARION_URL` and `POLARION_TOKEN` from the environment or `.env`, and tools never accept or return it. An OAuth 2.1 layer in front of `/mcp` would not stop someone who can read that process from taking the PAT. What it would gate is who may call tools that spend the PAT.

That gate is optional in the spec, and [ADR 0001](../adr/0001-hosts-connect-to-a-local-mcp-server.md) already chose loopback with no MCP login so Cursor, Claude Code, Codex, and a custom harness can share one process. The spec still requires Origin validation on Streamable HTTP, and it says a localhost bind is not enough on its own. Spring AI 2.0.1 ships that validation turned off. Closing that gap is the work that matches this deployment. Building an authorization server does not.

## Must bother

### Stay on 127.0.0.1

Streamable HTTP servers that run locally SHOULD bind to `127.0.0.1` rather than `0.0.0.0`. Without localhost binding, Origin checks, and authentication, a remote website can use DNS rebinding to talk to the server ([Streamable HTTP: Security & Endpoint](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)).

This server already binds loopback:

```yaml
server:
  address: 127.0.0.1
  port: 8080
```

`src/main/resources/application.yml`. Hosts use `http://127.0.0.1:8080/mcp` ([README.md](../../README.md), [`.mcp.json`](../../.mcp.json)). The starter does not choose that address for you. `server.address` is this app's setting. Keep it.

### Validate Origin and Host on `/mcp`

The same section says servers MUST validate the `Origin` header on every incoming connection. If `Origin` is present and invalid, the server MUST respond with HTTP 403.

Spring's starter does not do that. The HTTP transports expose an unauthenticated JSON-RPC endpoint by default, and the starters apply no authentication or authorization of their own ([MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)). In the version this repo pins (`spring-ai-bom` 2.0.1, [`build.gradle.kts`](../../build.gradle.kts)):

- `WebMvcStreamableServerTransportProvider.Builder` defaults the security validator to `ServerTransportSecurityValidator.NOOP`, which accepts every request ([`WebMvcStreamableServerTransportProvider.java`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/mcp/transport/mcp-spring-webmvc/src/main/java/org/springframework/ai/mcp/server/webmvc/transport/WebMvcStreamableServerTransportProvider.java), [`ServerTransportSecurityValidator.java`](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.0/mcp-core/src/main/java/io/modelcontextprotocol/server/transport/ServerTransportSecurityValidator.java)).
- Auto-configuration builds that transport with the endpoint, keep-alive, and delete flag only. It never sets a validator ([`McpServerStreamableHttpWebMvcAutoConfiguration.java`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webmvc/src/main/java/org/springframework/ai/mcp/server/webmvc/autoconfigure/McpServerStreamableHttpWebMvcAutoConfiguration.java)).
- `spring.ai.mcp.server.streamable-http` has `mcpEndpoint`, `keepAliveInterval`, and `disallowDelete`. There is no security property (`McpServerStreamableHttpProperties` in `spring-ai-autoconfigure-mcp-server-common` 2.0.1).

The SDK already has the check the spec describes. `DefaultServerTransportSecurityValidator` returns 403 for a present `Origin` that is not on the allowlist, and can require `Host` to be an allowed value such as `127.0.0.1:8080`. A missing `Origin` is treated as a same-origin request and skipped ([`DefaultServerTransportSecurityValidator.java`](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.0/mcp-core/src/main/java/io/modelcontextprotocol/server/transport/DefaultServerTransportSecurityValidator.java)). DNS rebinding often arrives that way: the browser still thinks it is talking to the attacker's name, so it may omit `Origin` and send `Host: attacker.example`. Allowing only `127.0.0.1` and `localhost` as `Host`, and only `http://127.0.0.1:8080` and `http://localhost:8080` as `Origin`, is the concrete fix. Wiring the validator is a small local change. This app has no `securityValidator` bean today.

Binding to `127.0.0.1` does not replace that. The local-server guide says a loopback HTTP listener is reachable by other local processes and, through the browser, by websites, and that binding to `127.0.0.1` is not an authentication boundary: those listeners need authentication and origin validation ([Local Server Security](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/local-server-security)).

### Keep the PAT out of the protocol

This part is already done. Leave it alone, and do not regress it.

- `EnvironmentPolarionCredentialsSource` reads `POLARION_URL` and `POLARION_TOKEN` from the process environment, then from `.env` only for names that are not already set ([`EnvironmentPolarionCredentialsSource.java`](../../src/main/java/com/arctic/mcp/polarion/EnvironmentPolarionCredentialsSource.java)). `.env` is gitignored.
- `PolarionCredentials` is the REST root and the token. The record's own comment says tools never see it ([`PolarionCredentials.java`](../../src/main/java/com/arctic/mcp/polarion/PolarionCredentials.java)). Tool classes take a `PolarionClient`, not credentials ([`PolarionConfiguration.java`](../../src/main/java/com/arctic/mcp/PolarionConfiguration.java)).
- `whoami` takes no arguments and returns `CurrentUser` (`id`, `name`, `email`) ([`WhoamiTool.java`](../../src/main/java/com/arctic/mcp/tool/WhoamiTool.java), [`CurrentUser.java`](../../src/main/java/com/arctic/mcp/polarion/CurrentUser.java)). The loopback test asserts the tool schema and the result do not contain the token ([`McpServerStreamableHttpTest.java`](../../src/test/java/com/arctic/mcp/McpServerStreamableHttpTest.java)).
- The client sends `Authorization: Bearer` to Polarion only. Error text passed back to the caller is redacted ([`PolarionClient.java`](../../src/main/java/com/arctic/mcp/polarion/PolarionClient.java), `redact`). `PolarionException` is documented as never including the token.
- Tool logs record the tool name, success or failure, and duration. Arguments and results stay out ([`ToolCallLogging.java`](../../src/main/java/com/arctic/mcp/ToolCallLogging.java)). Root level is WARN; the file appender is `build/arctic-mcp.log` ([`logback-spring.xml`](../../src/main/resources/logback-spring.xml)).
- The Java host entry is only the loopback URL. The PAT is not in [`.mcp.json`](../../.mcp.json). README says the token stays in the server process and is not placed in MCP config.

The authorization spec tells STDIO servers to take credentials from the environment and not to implement the OAuth spec ([Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)). This server is HTTP, but the PAT handling matches that rule: the upstream secret is an environment credential, separate from any MCP access token. The security-considerations page says an MCP server that calls an upstream API uses a separate token for that API and MUST NOT pass through a token it received from the MCP client ([Authorization Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)). Arctic never receives a client token to pass through.

The local-server guide's credential rules still apply to the file on disk: pass each server only its own token, prefer a narrow token, and do not leave long-lived secrets in client config files ([Local Server Security](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/local-server-security)). README already prefers each person's own PAT over a shared service account. A project allowlist (`POLARION_PROJECT_ALLOWLIST`) can shrink which Polarion projects a caller may touch; an empty value allows every project ([`ProjectAllowlist.java`](../../src/main/java/com/arctic/mcp/polarion/ProjectAllowlist.java)). That is a scope limit on an honest caller, not a lock.

## Not worth building

### An OAuth 2.1 MCP authorization server

Authorization is OPTIONAL. When an implementation does support it, HTTP transports SHOULD conform to the OAuth 2.1 flow (protected resource metadata, audience-restricted access tokens, PKCE). STDIO transports SHOULD NOT follow that spec ([Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)). The security-considerations requirements — audience binding, token theft storage rules, confused-deputy consent, forbidding token passthrough — are for implementations of that authorization spec.

Arctic does not implement it. There is no Spring Security dependency ([`build.gradle.kts`](../../build.gradle.kts)), no security block in `application.yml`, and no `Authorization` header on the host URL. Spring's own instruction is narrower than "every server needs OAuth": put a security boundary in front of an HTTP MCP endpoint before exposing it beyond localhost. That warning does not apply to STDIO, which is not network-accessible ([MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)). This process is HTTP, but it is localhost, which is the case Spring treats as the line you have not crossed yet.

The official confused-deputy write-up is an OAuth proxy bug: a server that uses a static client id at a third-party authorization server, skips per-client consent, and lets an attacker replay a consent cookie ([Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices), [Authorization Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)). Token passthrough is the related ban on accepting a token that was not issued for the MCP server and forwarding it upstream. Neither flow exists here. `PolarionClient` attaches the process PAT itself.

A spec-compliant MCP login would also reverse the host setup. ADR 0001 says there is no MCP login and that reversing the transport choice means every host config changes together. `.mcp.json` and the Claude Code / Codex commands are a bare URL.

### Server-side protection from prompt injection

Tools are model-controlled. For trust and safety there SHOULD always be a human who can deny a tool call; the host SHOULD show what is exposed and confirm operations ([Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)). That duty sits on Cursor, Claude Code, and Codex, not on a filter this server can reliably enforce once a host is connected.

Mutating tools default to `dry_run=true` when the argument is omitted ([`ToolSupport.java`](../../src/main/java/com/arctic/mcp/tool/ToolSupport.java)). A caller who passes `dry_run=false` still writes. The default is a brake on accidents. It is not authentication.

A malicious local process, or a host the user already trusts, can list and call every tool. The local-server guide also covers tool poisoning and prompt injection steering an honest server: the protocol does not sandbox a server, and isolation is the operator's job ([Local Server Security](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/local-server-security)). For this first-party server the practical form of that advice is the PAT's Polarion permissions and, if wanted, `POLARION_PROJECT_ALLOWLIST`. It is not an MCP user store.

### A shared static bearer, on a single-user machine

Security best practices for a server meant to run locally: prefer stdio, so only the spawning client can connect; if you use HTTP, require an authorization token, or use a unix socket or other IPC with restricted access ([Security Best Practices: Local MCP Server Compromise](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices)). Streamable HTTP also says servers SHOULD authenticate every connection.

Origin and Host checks do not satisfy that SHOULD. A process on the same machine can send `Host: 127.0.0.1:8080` and no `Origin` and pass the SDK validator. That process then acts as the Polarion user. The PAT never appears in the tool result; the tool still runs with it. This is the privileged-token deputy that matters here, and it is a different bug from the OAuth confused deputy above.

ADR 0001 accepted that residual risk: loopback is the boundary. A static bearer shared by every host would be a second secret in host config, which the local-server guide tells you not to scatter, and it would do nothing about a host you already configured. Skip it while one person runs this on their own machine. Add it if other OS users, or other untrusted local programs, can open TCP to `127.0.0.1:8080`. That token is still not the OAuth spec, and it is still not the Polarion PAT.

Stdio would also close this, which is why the best-practices page lists it first. The frozen prototype still speaks stdio. The Java server left stdio so several hosts can share one process. That tradeoff is the ADR. The security cost is the open loopback port.

## Threats

| Threat | Applies locally? | Already mitigated? | Worth extra work? |
| --- | --- | --- | --- |
| PAT accepted or returned by a tool | Yes, if a tool did it | Yes. Tools never take credentials; `whoami` returns id, name, email; test checks the token is absent | No. Keep the invariant |
| PAT in logs or error text | Yes | Yes for this app's logger and Polarion errors: name, outcome, duration only; `redact` strips the bearer and the token; root WARN | No new subsystem. Do not log request headers or tool payloads |
| PAT copied into MCP host config | Yes for any server whose config embeds it | Java server: URL only. README still shows an `env` block for the prototype stdio server | No change for the Java server. Do not copy that prototype example onto `arctic` |
| Someone reads the process environment or `.env` | Yes | `.env` is gitignored. MCP auth does not hide a secret the process already holds | No. File and OS permissions |
| DNS rebinding: a website drives `POST /mcp` | Yes. Loopback HTTP is still a browser target | No. Bind is `127.0.0.1`, validator is the starter's NOOP | Yes, now. Allow only loopback `Origin` and `Host` |
| Another local process calls tools and spends the PAT | Yes. The official guides say other local processes can reach a loopback listener | Only the bind. No MCP credential. Allowlist is empty unless set | Not OAuth. A static local bearer only if the machine is shared. Otherwise accepted by ADR 0001 |
| OAuth confused deputy / token passthrough | No. No third-party OAuth proxy, no client token | Not applicable | No |
| Prompt injection or a connected host calls destructive tools | Yes, after a host connects | `dry_run` defaults to true; project allowlist is optional and off | Host confirmation, which the tools spec assigns to the client. Not an MCP login |
| Listener reachable off the machine | No, while `server.address` stays `127.0.0.1` | The bind | Later. See below |

## Now, and later if it leaves localhost

Now:

1. Keep `server.address: 127.0.0.1`. Do not publish the port.
2. Install `DefaultServerTransportSecurityValidator` (or an equivalent filter) on the Streamable HTTP transport: allow `Origin` `http://127.0.0.1:8080` and `http://localhost:8080`, and allow `Host` `127.0.0.1` and `localhost` with the bound port. Reject anything else with 403. The starter will not do this for you.
3. Leave the PAT where it is. No tool parameter, no tool result, no log line, no host config entry.

Later, only if the server is reachable beyond loopback — `server.address` not loopback, a published port, a reverse proxy, or another machine's host URL:

Spring's docs then require a security boundary in front of `POST /mcp` before any client on the network can list and call tools. The spec's SHOULD for authentication becomes the thing you actually need, because Origin checks do not identify a remote caller. That is the point at which an MCP authorization flow, or a gateway that enforces one, is worth building. The PAT still stays a server-side upstream credential. Do not start accepting it from clients, and do not forward a client token to Polarion.

Until that day, the prototype's stdio transport remains the spec's tighter local default, and the Java server's loopback URL remains the shared-process default this repo chose. The missing piece in the chosen default is header validation, not an authorization server.

## Sources opened

- [Specification](https://modelcontextprotocol.io/specification/) (current index; revision used below is 2026-07-28, from the docs index)
- [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [stdio](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio)
- [Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
- [Authorization Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)
- [Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices)
- [Local Server Security](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/local-server-security)
- [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- Spring AI 2.0.1 `WebMvcStreamableServerTransportProvider` and `McpServerStreamableHttpWebMvcAutoConfiguration` (links above)
- MCP Java SDK 2.0.0 `ServerTransportSecurityValidator` and `DefaultServerTransportSecurityValidator` (links above)
- This repo: [ADR 0001](../adr/0001-hosts-connect-to-a-local-mcp-server.md), [README.md](../../README.md), [`.mcp.json`](../../.mcp.json), [`application.yml`](../../src/main/resources/application.yml), [`build.gradle.kts`](../../build.gradle.kts), Polarion credential and client types, tool classes, [`ToolCallLogging.java`](../../src/main/java/com/arctic/mcp/ToolCallLogging.java), [`ProjectAllowlist.java`](../../src/main/java/com/arctic/mcp/polarion/ProjectAllowlist.java)
