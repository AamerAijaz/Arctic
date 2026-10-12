# Shared Polarion fixtures

Language-neutral recordings of Polarion REST interactions. Every client
implementation (the Python prototype in `prototype/`, the Java server in
`server/`) replays these files and must produce the same requests and results.

The Python prototype is the source of truth. Its harness is
`prototype/packages/mcp-polarion/tests/test_shared_fixtures.py`; run it with
`uv run pytest` from `prototype/`. If a fixture and the prototype disagree,
fix the prototype or the fixture there first, then port.

## Layout

```
fixtures/polarion/
  auth/        get_current_user (whoami)
  projects/    list_projects, get_project
  workitems/   get/list/create/update work items, list/create work item types
  links/       link roles, links, backlinks, create/delete link
  users/       list_project_users, assign_work_item
  url_normalization.json
  redaction.json
```

## Conventions

- Base URL: `https://polarion.example.com`. After normalization the REST root is
  `https://polarion.example.com/polarion/rest/v1`.
- Token: `test-token`.
- Every request must carry `Authorization: Bearer test-token` and
  `Accept: application/json`. Requests with a body must also carry
  `Content-Type: application/json`. These headers are not repeated in each file.
- `POLARION_PROJECT_ALLOWLIST` is unset unless the scenario has an `env` block.

## Scenario files (`polarion/<area>/<scenario>.json`)

```json
{
  "name": "create_work_item",
  "description": "What this scenario pins down.",
  "call": { "operation": "create_work_item", "args": { "project_id": "ELK", "dry_run": false } },
  "env": { "POLARION_PROJECT_ALLOWLIST": "ELK" },
  "exchanges": [
    {
      "request": { "method": "POST", "path": "/polarion/rest/v1/projects/ELK/workitems", "query": {}, "body": {} },
      "response": { "status": 201, "body": {} }
    }
  ],
  "expect": { "result": {} }
}
```

- `name` equals the file name without `.json`.
- `call.operation` is the Python `PolarionClient` method name. `call.args` are
  its keyword arguments in snake_case. Arguments that are not listed take the
  method's defaults. Note that `dry_run` defaults to `true`.
- `env` (optional): environment variables to set for this scenario only.
- `exchanges`: the HTTP calls the operation must make, in order. If the list is
  empty, the operation must make no HTTP call at all (dry runs, validation
  errors, allowlist rejections).
  - `request.path`: the path relative to the server origin, exactly as sent,
    percent-encoded.
  - `request.query`: the complete set of query parameters as string values.
    There are no extra or missing keys and no repeated keys. Order does not
    matter.
  - `request.body`: the JSON body, compared structurally (key order and
    whitespace are ignored). If omitted, the request must have no body.
  - `response.status`: the status to return. `response.body`: the JSON to
    return. If omitted, return an empty body (for example, a 204).
- `expect` holds exactly one of:
  - `result`: the operation's return value as plain JSON. A model object is
    serialized with its `to_dict()` shape (snake_case keys, every field present
    with `null` when unset, tuples as arrays), and a list as an array of those.
    Dry-run previews include `"dry_run": true` as `to_dict()` does.
    `WorkItemUpdatePreview` omits `change_type_to` when it is null.
  - `error`: `type` is the exact error class name: `PolarionAuthError`,
    `PolarionUnavailableError`, `PolarionApiError`, `MissingCredentialsError`,
    or the base `PolarionError` for local validation and unexpected-payload
    failures. Optional keys:
    - `status`: the `PolarionApiError` status code.
    - `message_contains`: a substring the error message must contain.
    - `message_excludes`: a substring the error message must not contain (used
      to check that the token never leaks).

## `url_normalization.json`

`cases[]` with `name`, `input`, and either `expected` (the REST root that
`normalize_rest_root(input)` returns) or `error.type` (the error raised).

## `redaction.json`

`cases[]` with `name`, `text`, `token` (string or `null`), and `expected`, which
is the output of `redact(text, token)`.

## Consuming the fixtures from another implementation

1. Find the repo root (the directory that contains `fixtures/`) and glob
   `fixtures/polarion/*/*.json`.
2. For each scenario, build a client with a static credential provider:
   base URL `https://polarion.example.com` and token `test-token`. Apply `env`
   (or the equivalent config) and make sure the allowlist is empty otherwise.
3. Install a fake HTTP transport that pops the next exchange for each request.
   Assert the method, path, query, body, and common headers match, then
   return the recorded status and body. Fail on any extra request.
4. Invoke the operation that matches `call.operation` with `call.args`. Use a
   small per-language dispatch table, for example mapping `list_work_items` to
   `listWorkItems(projectId, query, pageSize, pageNumber)`.
5. Compare the returned value, serialized to the `to_dict()` JSON shape, with
   `expect.result`. Or check that the error type, status, and message
   constraints match `expect.error`.
6. Assert that every exchange was consumed.
