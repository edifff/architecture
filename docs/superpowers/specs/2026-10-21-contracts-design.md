# Sub-project 1: Contracts (protobuf + gRPC codegen) — Design

**Date:** 2026-10-21
**Status:** awaiting author review
**Parent effort:** layered-architecture lab — 6 sub-projects:
(1) Contracts [this spec] → (2) Storage service → (3) Chat service →
(4) Frontend (Netty + Facade) → (5) Compose wiring → (6) Performance (k6 + Prometheus/Grafana).

## Context

The current project is a single-module Maven app: a Jetty chat server (`ru.archlabs.chat`)
with JDBC storage (`PostgresChatStorage`), tested via Testcontainers. The lab rebuilds it
as three layered services — `frontend` (presentation + Facade), `chat` (domain),
`storage` (persistence) — that interact exclusively via protobuf over gRPC.
The client-facing protocol (HTTP + JSON + SSE) is unchanged and stays at the frontend.

This sub-project lays the shared foundation only: the protobuf contracts every service
consumes, the codegen build, and the multi-module reactor the later sub-projects plug into.

## Goals

1. Restructure the repository into a Maven multi-module reactor without changing behavior.
2. Define the inter-container contracts: `storage.proto` and `chat.proto` (+ shared messages).
3. Configure protobuf/gRPC code generation; generated sources are **not** committed
   (consistent with the earlier jOOQ decision — they are rebuilt each build).
4. Fix the error-code mapping between domain exceptions and gRPC statuses, so all later
   sub-projects implement one consistent convention.
5. Keep everything that worked before working: `mvn test` at the root, the Docker image
   build, the compose `tests` service.

## Non-goals

- No service implementations (no gRPC servers/clients beyond generated stubs).
- No changes to the client-facing HTTP protocol, to `ChatServerImpl`, or to storage logic.
- No compose topology changes (still `chat` + `db` until sub-project 5).
- No metrics/histograms yet (per-service, later sub-projects; aggregation in 6).

## Decisions

### D1. Module layout

Root `pom.xml` becomes the parent (`packaging: pom`) with modules:

- `proto/` — `.proto` files, codegen, compiled stubs. Dependencies: `grpc-netty-shaded`,
  `grpc-protobuf`, `grpc-stub`, `protobuf-java`, `javax.annotation-api` (provided —
  generated stubs carry `@javax.annotation.Generated`).
- `app/` — the existing single-module app, moved **verbatim** (`git mv src app/src`,
  build plugins carried over to `app/pom.xml`). Sub-projects 2–4 move code out of `app/`
  into new modules (`storage`, `chat-service`, `frontend`); sub-project 5 deletes `app/`.

`app` does not depend on `proto` yet — nothing in it speaks gRPC until later sub-projects.

### D2. Versions (pinned, verified against Maven Central 2026-10-21)

| Artifact | Version |
|---|---|
| `io.grpc:grpc-netty-shaded` (+ `grpc-protobuf`, `grpc-stub`) | `1.85.1` |
| `com.google.protobuf:protobuf-java` | `4.36.2` |
| `org.xolstice.maven.plugins:protobuf-maven-plugin` | `0.6.1` |
| `javax.annotation:javax.annotation-api` | `1.3.2` |

`grpc-netty-shaded` (not `grpc-netty`) is deliberate: the frontend module will carry its
own `io.netty:netty-codec-http`, and unshaded gRPC pins a different Netty line — shading
eliminates the conflict.

Codegen downloads `protoc` 26.x-ish (plugin `os-maven-plugin` extension picks the platform
binary) and `protoc-gen-grpc-java`; both pinned via plugin `<configuration>` versions
matching `protobuf-java`/`grpc` above.

### D3. Proto files

Three files under `proto/src/main/proto/`, all with
`option java_multiple_files = true;` and `optimize_for = SPEED;` omitted (default):

**`common.proto`** — `package common;`, `option java_package = "ru.archlabs.proto.common";`

```proto
message Message   { int64 id = 1; string sender = 2; string text = 3; google.protobuf.Timestamp sent_at = 4; }
message UserState { string name = 1; bool online = 2; google.protobuf.Timestamp last_connected = 3; bool notifications_enabled = 4; }
message Empty     {}
```

**`storage.proto`** — `package storage;`, `option java_package = "ru.archlabs.proto.storage";`
service `ChatStorage` — mirrors the `ChatStorage` interface 1:1:

| RPC | Domain method | Notes |
|---|---|---|
| `Connect(ConnectRequest{name, at})` | `connect` | |
| `Disconnect(DisconnectRequest{name, at})` | `disconnect` | |
| `SetNotificationsEnabled(Request{name, enabled})` | `setNotificationsEnabled` | |
| `Append(AppendRequest{sender, text, sent_at}) → common.Message` | `append` | |
| `UndeliveredNotifications(Request{recipient}) → repeated common.Message` | `undeliveredNotifications` | |
| `ClearNotifications(ClearRequest{recipient, repeated int64 message_ids})` | `clearNotifications` | |
| `History(HistoryRequest{}) → repeated common.Message` | `history` | |
| `User(UserRequest{name}) → common.UserState` | `user` | absent user → `NOT_FOUND` |
| `OnlineUsersWithNotificationsEnabled(Request{}) → repeated common.UserState` | same name | |
| `ResetOnlineUsers(ResetRequest{})` | `resetOnlineUsers` | currently private, called by the storage ctor; becomes an explicit RPC so the **chat service** can invoke it at its startup (the semantic "after restart nobody is online" belongs to the layer that owns sessions) |

**`chat.proto`** — `package chat;`, `option java_package = "ru.archlabs.proto.chat";`
service `ChatService` — the domain surface the frontend consumes:

| RPC | Maps to (current HTTP behavior) |
|---|---|
| `Connect(ConnectRequest{name}) → ConnectResponse{user}` | `POST /sessions/{u}` |
| `Disconnect(DisconnectRequest{name})` | `DELETE /sessions/{u}` |
| `Send(SendRequest{name, text}) → common.Message` | `POST /messages/{u}` |
| `History(HistoryRequest{name}) → repeated common.Message` | `GET /messages/{u}` |
| `SetNotificationsEnabled(Request{name, enabled})` | `PUT /notifications/{u}` |
| `User(UserRequest{name}) → common.UserState` | `GET /users/{u}` |
| `Subscribe(SubscribeRequest{name}) → stream common.Message` | the SSE backbone: the frontend opens one stream per connected user; the chat service pushes live notifications down it |

Session state lives in the chat service keyed by username (as today, in
`ChatHttpServer.connections`). `Connect` registers the session; `Subscribe` attaches the
delivery stream (a user may be connected without an open subscription, exactly as today a
client may `POST /sessions` without opening `/events`); `Disconnect` tears both down.
The per-user pending queue (`ClientConnection.pending`) moves into the chat service —
the layer that owns delivery owns the queue.

### D4. Error mapping (binding convention for sub-projects 2–4)

Domain exception → gRPC `Status`, mapped **per RPC** (the same exception type maps
differently depending on the RPC, because `IllegalStateException` carries several meanings):

| Condition | RPC context | Status |
|---|---|---|
| "User is already online" | `Connect` | `ALREADY_EXISTS` |
| "Session is not active" | `Send`, `History`, `SetNotificationsEnabled`, `Subscribe` | `FAILED_PRECONDITION` |
| history before disabling notifications (`ChatServerImpl` rule) | `History` | `FAILED_PRECONDITION` |
| "Unknown user" | any | `NOT_FOUND` |
| "Username must not be blank" | any | `INVALID_ARGUMENT` |
| storage failure (`StorageException`) | storage RPCs | `INTERNAL` |

Status descriptions carry the original exception message, so the frontend can re-emit the
same `{"error": "..."}` JSON bodies the client protocol uses today.

### D5. Time

`google.protobuf.Timestamp` for `sent_at`, `at`, `last_connected`. DB columns are
`TIMESTAMP WITH TIME ZONE` with microsecond precision; protobuf Timestamp is nanosecond —
no loss. `Instant ↔ Timestamp` conversion is standard (`Timestamps.fromNanos(instant.getEpochSecond() * 1_000_000_000L + instant.getNano())` and back); helpers may live in
each service until a shared utility proves itself (YAGNI for now).

## Files

- Create: `proto/pom.xml`
- Create: `proto/src/main/proto/common.proto`, `storage.proto`, `chat.proto`
- Create: `proto/src/test/java/ru/archlabs/proto/ProtoRoundTripTest.java`
- Move: root `src/` → `app/src/`; root build plugins → `app/pom.xml`
- Rewrite: root `pom.xml` (parent, modules `proto`, `app`)
- Modify: `Dockerfile` (build `app` from the reactor: `mvn -B -ntp -pl app -am -Dmaven.test.skip=true package`)
- Modify: `.dockerignore` (root `target` pattern no longer matches module targets — use `**/target`)
- Move: `docs/superpowers/plans/2026-10-21-netty-http-server.md` → mark superseded
  (its Netty frontend design survives inside sub-project 4)

## Testing

The lab requires each layer covered by tests separately; for contracts that means:

1. **`ProtoRoundTripTest`** (in `proto` module, no Docker needed):
   - build a `common.Message` with a `Timestamp` at microsecond precision, serialize,
     parse, assert field-for-field equality including nanos;
   - build each request/response type of `ChatStorage` and `ChatService` (including
     `repeated` fields: `ClearNotifications.message_ids`, `History` results), round-trip,
     assert equality — this pins the schema against accidental field-number changes only
     insofar as Java-API equality goes; field numbers themselves are asserted literally
     in the test (e.g. `assertEquals(4, Message.getSentAtFieldNumber())`) so a renumbering
     breaks the test;
   - assert the generated service descriptors exist
     (`ChatStorage.getServiceDescriptor()`, `ChatService.getServiceDescriptor()`).
2. **Reactor regression:** `mvn test` at the root — all 32 existing app tests still pass
   (Testcontainers, needs Docker) plus the new proto tests.
3. **Docker regression:** `docker build -t arch-labs-chat .` still succeeds with the
   restructured modules.

## Acceptance criteria

- `mvn test` green at root: existing 32 app tests + new proto tests.
- `docker build` green.
- No `.proto`-generated Java committed; `proto/target/generated-sources` regenerated per build.
- Both services defined exactly as in D3; error mapping exactly as in D4.
- Existing package/API of `ru.archlabs.chat.*` unchanged (only relocated to `app/`).

## Risks

- **Codegen needs network** on first build (protoc binary + gRPC plugin downloads into
  `.m2-repository`). One-time cost; afterwards offline builds work.
- **`javax.annotation-api`**: generated gRPC stubs reference `@javax.annotation.Generated`;
  without the provided-scope dependency compilation fails under `-Werror`.
- **Reactor move** touches every path reference (Dockerfile, .dockerignore, .mvn stays at
  root); mitigated by the Docker regression test above.
