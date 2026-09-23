# Design: MCP `2026-07-28` support — Java SDK 3.0

**Status:** draft, in progress
**Branch:** `dgarnier/2026-07-28`
**Spec source:
** [modelcontextprotocol/modelcontextprotocol](https://github.com/modelcontextprotocol/modelcontextprotocol)
at [
`c7400a0`](https://github.com/modelcontextprotocol/modelcontextprotocol/tree/c7400a0796045cbf102cb80b9b41422c7ce8e7af) (
2026-09-22)

- prose: `docs/specification/2026-07-28/`
- schema: `schema/2026-07-28/schema.ts` / `schema.json`
- changelog: `docs/specification/2026-07-28/changelog.mdx`

---

## 1. Context

`2026-07-28` is not an additive revision. It removes the two pillars the current
SDK is built around:

1. **Protocol-level sessions are gone.** No `Mcp-Session-Id`, no
   `initialize` / `notifications/initialized`. Protocol version and client
   capabilities travel in `_meta` on *every* request. The stateful/stateless
   server split (`McpAsyncServer` vs `McpStatelessAsyncServer`) collapses into
   the stateless model only.
2. **Server-initiated requests are gone.** No server→client `roots/list`,
   `sampling/createMessage`, `elicitation/create`. They are replaced by
   **MRTR** (Multi Round-Trip Requests): a handler *returns*
   `InputRequiredResult` and the client *retries the original request* carrying
   `inputResponses`.

Consequence: v2.2 is a rewrite of the protocol core, not a migration of it.

## 2. Goals / non-goals

### Goals

- Full, clean implementation of `2026-07-28` — server and client.
- The new APIs live alongside the existing APIs. The existing APIs are untouched.
- The sync/async split is too complicated: remove the split entirely and produce only blocking sync APIs.

### Non-goals

- Evolving the new types from existing `2025-11-25` or earlier. Users needing them use the existing classes.
- Backwards compatibility of the v2.2 public API with current. It is a new API.
- (For now) the dual-era bridge — see §11.

## 3. Decisions

### D1 — Version support: v3 is `2026-07-28`-only

A new module, with new package names, supporting exactly one protocol revision. Users who need legacy servers the
modules.

*Rationale:* the wire models diverge so far (sessions, lifecycle, MRTR,
subscriptions) that a shared abstraction would cost more than two independent
stacks, and would leak legacy concepts into the new API.

### D2 — Extract `io.modelcontextprotocol.json` into a new `mcp-json` artifact

Everything under `io.modelcontextprotocol.json` in `mcp-core` (`McpJsonMapper`,
`McpJsonMapperSupplier`, `McpJsonDefaults`, `TypeRef`, and `json.schema`'s
`JsonSchemaValidator` / `JsonSchemaValidatorSupplier`) moves to a new
`io.modelcontextprotocol.sdk:mcp-json` artifact. Package names are unchanged.
`mcp-core` and `mcp-json-jackson{2,3}` depend on `mcp-json`; the Jackson modules
no longer depend on `mcp-core`.

Couplings cut when extracting:
- `McpJsonDefaults` used `util.McpServiceLoader`. It moved to
  `io.modelcontextprotocol.json.McpServiceLoader` in `mcp-json`. `mcp-core` keeps
  a deprecated `util.McpServiceLoader` subclass for compatibility, since
  splitting the `util` package across two jars would break JPMS.
- `DefaultJsonSchemaValidator` (both Jackson modules) used `util.Assert` and
  `McpSchema.JSON_SCHEMA_DIALECT_2020_12`. Both are inlined.
- `mcp-core`'s bnd `Export-Package` excludes `io.modelcontextprotocol.json.*`,
  otherwise bnd copies the classes from the classpath back into the `mcp-core`
  bundle.

*Rationale:* the JSON abstraction is independent of any protocol revision.
Having it in its own artifact lets the existing and the new core share one JSON
layer and one set of Jackson bindings.

### D3 — Coordinates and packages

Idea:

- introduce a new `io.modelcontextprotocol.sdk:mcp-core-modern` module, and the base package is `io.modelcontextprotocol.modern`.
- `mcp-core-modern` is allowed to depend on `mcp-json`. `mcp-json-*`.

TODO: The package name is provisional and should be refined


TODO: artifactId and groupId names must be defined

*Rationale:* distinct packages + distinct artifactIds mean no split packages and
no duplicate classes, which is what makes side-by-side installation and a later
dual-era bridge possible.

### D4 — MRTR: union return types, no blocking facade

Handlers for `tools/call`, `prompts/get` and `resources/read` return an open
(non-sealed) union of their normal result, `InputRequiredResult`, and any
result type contributed by an extension (e.g. Tasks' `CreateTaskResult`). There
is no continuation facade that makes `elicit()` look blocking. Full shape in
§7.4.

### D5 — Authorization is out of scope

Authorization is not covered in the SDK. The SDK only provides the appropriate
extension hooks used for authorization purposes.

### D6 — `mcp-demo-server` and `mcp-demo-client` modules for API experiments

Two new modules, `mcp-demo-server` and `mcp-demo-client`, depend on
`mcp-core-modern` (plus a `mcp-json-jackson*` binding so they run). Each is a
small runnable app that exercises the new API end to end, e.g. a tool that
needs MRTR elicitation, called by a client that auto-drives the loop.

They are a place to try API shapes (§7, §9) against real code before
committing to them. They are not published, like `conformance-tests`, and they
are not the conformance harness (§12.1).

*Rationale:* the API decisions in this document are easier to judge from
caller code than from signatures. Keeping that code in the build means it
breaks, and gets updated, whenever the API changes.

---

## 5. Implementation inventory

Written as if `mcp3` starts from an empty directory: everything that must exist,
not a diff against v2. Items are numbered so §5.9 and §13 can reference them.

Scope note: the Java SDK deliberately does **not** implement OAuth flows — v2
ships only seams (`McpHttpClientAuthorizationErrorHandler` and friends) and
leaves the flows to the host framework. §5.7 keeps that boundary.

### 5.1 Schema / wire layer — `v3.spec`

165 exported types in `schema/2026-07-28/schema.ts`. Grouped by what they cost us:

| #   | Item                                                                                                                                                                                                                                 | Size |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| S1  | JSON-RPC envelope: `JSONRPCRequest`, `JSONRPCNotification`, `JSONRPCResultResponse`, `JSONRPCErrorResponse`, `RequestId`, version constants                                                                                          | S    |
| S2  | Common types: `Request`/`Notification`/`Result` params bases, `MetaObject` family (request/notification/result), `ProgressToken`, `Cursor`, `RequestId`, `EmptyResult`                                                               | M    |
| S3  | `ResultType` + `Result.resultType`, SDK-stamped, absent⇒`complete` on read (§7.4)                                                                                                                                                    | S    |
| S4  | `CacheableResult` (`ttlMs`, `cacheScope`) mixed into 5 result types                                                                                                                                                                  | S    |
| S5  | `PaginatedRequestParams` / `PaginatedResult`                                                                                                                                                                                         | S    |
| S6  | Metadata & display: `BaseMetadata`, `Icon`, `Icons`, `Annotations`, `Role`                                                                                                                                                           | S    |
| S7  | Content blocks: `ContentBlock` union, `TextContent`, `ImageContent`, `AudioContent`, `ResourceLink`, `EmbeddedResource`, `ResourceContents`/`Text`/`Blob`                                                                            | M    |
| S8  | Tools: `Tool`, `ToolAnnotations`, list/call requests + results                                                                                                                                                                       | M    |
| S9  | Prompts: `Prompt`, `PromptArgument`, `PromptMessage`, list/get                                                                                                                                                                       | S    |
| S10 | Resources: `Resource`, `ResourceTemplate`, list / templates-list / read                                                                                                                                                              | M    |
| S11 | Completion: `CompleteRequest`/`Result`, `PromptReference`, `ResourceTemplateReference`                                                                                                                                               | S    |
| S12 | Elicitation: `ElicitRequest` form + URL params, `ElicitResult`, and the 13-type `PrimitiveSchemaDefinition` / `EnumSchema` tree                                                                                                      | L    |
| S13 | Sampling: `CreateMessageRequest`/`Result`, `SamplingMessage`, `SamplingMessageContentBlock`, `ToolUseContent`, `ToolResultContent`, `ToolChoice`, `ModelPreferences`, `ModelHint`                                                    | M    |
| S14 | Roots: `ListRootsRequest`/`Result`, `Root`                                                                                                                                                                                           | S    |
| S15 | MRTR: `InputRequest`/`InputResponse` unions, `InputRequests`, `InputResponses`, `InputRequiredResult`, `InputResponseRequestParams` (§7.4)                                                                                           | M    |
| S16 | Discover: `DiscoverRequest`/`Result`, `ClientCapabilities`, `ServerCapabilities`, `Implementation`, `extensions`                                                                                                                     | M    |
| S17 | Subscriptions: `SubscriptionFilter`, `SubscriptionsListenRequest`/`Result`, `SubscriptionsListenResultMetaObject`                                                                                                                    | S    |
| S18 | Notifications: cancelled, progress, message, 3× list_changed, resources/updated, subscriptions/acknowledged                                                                                                                          | S    |
| S19 | Errors: 5 JSON-RPC codes + `HEADER_MISMATCH` `-32020`, `MISSING_REQUIRED_CLIENT_CAPABILITY` `-32021`, `UNSUPPORTED_PROTOCOL_VERSION` `-32022`, their typed error data, and the `-32000..-32019` / `-32020..-32099` allocation policy | M    |
| S20 | Well-known `_meta` keys: `protocolVersion`, `clientCapabilities`, `clientInfo`, `serverInfo`, `logLevel`, `subscriptionId`, `tasks` — as constants + typed accessors                                                                 | S    |
| S21 | Aggregate unions `ClientRequest`/`ClientNotification`/`ClientResult`/`ServerNotification`/`ServerResult` and polymorphic (de)serialization wiring                                                                                    | M    |
| S22 | Round-trip conformance of every type against `schema.json`                                                                                                                                                                           | M    |

### 5.2 Protocol methods

21 methods. Direction and shape drive where the work lands.

**Client → server requests (10)**

| #   | Method                     | Result                                      | Notes                            |
|-----|----------------------------|---------------------------------------------|----------------------------------|
| P1  | `server/discover`          | `DiscoverResult`                            | mandatory server-side; cacheable |
| P2  | `tools/list`               | paginated + cacheable                       | deterministic order (SHOULD)     |
| P3  | `tools/call`               | `CallToolResult \| InputRequiredResult`     | MRTR                             |
| P4  | `prompts/list`             | paginated + cacheable                       |                                  |
| P5  | `prompts/get`              | `GetPromptResult \| InputRequiredResult`    | MRTR                             |
| P6  | `resources/list`           | paginated + cacheable                       |                                  |
| P7  | `resources/templates/list` | paginated + cacheable                       |                                  |
| P8  | `resources/read`           | `ReadResourceResult \| InputRequiredResult` | MRTR + cacheable                 |
| P9  | `completion/complete`      | `CompleteResult`                            |                                  |
| P10 | `subscriptions/listen`     | `SubscriptionsListenResult`                 | long-lived POST-response stream  |

**Client → server notifications (1)**

| #   | Method                    | Notes |
|-----|---------------------------|-------|
| P11 | `notifications/cancelled` |       |

**Server → client notifications (7)**

| #   | Method                                     | Channel                                                              |
|-----|--------------------------------------------|----------------------------------------------------------------------|
| P12 | `notifications/progress`                   | response stream of the originating request                           |
| P13 | `notifications/message`                    | response stream; emitted **only** if that request carried `logLevel` |
| P14 | `notifications/tools/list_changed`         | `subscriptions/listen` stream, tagged with `subscriptionId`          |
| P15 | `notifications/prompts/list_changed`       | idem                                                                 |
| P16 | `notifications/resources/list_changed`     | idem                                                                 |
| P17 | `notifications/resources/updated`          | idem                                                                 |
| P18 | `notifications/subscriptions/acknowledged` | idem                                                                 |

**Not standalone methods — MRTR `InputRequest` payloads only (3)**

| #   | Method                   | Notes                          |
|-----|--------------------------|--------------------------------|
| P19 | `elicitation/create`     | form + URL mode, both via MRTR |
| P20 | `sampling/createMessage` | deprecated feature             |
| P21 | `roots/list`             | deprecated feature             |

There is no `initialize`, no `notifications/initialized`, no `ping`, no
`logging/setLevel`, no `resources/subscribe`/`unsubscribe`, no
`notifications/roots/list_changed`, no `notifications/elicitation/complete`.

### 5.3 Server runtime

| #    | Item                                                                                                                                                                                                                                       | Size |
|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| SR1  | Stateless per-request dispatch: JSON-RPC decode → method route → handler → encode. No session object, no connection state                                                                                                                  | L    |
| SR2  | Per-request negotiation: read `protocolVersion` / `clientCapabilities` / `clientInfo` from `_meta`; reject mismatch with `UnsupportedProtocolVersionError`; reject missing required capability with `MissingRequiredClientCapabilityError` | M    |
| SR3  | Stamp `serverInfo` into every result `_meta`; stamp `resultType`                                                                                                                                                                           | S    |
| SR4  | `server/discover` implementation from declared capabilities + identity + supported versions                                                                                                                                                | M    |
| SR5  | Tool registry: register/list/call, filtering, deterministic ordering                                                                                                                                                                       | M    |
| SR6  | Prompt registry                                                                                                                                                                                                                            | S    |
| SR7  | Resource + resource-template registry, URI template matching                                                                                                                                                                               | M    |
| SR8  | Pagination over all four list methods                                                                                                                                                                                                      | M    |
| SR9  | Caching hints: `ttlMs` + `cacheScope` as a per-registration and per-result concern                                                                                                                                                         | M    |
| SR10 | MRTR re-entry: `InputRequiredResult` emission, `inputResponses` exposure, `requestState` mint/verify seam rejecting tampered state with `-32602` before the handler (§7.4)                                                                 | L    |
| SR11 | `RequestStateCodec`: HMAC-SHA256 default, AEAD-pluggable, JDK-only crypto                                                                                                                                                                  | M    |
| SR12 | Subscriptions: `subscriptions/listen` handling, `SubscriptionFilter` opt-in, `subscriptionId` minting + tagging, acknowledgement notification, fan-out from registry mutations and resource updates                                        | L    |
| SR13 | Per-request log level from `_meta`; suppress `notifications/message` for requests that did not ask                                                                                                                                         | M    |
| SR14 | Progress notifications on the originating response stream                                                                                                                                                                                  | S    |
| SR15 | Cancellation                                                                                                                                                                                                                               | M    |
| SR16 | Completion support                                                                                                                                                                                                                         | S    |
| SR17 | Tool schema validation: JSON Schema 2020-12 with arbitrary keywords, `$ref` resolution + resource bounds, composition-keyword bounds, `structuredContent` as any JSON                                                                      | L    |
| SR18 | Error mapping incl. resource-not-found as `-32602`                                                                                                                                                                                         | S    |
| SR19 | OTel trace context propagation (`traceparent`, `tracestate`, `baggage`) through `_meta`                                                                                                                                                    | S    |
| SR20 | Extensions: declare in capabilities, dispatch unknown-method-to-extension hook                                                                                                                                                             | M    |
| SR21 | Async server API + builder                                                                                                                                                                                                                 | L    |
| SR22 | Sync server API + builder over the async core                                                                                                                                                                                              | M    |

### 5.4 Client runtime

| #   | Item                                                                                                                                                                                                                                | Size |
|-----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| C1  | Request pipeline that stamps `protocolVersion`, `clientCapabilities`, `clientInfo`, and optional `logLevel` into `_meta` on every request                                                                                           | M    |
| C2  | Version selection: optional `server/discover` up front; on stdio, `server/discover` doubles as the backward-compatibility probe                                                                                                     | M    |
| C3  | `resultType` handling: absent ⇒ `complete`                                                                                                                                                                                          | S    |
| C4  | MRTR driver: auto-fulfil loop, 10-round default cap, concurrent dispatch of all `inputRequests`, byte-exact `requestState` echo, fresh request id per retry, 50→250ms backoff for `requestState`-only rounds, manual opt-out (§7.4) | L    |
| C5  | Elicitation handler (form + URL mode), sampling handler, roots provider — wired as `InputRequest` fulfillers rather than request handlers                                                                                           | M    |
| C6  | `subscriptions/listen` client: filter construction, stream consumption, `subscriptionId` demultiplexing, reconnect                                                                                                                  | L    |
| C7  | List-result caching honouring `ttlMs` / `cacheScope`                                                                                                                                                                                | M    |
| C8  | Progress + logging consumers, scoped to the originating request                                                                                                                                                                     | S    |
| C9  | Cancellation                                                                                                                                                                                                                        | S    |
| C10 | Async client API + builder                                                                                                                                                                                                          | L    |
| C11 | Sync client API + builder                                                                                                                                                                                                           | M    |

### 5.5 Transports

| #   | Item                                                                                                                       | Size |
|-----|----------------------------------------------------------------------------------------------------------------------------|------|
| T1  | stdio server transport                                                                                                     | M    |
| T2  | stdio client transport                                                                                                     | M    |
| T3  | Streamable HTTP server: POST-only, no `Mcp-Session-Id`, no GET endpoint, no resumability / `Last-Event-ID` / SSE event ids | L    |
| T4  | Required request headers `Mcp-Method` / `Mcp-Name`, with `HeaderMismatchError` `-32020` on disagreement with the body      | M    |
| T5  | `x-mcp-header`: custom headers derived from tool parameters                                                                | M    |
| T6  | Response-stream (SSE) framing for request-scoped notifications                                                             | M    |
| T7  | Long-lived POST-response stream for `subscriptions/listen`                                                                 | M    |
| T8  | Streamable HTTP client transport                                                                                           | L    |
| T9  | Servlet server transport                                                                                                   | M    |
| T10 | Transport context / security validation (host + origin), body size limits                                                  | M    |
| T11 | HTTP client request customizer chain                                                                                       | S    |

No HTTP+SSE (2024-11-05) transport — Deprecated in this revision and never
supported by v3.

### 5.6 Extensions

| #  | Item                                                                                                                                                                                                      | Size |
|----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| X1 | `extensions` on `ClientCapabilities` / `ServerCapabilities`; registration and negotiation mechanics                                                                                                       | M    |
| X2 | Tasks extension `io.modelcontextprotocol/tasks`: `tasks/get` polling, `tasks/update`, `tasks/cancel`, task handles via result `_meta`, unsolicited handles with no per-request opt-in, task notifications | L    |
| X3 | Task store abstraction + in-memory default                                                                                                                                                                | M    |

### 5.7 Authorization

The SDK provides seams, not flows — same boundary as v2.

| #  | Item                                                                                                                                                                             | Size |
|----|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|
| A1 | Client-side `401` / `WWW-Authenticate` surfacing and authorization error handler hooks                                                                                           | S    |
| A2 | Resource-server metadata surfacing so a host framework can drive discovery                                                                                                       | S    |
| A3 | Documentation of the client obligations the SDK does *not* enforce: `iss` validation (RFC 9207), credentials keyed by issuer, `application_type` in DCR, CIMD preferred over DCR | S    |

OPEN: does v3 take on more of the auth flow than v2 did, or hold the line?
Holding the line is the smaller job and consistent with the ecosystem
(`ext-auth`, Spring Security).

### 5.8 Testing, tooling, docs

| #  | Item                                                                                           | Size |
|----|------------------------------------------------------------------------------------------------|------|
| Q1 | Abstract client/server integration test bases, per transport                                   | L    |
| Q2 | `mcp3-test` fixtures                                                                           | M    |
| Q3 | Schema round-trip tests against `schema/2026-07-28/schema.json`                                | M    |
| Q4 | `conformance-tests` wired to the v3 stack                                                      | L    |
| Q5 | MRTR-specific tests: multi-round, tampered `requestState`, round cap, state-only load shedding | M    |
| Q6 | Docs site pages, `MIGRATION-3.0.md`, README, `VERSIONING.md`, `CHANGELOG.md`                   | M    |

### 5.9 Reuse assessment

TODO — next pass. For each item above, decide: lift as-is, lift-and-adapt,
or write fresh. Candidates likely to lift cleanly are the ones with no
protocol-era coupling (json layer, util, URI templates, HTTP plumbing,
schema validation); the protocol core and both API surfaces are likely
fresh.

---

## 6. Lifecycle: `server/discover` + per-request `_meta`

TODO

## 7. MRTR: the handler programming model

**This is the pivotal API decision.** Today:

```java
// v2 — handler blocks on a server-initiated request
McpSchema.ElicitResult r = exchange.createElicitation(request);
```

Under MRTR there is no server→client request and no session to suspend in, so
the handler must return/signal `InputRequiredResult` and be re-entered on the
client's retry, receiving `inputResponses` plus whatever it stashed in
`requestState`.

### 7.1 Prior art

All three sibling SDKs have already shipped `2026-07-28` and MRTR on `main`.
Surveyed at: typescript-sdk `7b781ed4`, python-sdk `e7284ed1`, csharp-sdk `609499b2`.

**TypeScript** — union return type.

- Handler returns `CallToolResult | InputRequiredResult`. Builders:
  `inputRequired({ inputRequests, requestState })`, `inputRequired.elicit(...)`,
  `inputRequired.elicitUrl(...)`; reads via `acceptedContent(ctx.mcpReq.inputResponses, key)`
  and the typed `ctx.mcpReq.requestState<T>()`.
- `requestState` integrity: SDK ships `createRequestStateCodec({ key, ttlSeconds })`
  (`mint` / `verify`), wired into `ServerOptions.requestState.verify`. The seam
  rejects tampered or expired state with a wire-level `-32602` **before** the
  handler runs.
- The 2026 path serves every request from a fresh server instance — state on the
  wire is the only thing that survives between rounds.
- Throw-style `UrlElicitationRequiredError` is explicitly *not* available toward
  2026-07-28 requests; URL elicitation rides MRTR.
- Client auto-drives the retry loop (`inputRequired.autoFulfill`,
  `inputRequired.maxRounds`, default 10); `allowInputRequired: true` opts out to
  handle `InputRequiredResult` manually.
- Dual-era: one server binary serves both eras, branching on `isLegacyRequest(...)`
  (`test/integration/test/server/dualEraStdio.test.ts`).

**Python** — union return type.

- Low-level: `async def call_tool(ctx, params) -> CallToolResult | InputRequiredResult`,
  reading `params.input_responses`. High-level `MCPServer` the same, via
  `ctx.input_responses`.
- `requestState` integrity: `RequestStateSecurity(codec=...)` with `seal`/`unseal`,
  raising `InvalidRequestState`. Documented example is an AES-GCM envelope over a
  KMS-wrapped data key.
- Client auto-drives: `Client(..., elicitation_callback=...)` — MRTR is invisible
  to client authors. `session.call_tool(..., allow_input_required=True)` to drive
  the loop by hand.
- Driver (`src/mcp/client/_input_required.py`): `DEFAULT_INPUT_REQUIRED_MAX_ROUNDS = 10`;
  dispatches all `input_requests` **concurrently**; a `requestState`-only result
  sleeps with exponential backoff (50ms → 250ms cap, reset by any leg carrying
  requests) and retries empty; `request_state` passed byte-exact and never
  inspected; an `ErrorData` from a callback aborts the loop.
- Its own comment notes 10 is the cross-SDK number: configurable in TS/Python,
  a hard constant in csharp-sdk and go-sdk.

**C#** — exception, plus a session-gated continuation facade.

- Primary authoring model: `throw new InputRequiredException(inputRequests, requestState)`
  from anywhere in the handler. Handler return types are unchanged. Retry data
  arrives on `context.Params.InputResponses` / `.RequestState`.
- `InputResponse.Deserialize(InputResponse.ElicitResultJsonTypeInfo)` — there is
  no on-the-wire discriminator; the expected type follows from the matching
  `InputRequest.Method` in the original `inputRequests` map.
- `server.IsMrtrSupported` guard, with a documented spectrum of down-level
  fallbacks (guidance message → explicit non-interactive tool argument).
- It *also* keeps the blocking `ElicitAsync`/`SampleAsync`/`RequestRootsAsync`,
  implemented as a genuine suspended continuation (`MrtrContext`, `MrtrExchange`,
  `MrtrContinuation`: the handler task parks on a `TaskCompletionSource`, the
  pipeline emits `InputRequiredResult`, the retry completes the TCS). This needs
  in-memory session state, so it throws
  `InvalidOperationException("… is not supported in stateless mode.")` when
  stateless, and rejects concurrent exchanges from one handler.
- Backcompat in the other direction: on ≤2025-11-25 **stateful** sessions,
  `InputRequiredException` is bridged to real `elicitation/create` /
  `sampling/createMessage` / `roots/list` JSON-RPC plus a handler retry, capped
  at 10 rounds. On ≤2025-11-25 stateless it is an error.
- Session-mode gating: HTTP `SessionMode = Stateful` *refuses* 2026-07-28 with
  `UnsupportedProtocolVersion`; `StatefulForInitializeClients` serves 2026-07-28
  statelessly while still issuing sessions to `initialize` clients.

### 7.2 What the three agree on

1. **Server-side, the raw re-entrant model is the only model** on the stateless
   2026-07-28 path. No SDK offers a continuation facade there. C# has one, and
   it is explicitly gated on having a session (stdio or legacy stateful HTTP).
2. **Client-side, the loop is auto-driven**, so client authors keep
   callback-style elicitation/sampling APIs and never see `InputRequiredResult`
   unless they ask for it. Cap of **10 rounds** is a cross-SDK convention.
3. **`requestState` is attacker-controlled input.** All three ship an
   integrity-sealing helper and a verify seam that rejects before the handler runs.
4. `requestState`-only results are a legitimate load-shedding / deferral tool,
   and clients retry them automatically with backoff.

### 7.3 Where they diverge — the actual Java decision

How the handler *signals* an incomplete result:

- **(A) Union return**, as TS and Python: `sealed interface CallToolOutcome
  permits CallToolResult, InputRequiredResult`. Type-honest, exhaustive
  switching, no exception control flow. Cost: every handler signature and every
  `Mono<CallToolResult>` in the codebase changes, and composition helpers must
  thread the union through.
- **(B) Exception**, as C#: `Mono.error(new InputRequiredException(...))`.
  Signatures stay `Mono<CallToolResult>`; the signal composes through arbitrary
  helper depth without changing intermediate types. In Reactor an error channel
  is a first-class, non-exceptional control path — this is far less of a smell in
  Java/Reactor than plain `throw` would be. Cost: not visible in the type, so
  nothing forces a handler author to think about re-entry.

**DECISION (D4):** open (non-sealed) union return types, no blocking facade.
Rationale: we are writing new packages, so the "signatures stay unchanged"
advantage that drove C# to exceptions is worth nothing to us; the outcome is
visible in the handler type; and a facade that only works when a session exists
has no place in a stateless-only SDK.

The union is deliberately *not* sealed. `ResultType` is open on the wire
(`"complete" | "input_required" | string`, schema line 216), and extensions use
that: Tasks answers a `tools/call` with `CreateTaskResult` /
`resultType: "task"`. A sealed union would force core to list every extension
result in its `permits` clauses. Accepted cost: no compiler-checked
exhaustiveness, so a `switch` over an outcome needs a `default` branch.

### 7.4 The Java shape

MRTR applies to exactly three methods — `tools/call`, `prompts/get`,
`resources/read` (schema lines 1849, 1651, 1245). Everything else returns its
result unconditionally.

#### Union types

```java
public interface CallToolOutcome extends Result {
}

public interface GetPromptOutcome extends Result {
}

public interface ReadResourceOutcome extends Result {
}
```

Core implementations: `CallToolResult implements CallToolOutcome` (and likewise
for prompts and resources); `InputRequiredResult` implements all three. An
extension result implements the outcomes for the methods it applies to, e.g.
`CreateTaskResult implements CallToolOutcome`.

An extension result type must be registered with the SDK, together with its
`resultType` value and the extension id it belongs to:

- **Server:** before sending, the SDK checks that the request's
  `clientCapabilities` declare that extension. If they don't, it fails the
  request with an internal error instead of sending a result the client can't
  parse ("never return a task to a client that did not declare support").
- **Client:** the response is deserialized by looking up `resultType` for that
  method. `complete` and `input_required` are built in; other values come from
  registered extensions. An unregistered `resultType` is an SDK-level error.

The client-side handling of an extension result (e.g. Tasks polling
`tasks/get` until it has the final `CallToolResult`) belongs to the extension.
See §10.

**Generics wart to get right up front.** `Mono<CallToolResult>` is *not*
assignable to `Mono<CallToolOutcome>`, so the handler type must be declared
covariantly or every handler is forced to write `Mono.<CallToolOutcome>just(...)`:

```java
// async
BiFunction<McpRequestContext, CallToolRequest, Mono<? extends CallToolOutcome>> callHandler
// sync
BiFunction<McpSyncRequestContext, CallToolRequest, CallToolOutcome> callHandler
```

Handler side:

```java
(ctx,req)->{
DeployState state = ctx.requestState(DeployState.class);   // verified, decoded
    if(state ==null){
        return Mono.

just(InputRequiredResult.builder()
            .

inputRequest("confirm",ElicitRequest.form("Deploy to "+env+"?", CONFIRM_SCHEMA))
        .

requestState(ctx.mintRequestState(new DeployState("confirm", env)))
        .

build());
        }
ElicitResult confirmed = req.inputResponses().as("confirm", ElicitResult.class);
    ...
            return Mono.

just(CallToolResult.builder().

addTextContent("deployed").

build());
        }
```

#### `resultType` is SDK-managed, not user-visible

`Result.resultType` is required on the wire for every result. Users never set
it: the serializer emits `"complete"` for every concrete result type,
`"input_required"` for `InputRequiredResult`, and the registered value for an
extension result type. On the client, an absent
`resultType` is read as `"complete"` (spec requirement for down-level servers).

Naming trap: `CompleteResult` in the schema (line 2644) is the
`completion/complete` result and has nothing to do with `resultType: "complete"`.
Do not let those two collide in `McpSchema`.

#### `InputResponse` has no wire discriminator

```
InputRequest  = CreateMessageRequest | ListRootsRequest | ElicitRequest   // has `method`
InputResponse = CreateMessageResult  | ListRootsResult  | ElicitResult    // has nothing
```

`InputRequest` deserializes polymorphically on `method`. `InputResponse` cannot
— and the server cannot recover the mapping from context either, because on a
stateless retry it no longer remembers the `inputRequests` it minted; only
`requestState` survives. So `InputResponse` must hold the raw JSON and expose
typed access on demand, the same conclusion C# reached:

```java
public final class InputResponses {
    public <T> T as(String key, Class<T> type);

    public <T> T as(String key, TypeRef<T> type);

    public ElicitResult elicitResult(String key);            // convenience

    public CreateMessageResult createMessageResult(String key);

    public ListRootsResult listRootsResult(String key);

    public Set<String> keys();
}
```

If a handler needs to know which shape to expect across rounds, it encodes that
in `requestState` — which is what the sibling SDKs' examples all do.

#### `requestState` integrity is part of the SDK, not the user's problem

`requestState` round-trips through the client and is therefore
attacker-controlled on re-entry. All three sibling SDKs ship a sealing helper
plus a verify seam that rejects before the handler runs. We do the same, with
JDK-only crypto (`javax.crypto.Mac`, `AES/GCM` — no new dependency):

```java
RequestStateCodec codec = RequestStateCodec.hmacSha256(key, Duration.ofMinutes(10));
// server-level seam; tampered or expired state fails the request with -32602
// (Invalid Params) before any handler is entered
McpServer.

async().

requestStateCodec(codec)
```

Default is HMAC-SHA256 over a JSON payload with an expiry, Base64url-encoded.
Pluggable for AEAD when the payload must also be confidential.

OPEN: is a codec mandatory? Refusing to start a server that returns
`requestState` without one is the safe default but may annoy stdio users.
Leaning: warn loudly, don't refuse.

#### Client side auto-drives the loop

Client authors keep callback-style APIs and never see `InputRequiredResult`
unless they ask. Matching the cross-SDK conventions:

- default cap **10 rounds**, configurable; exceeding it is an SDK-level error;
- all `inputRequests` in a round dispatched **concurrently**
  (`Flux.fromIterable(...).flatMap(...)`), first failure cancels the siblings;
- a `requestState`-only result (load shedding) is retried with no responses
  after a backoff of 50ms doubling to a 250ms cap, reset by any round that
  carries requests;
- `requestState` echoed byte-exact, never inspected;
- each retry mints a **fresh JSON-RPC request id**;
- opt out to drive the loop manually and receive `InputRequiredResult` raw.

```java
McpClient.async(transport)
    .

elicitationHandler(...)      // unchanged shape from v2
    .

samplingHandler(...)
    .

inputRequired(cfg ->cfg.

maxRounds(10).

autoFulfill(true))
        .

build();
```

#### Deprecated-on-arrival interaction

Sampling and Roots are deprecated in this revision, yet `InputRequest` still
permits `CreateMessageRequest` and `ListRootsRequest`. We implement all three
`InputRequest` kinds — MRTR is the only channel left for them, so omitting them
would make the deprecated features unusable rather than merely discouraged.
See the open questions list on whether to implement them at all.

DEPENDS ON §6: where the handler reads verified `requestState` from — a
per-request context object replacing `McpAsyncServerExchange`. Settled there.

## 8. Transports

TODO — Streamable HTTP changes, `subscriptions/listen`, resumability removal,
required headers, `x-mcp-header`.

## 9. Client API surface

TODO

## 10. Extensions mechanism + tasks

TODO

Constraint from D4: an extension may contribute result types for existing
methods. It registers the type, its `resultType` value and its extension id
(§7.4), and it owns the client-side handling of that result.

OPEN: how an extension result handler composes with the client's MRTR loop. A
task can itself move to `input_required`, with responses sent via
`tasks/update` rather than by retrying the original request.

## 11. Dual-era bridge (later)

Out of scope for 3.0. A future `mcp-dual` module could depend on both `mcp-core`
and `mcp3-core` and offer a client that negotiates era via `server/discover`
(with the STDIO backward-compatibility probe) and falls back to `initialize`.
D3 exists so this stays possible.

## 12. Testing

TODO — integration matrix, what `mcp3-test` exposes.

### 12.1 Validation criteria: the conformance suite

The implementation is validated against the MCP conformance suite
([modelcontextprotocol/conformance](https://github.com/modelcontextprotocol/conformance)),
pinned to `@modelcontextprotocol/conformance@0.2.0-alpha.11`. "Done" means the
server and the client both pass the `2026-07-28` requirement set, excluding
authorization (D5).

`--requirements 2026-07-28` runs exactly the scenarios that revision requires,
frozen at release, at the `2026-07-28` (stateless) wire version. It replaces
`--suite`, `--spec-version` and `--scenario`. List the set with:

```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.11 list --requirements 2026-07-28
```

**Server.** Assuming a conformance server runs at `http://localhost:8080/mcp`:

```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.11 server \
  --url http://localhost:8080/mcp --requirements 2026-07-28
```

The server set has no auth scenarios. It covers `server-stateless`, tools,
resources, prompts, completion, `caching`, `dns-rebinding-protection`, and the
`input-required-result-*` MRTR scenarios (tampered `requestState`, multi-round,
capability check, and so on).

**Client.** Assuming `target/client.jar` is a conformance client:

```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.11 client \
  --command "java -jar target/client.jar" --requirements 2026-07-28
```

The runner appends the server URL as the last argument and sets
`MCP_CONFORMANCE_SCENARIO`, so the client jar dispatches on the scenario name,
as `conformance-tests/client-jdk-http-client` does today. Non-auth scenarios in
the client set: `tools_call`, `request-metadata`, `sep-2322-client-request-state`,
`http-standard-headers`, `http-custom-headers`, `http-invalid-tool-headers`,
`json-schema-ref-no-deref`.

**Excluding auth.** The client set includes 25 `auth/*` scenarios, and
`--requirements` can't be combined with `--scenario` or `--suite`. The suite has
no exclude flag, and baseline entries don't support wildcards. So each `auth/*`
scenario is listed explicitly in a `client:` section of an expected-failures
baseline, passed with `--expected-failures`. The baseline makes a listed
scenario that starts passing fail the run as stale. That is fine here, because
the SDK does not implement auth.

**Not scored, but reported.** The `tasks-*` server scenarios (extension, §10),
`json-schema-2020-12`, `http-header-validation` and
`http-custom-header-server-validation` (pending), and
`json-schema-2020-12-preservation` (client, added after release) run without
affecting the result. We track them, but they are not a gate.

**Harness.** New conformance server and client apps for the new core, alongside
the existing `conformance-tests` modules, which keep validating the existing
stack against `2025-11-25`. Module names follow D3. They get their own
`conformance-baseline.yml` and CI jobs in `.github/workflows/conformance.yml`,
with the same pinned suite version. Every scenario also emits
`wire-schema-valid`, which validates each JSON-RPC message we send against the
`2026-07-28` JSON schema. This covers much of S22 on the wire.

## 13. Phasing

TODO — PR breakdown.

---

## Open questions

- §7.4 Is a `RequestStateCodec` mandatory, or warn-only? (leaning: warn)
- §7.4 Naming: `CallToolOutcome` / `GetPromptOutcome` / `ReadResourceOutcome`.
- §4 parent pom for `mcp3-*`.
- Do we ship `mcp3-json-jackson2` at all, or is v3 Jackson-3-only?
- Sync API: does v3 keep the sync/async duality, or async-only with a sync
  wrapper?
- Roots/Sampling/Logging are deprecated on arrival. Does v3 implement them at
  all, or skip them and tell users to stay on v2?
