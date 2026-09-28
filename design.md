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

### D7 — No Reactor: blocking, virtual-thread-friendly APIs only

`mcp-core-modern` does not use Project Reactor, neither in its public API nor in
its implementation, and does not depend on it. There is a single API surface: plain blocking
calls (`CallToolResult callTool(...)`, handlers returning `CallToolOutcome`
directly). No `Mono` / `Flux`, no sync/async pair.

The implementation must be virtual-thread-friendly:
- no thread pools owned by the SDK for request handling; where the SDK needs to
  run work concurrently (e.g. dispatching a round of `inputRequests`), the
  `Executor` is user-configurable;
- no reliance on expensive-per-thread state (`ThreadLocal` caches).

The baseline of the `2026-07-28` modules (`mcp-core-modern`, `mcp-test-modern`,
`mcp-demo-*`) is Java 25. `mcp-json` stays on Java 17, since `mcp-core` depends
on it. On Java 25, `synchronized` no longer pins the carrier thread (JEP 491),
so it needs no special treatment. Preview APIs such as `StructuredTaskScope`
are not used: preview class files only run on the exact JDK they were compiled
for, with `--enable-preview`.

*Rationale:* the sync/async split is the main source of API duplication in the
existing SDK (see §2 goals). With virtual threads, blocking code scales without
a reactive API, and it is simpler to write, read and debug.

### D8 — Nullability is declared with JSpecify

`mcp-core-modern` uses [JSpecify](https://jspecify.dev) (`org.jspecify:jspecify`)
annotations. Every package is `@NullMarked`, so types are non-null unless
annotated `@Nullable`.

In `McpSchema`, nullability follows the spec:
- spec-required fields are non-null, and are rejected at construction;
- optional fields are `@Nullable`;
- the parameters of the `@JsonCreator` `fromJson` factories are all
  `@Nullable`, since a peer may omit any field on the wire.

Not annotated for now: the value types of raw JSON maps
(`Map<String, Object>`), which can hold JSON `null`s.

*Rationale:* the schema has many optional fields, and records make it easy to
pass `null` where the spec requires a value. Annotations make the contract
visible in the API and checkable by tools (IDEs, NullAway), and JSpecify is the
standard, tool-neutral choice.

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

### 8.1 Shared-connection transports write from a single writer thread

A transport that multiplexes many requests over one connection (stdio, or a
custom transport over a TCP or Unix-domain socket) must:
- send every outgoing message through a single writer thread fed by a queue;
- never write to the shared stream from a handler's thread. The
  `notifications` consumer passed to `McpServer.handleRequest` enqueues the
  notification and returns;
- serialize each message on the thread sending it, and queue the bytes, so
  that one large result does not delay every other message, and a message
  that fails to serialize can be dropped (or, for a response, replaced by an
  `INTERNAL_ERROR`) without stopping the writer;
- bound the queue, by bytes. When it is full, e.g. because the client does not
  read, the senders wait, and a cancelled handler stops waiting. `StdioServerTransport`
  bounds it to 16MB with a `Semaphore` rather than a bounded queue, so that the
  end-of-output marker can always be queued.

*Rationale:* cancellation may interrupt the handler thread (SR15). On a virtual
thread, an interrupt during a blocking socket or `InterruptibleChannel`
operation closes the socket or channel. If a handler thread were interrupted
while writing, it would close the connection for every in-flight request, not
just the cancelled one.

Streamable HTTP has one response stream per request, but over HTTP/2 the
streams share one TCP connection, and whether an interrupt during a servlet
write closes it depends on the container (unverified). So the HTTP transport
also keeps writes off the handler thread (§8.2).

This constraint is in the javadoc of `StdioServerTransport`.

### 8.2 Cancellation is implemented by the transport, by interrupting the handler thread

The cancellation signal depends on the transport
(`basic/patterns/cancellation.mdx`):
- **stdio:** the client MUST send `notifications/cancelled` with the request
  id. The server SHOULD stop the work and SHOULD NOT send a response.
- **Streamable HTTP:** closing the response stream is the cancellation. The
  server MUST treat a client disconnect as cancellation, and MUST NOT send
  anything further for that request. No client-to-server notification is
  defined over HTTP.

The transport cancels a request by interrupting the thread that runs
`McpServer.handleRequest`. `McpServer` has no cancellation API and no
in-flight registry:
- it has no connection, and request ids are only unique among the in-flight
  requests of one sender (`basic/index.mdx`), so a server-level registry would
  let one client cancel another's request. All four sibling SDKs ignore a
  `notifications/cancelled` POST on stateless HTTP for that reason;
- the transport owns the thread and the response, so it is the one that can
  interrupt the first and drop the second.

The interrupt is the cancellation signal handlers see. Blocking JDK calls
already react to it: `Thread.sleep`, `BlockingQueue`, `Future.get`,
`HttpClient.send`, and, on a virtual thread, `java.net.Socket` I/O. With one
thread per request, the interrupt can only reach the cancelled request. With a
shared pool it could land on whatever task reuses the thread.

**`McpServer`.** When the handler fails and the thread is interrupted, or an
`InterruptedException` is in the cause chain, the request was cancelled. The
server logs it at debug instead of error, restores the interrupt status if the
handler cleared it, and returns an `INTERNAL_ERROR` that the transport drops.
A handler that finishes despite the interrupt returns its result as usual, and
the transport drops that too.

**stdio**, and any transport multiplexing requests over one connection:

```java
// Reader thread. It reads requests and cancellations in order, so a thread is
// registered and started before any cancellation of its id is read.
case JSONRPCRequest request -> {
    Thread thread = threadFactory.newThread(() -> {   // default: Thread.ofVirtual().factory()
        JSONRPCResponse response = server.handleRequest(context, request, outbox::add);
        if (inFlight.remove(request.id(), Thread.currentThread())) {
            outbox.add(response);                     // removing the entry decides the race
        }
    });
    if (inFlight.putIfAbsent(request.id(), thread) != null) {
        outbox.add(invalidRequest(request.id(), "Duplicate request id"));
        return;
    }
    thread.start();
}
case JSONRPCNotification n when METHOD_NOTIFICATION_CANCELLED.equals(n.method()) -> {
    Thread thread = inFlight.remove(requestId(n));    // unknown or completed: ignored
    if (thread != null) {
        thread.interrupt();
    }
}
```

- `inFlight` is a `ConcurrentMap<RequestId, Thread>`, scoped to the
  connection. Whichever of the handler or the cancellation removes the entry
  decides whether the response is sent.
- `outbox` is drained by the single writer thread of §8.1.
- The transport takes a `ThreadFactory` rather than D7's `Executor`, since it
  needs the `Thread` to interrupt.

**Streamable HTTP.** The handler runs on its own thread, not the container's.
The servlet thread writes the messages the handler queues for the request. It
interrupts the handler when a write fails, or when the container reports the
disconnect. The servlet API has no portable way to notice a disconnect while
nothing is written, so a silent handler learns about it late. Periodic SSE
comments would bound that delay, at the cost of always answering with SSE.

**Limits.** These must go in the handler javadoc:
- The interrupt is cooperative. It does not stop a CPU-bound loop that never
  checks `Thread.interrupted()`, code that swallows `InterruptedException`,
  `synchronized`, or `ReentrantLock.lock()`.
- It does not reach threads the handler starts. `StructuredTaskScope` would
  pass it on, but it is a preview API (D7), so handlers pass it on themselves.
- On a virtual thread, it closes the `java.net.Socket` or `InterruptibleChannel`
  the handler is blocked on. A pooled JDBC connection used at that moment is
  broken, and the pool must evict it.

*Rationale:* the spec defines cancellation per transport, and only the
transport knows which requests a cancellation can apply to. Interrupting a
virtual thread is Java's built-in cancellation signal for blocking code, so
handlers need no new API.

OPEN: handlers are `BiFunction`s, so a handler that blocks must catch
`InterruptedException`, restore the interrupt status and wrap it. SDK
functional interfaces declaring `throws Exception` would remove that
boilerplate. Changing it later breaks the API.

OPEN: the spec contradicts itself on a cancelled `subscriptions/listen`:
`cancellation.mdx` says no response, `subscriptions.mdx` says the server
SHOULD send a `complete` result. To settle with SR12.

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

### 13.1 Status: basic server

Done: `McpSchema` server-side types and `McpServer` with its builder in
`mcp-core-modern`, and the stdio server transport (T1),
`StdioServerTransport`, as described in §8.1 and §8.2. Tests in
`mcp-test-modern`.

The stdio transport:
- `run()` blocks, reading `stdin` until end of stream. At end of stream, it
  cancels the in-flight requests, writes the messages already queued, and
  returns.
- Answers invalid JSON with `-32700`, an invalid JSON-RPC envelope, a
  duplicate in-flight request id, or a line longer than `maxMessageSize`
  (16M characters by default) with `-32600`, and carries on reading.
- Ignores the responses a client sends, and every notification except
  `notifications/cancelled`.
- Queues up to 16MB of serialized messages for the writer thread (§8.1).

The Streamable HTTP server transport (T3, T4, T6, T9, T10),
`HttpServletServerTransport`, is a servlet:
- `POST` only; every other method gets `405` with `Allow: POST`. Client
  notifications get `202` and are ignored.
- Before dispatch, in order: `Origin` / `Host` (`403`; by default, only
  localhost `http` origins are allowed, and any host), `Accept` must list both
  `application/json` and `text/event-stream` (`406`), `Content-Type` must be
  `application/json` (`415`), body size (`413`, 16MB by default), and a
  single JSON-RPC request or notification (`-32700` / `-32600`).
- `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` must be present once,
  and match the body, else `-32020`. `Mcp-Name` is trimmed, and decoded from
  `=?base64?...?=`. These checks are skipped when the body lacks the required
  `_meta` fields: the server then answers `-32602`, as the conformance suite
  expects.
- A response sent as JSON gets the HTTP status of its error code, one table
  whoever produced the error, as in the Python SDK: `404` for `-32601`, `400`
  for `-32700`, `-32600`, `-32602`, `-32020`, `-32021` and `-32022`, `200`
  otherwise.
- The response is JSON, unless the handler emits a notification first. It
  then becomes an SSE stream, `200` with `X-Accel-Buffering: no`, with one
  `data:` line per message, ending with the response.
- As §8.2 describes: the handler runs on a thread from a `ThreadFactory`
  (virtual by default), and the container thread writes. The two share a
  handoff queue of up to 64 notifications; a sender waits beyond that. A
  failed write closes the queue and interrupts the handler.

`mcp-demo-server` (D6) runs a server that uses every feature above:
plain, structured-output, streaming (progress + cancellation) and multi
round-trip tools, resources with caching hints, a resource template, a prompt
and completions. `java -jar mcp-demo-server/target/mcp-demo-server-*.jar`
serves Streamable HTTP at `http://localhost:8080/mcp` (port from `MCP_PORT`),
on an embedded Tomcat bound to the loopback interface. `MCP_MODE=stdio`
switches to stdio.

Not implemented yet:
- `subscriptions/listen` (and the `notifications/subscriptions/acknowledged`
  types), with the `list_changed` and `resources/updated` notifications it
  carries. Registries are fixed at `build()`, so nothing emits `list_changed`
  yet, and a user-supplied `capabilities(...)` can advertise `listChanged` or
  `subscribe` without the server supporting them.
- Pagination: every list is returned in a single page, and any `cursor` is
  rejected with `-32602`.
- Validation of `tools/call` arguments against the tool's `inputSchema`. Tool
  names, the validity of `inputSchema` / `outputSchema`, and the conformance
  of `structuredContent` to `outputSchema` are checked.
- Unsupported schema dialects: schemas declaring a `$schema` other than
  2020-12 are accepted without meta-schema validation (the `mcp-json`
  `JsonSchemaValidator` behavior) instead of rejected with an error.
- `$ref` resolution and composition-keyword bounds (SR17) are whatever the
  `JsonSchemaValidator` implementation does, not enforced by the server.
- `x-mcp-header` constraints on tool input schemas, checked at registration.
- Over Streamable HTTP, a handler that emits nothing learns about a client
  disconnect only when its response is written: the servlet API can't detect
  a disconnect while nothing is written. There are no SSE keep-alive comments
  (§8.2), so long silent requests don't upgrade to a stream.
- Server-side validation of `Mcp-Param-*` headers against `x-mcp-header`
  tool parameters (T5): the transport can't see the tool schemas.
  (`http-custom-header-server-validation` is not scored.)
- `MissingRequiredClientCapabilityError` (`-32021`) raised by handlers: the
  server raises it when an `InputRequiredResult` carries an elicitation mode
  the client did not declare, but handlers have no way to raise it before
  doing any work, e.g. for a capability required by an extension.
- MRTR server obligations (SR10, SR11): no `requestState` integrity
  protection.
- Extensions (SR20): `ServerCapabilities.extensions` can be declared, but
  there is no way to dispatch extension methods or extension `resultType`s.
- OTel trace context propagation through `_meta` (SR19).
- Single-pass JSON-RPC deserialization: `McpSchema.deserializeJsonRpcMessage`
  parses every incoming message into a `Map`, then `convertValue`s the map
  into the envelope record, so each message is processed twice. Ported as-is
  from `mcp-core`, which does the same. Fix it for every transport at once, in
  its own change, backed by a benchmark.

On hold, deprecated in `2026-07-28` (see open questions):
- Logging (P13, SR13): the request's `logLevel` is parsed and exposed on
  `McpRequestContext`, but handlers cannot emit `notifications/message`.
- Sampling (P20): no `CreateMessageRequest` `InputRequest`.
- Roots (P21): no `ListRootsRequest` `InputRequest`.

---

## Open questions

- §7.4 Is a `RequestStateCodec` mandatory, or warn-only? (leaning: warn)
- §7.4 Naming: `CallToolOutcome` / `GetPromptOutcome` / `ReadResourceOutcome`.
- §8.2 Should handler functional interfaces declare `throws Exception`?
- §4 parent pom for `mcp3-*`.
- Do we ship `mcp3-json-jackson2` at all, or is v3 Jackson-3-only?
- Roots/Sampling/Logging are deprecated on arrival. Does v3 implement them at
  all, or skip them and tell users to stay on v2? (on hold for now, see §13.1)
