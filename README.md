# Arch Labs: Chat System

A layered chat system. The input container exposes Netty HTTP/SSE, the domain container
serves gRPC, and the storage container owns an embedded H2 database. The chat and storage
layers are sharded by user into three identical stacks, with the input container acting
as the deterministic router and scatter-gather aggregator.

## Code style

```shell
mvn checkstyle:check
```

Checkstyle runs during `validate`; `var` is forbidden. The project intentionally uses
no Javadoc comments, so Javadoc checks and repeated-string checks in tests are
suppressed in `suppressions.xml`.

## Architecture

```text
api (Netty HTTP/SSE + Facades) --gRPC chat.proto--> chat (domain)
chat (domain) --gRPC storage.proto--> storage (JDBC/H2) --JDBC--> H2 file
```

| Layer | Module / container | Responsibility | Contract |
| --- | --- | --- | --- |
| Input | `api` / `api` | Netty HTTP/SSE, Facade parsing, validation, protobuf conversion | `chat.proto` |
| Domain | `chat-service` / `chat-N` | sessions, delivery, notification fan-out | `chat.proto`, `storage.proto` |
| Storage | `storage-service` / `storage-N` | JDBC/H2, transactions, crash recovery | `storage.proto` |
| Contracts | `proto` | protobuf schemas and gRPC codegen | — |
| Core | `core` | domain records, policies, in-memory adapter, metrics | — |

### Contracts

- `proto/common.proto` — `ChatMessage`, `UserState`, `ChatNotification`, `Empty`.
- `proto/chat.proto` — `ChatService` with `Connect`, `Disconnect`, `Send`, `Deliver`,
  `SendStatus`, `History`, `LocalHistory`, `SetNotifications`, `GetUser`, and
  server-streaming `Subscribe`.
- `proto/storage.proto` — `StorageService` with presence, message, history, outbox, and
  acknowledgement operations.

`ChatGrpcService` maps domain failures to gRPC statuses. `GrpcChatStorage` maps them
back to domain exceptions for the chat layer. `GrpcChatGateway` does the same for the
api layer, so HTTP status mapping stays independent of protobuf details.

### Input element

The input element is the `ChatFacades` group: one facade for sessions, messages,
notifications, and users. A facade parses JSON, validates it, and converts it into the
internal command represented by `ChatGateway`. In the layered deployment
`GrpcChatGateway` converts that internal call into a protobuf request; in the sharded
deployment `ShardedChatGateway` routes and aggregates across chat shards.

| Category | Facade | Netty handler |
| --- | --- | --- |
| Sessions | `SessionFacade` | `SessionsHandler` |
| Messages | `MessageFacade` | `MessagesHandler` |
| Notifications | `NotificationFacade` | `NotificationsHandler` |
| Users | `UserFacade` | `UsersHandler` |
| Event stream | `NotificationFacade` | `EventsHandler` |
| Shard assignment | `ShardFacade` | `ShardHandler` |

`ChatFacades` groups the facades. `ChatRequestRouter` chooses a route by method and
path, and `ResponseWriter` owns HTTP serialization. The HTTP layer depends on
`ChatGateway`, not on Netty internals or storage.

### Protocol

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| `POST` | `/sessions/{user}` | empty | `201` + user state |
| `DELETE` | `/sessions/{user}` | empty | `204` |
| `GET` | `/users/{user}` | — | `200` + user state |
| `POST` | `/messages/{user}` | `{"text":"..."}` | `201` + message |
| `GET` | `/messages/{user}?limit=50` | — | `200` + message array |
| `PUT` | `/notifications/{user}` | `{"enabled":true}` | `204` |
| `GET` | `/events/{user}` | — | `200` + `text/event-stream` |
| `POST` | `/status/{user}` | `{"text":"..."}` | `200` + aggregated status report |
| `GET` | `/shards/{user}` | — | `200` + deterministic shard assignment |

The event stream sends:

```text
event: message
data: {"id":1,"sender":"bob","text":"hello","createdAt":"..."}
```

Errors use `{"error":"..."}` and map validation to `400`, duplicate/closed sessions to
`409`, unknown users to `404`, and unexpected failures to `500`.

## Sharding by user

The layered system is split into three identical shard stacks. The three chat shards
share one image, the three storage shards share another, and each stack owns a different
subset of the users and their chats:

```text
                         ┌──────────────────────────── api (input) ──────────────────────────┐
HTTP/SSE clients ───────►│ ShardRouter: FNV-1a(user) mod 3                                    │
                         │ per-user calls ──► one shard        general calls ──► all shards   │
                         └───────┬───────────────────┬───────────────────┬───────────────────┘
                                 │ gRPC              │ gRPC              │ gRPC
                           chat-1│             chat-2│             chat-3│
                                 │ gRPC              │ gRPC              │ gRPC
                         storage-1            storage-2            storage-3
                                 │ JDBC              │ JDBC              │ JDBC
                          H2 volume 1          H2 volume 2          H2 volume 3
```

The user is the business entity key. The same deterministic function is used by every
input container, so a user always lands on the same chat and storage pair:

```java
shardIndex = fnv1a64(user.strip()) % shardCount
```

FNV-1a is used instead of `String.hashCode()` so the mapping is stable across JVM
versions, container restarts, and replicas. `GET /shards/{user}` exposes the assignment.
The shard list must be identical and in the same order on every api replica. Each entry
is `host:port`, or `id@host:port` when the report id should differ from the host:
`CHAT_SHARDS=chat-1@chat-1:9091,chat-2@chat-2:9091,chat-3@chat-3:9091`.

### How a request flows

| Request | Input element behaviour |
| --- | --- |
| session, user, notification preference, subscription | one sub-request to `shard(user)` |
| send a message | store and deliver on `shard(sender)`, then fan out `Deliver` to the other shards |
| history | validate the session on `shard(user)`, fetch `LocalHistory` from every shard, merge and trim by time |
| status to all chats | one `SendStatus` plus `Deliver` on every other shard, then aggregate the reports |

The input container waits for all parallel gRPC calls with `CompletableFuture.join()`
before it writes the HTTP response. The fan-out runs on virtual threads. A request
either returns a complete aggregate or a mapped error, so the caller never sees a
half-built response.

### General operation: status for every chat

`POST /status/{user}` sends a status message from the user to every chat in the system.
The chat option models one shared room, so every user's chat is a view of the same
conversation and the set of chats related to the user is the set of all user chats. This
is exactly the operation that cannot be served by one shard, so it exercises the whole
pattern: the input element splits it into one sub-request per shard, every shard applies
it to the chats it owns and reports counters, and the input element combines the
per-shard counters into one response.

```shell
curl -X POST http://localhost:8080/status/alice \
  -H 'Content-Type: application/json' -d '{"text":"maintenance at 22:00"}'
```

```json
{
  "sender": "alice",
  "message": {"id": 7, "sender": "alice", "text": "maintenance at 22:00", "createdAt": "..."},
  "shardCount": 3,
  "chats": 4,
  "delivered": 2,
  "queued": 2,
  "shards": [
    {"shard": "chat-3", "chats": 1, "delivered": 1, "queued": 0},
    {"shard": "chat-1", "chats": 2, "delivered": 1, "queued": 1},
    {"shard": "chat-2", "chats": 1, "delivered": 0, "queued": 1}
  ]
}
```

`chats` is the number of user chats owned by each shard, `delivered` counts live
sessions with notifications enabled, and `queued` counts offline users whose pending
notifications were stored. The message itself is stored once, on the sender's shard;
other shards keep self-contained notification rows, so cross-shard delivery does not
need a foreign key to a message owned by another shard.

### Storage shard details

- Each storage container has its own H2 file in its own named volume.
- Identities are offset per shard: shard `i` starts at `i + 1` and increments by
  `shardCount`, so message ids stay unique after histories are merged.
- Notifications copy the message fields (`sender`, `body`, `message_created_at`).
  A notification can therefore reference a message that lives on a different shard.
- `StorageMain` reads `SHARD_INDEX`, `SHARD_COUNT`, and `SHARD_ID`; the chat container
  reads `SHARD_ID` for its delivery reports.

### Sharding trade-offs

- Fan-out cost is O(number of shards) per message and per status request.
- There is no replication inside a shard, so a stopped shard fails the requests whose
  users it owns; the system shows the pattern, not high availability.
- Adding a shard changes `mod N` assignments. A production system would use consistent
  hashing or a shard directory and migrate the affected users.
- The input layer has no per-user state, so it can be replicated behind a load balancer
  without sticky sessions.

## Run

The only supported deployment is the sharded stack with one database per storage shard:

```shell
docker compose up -d --build
docker compose ps
```

Services: api `8080`, Prometheus `9090`. Chat shards are published on gRPC
`9091`–`9093` and metrics `9101`–`9103`; storage shards on gRPC `9191`–`9193` and
metrics `9111`–`9113`.

```shell
for user in alice bob carol dave; do
  curl -s -X POST "http://localhost:8080/sessions/$user" >/dev/null
done
curl -s http://localhost:8080/shards/alice
curl -s http://localhost:8080/shards/bob
curl -s -X POST http://localhost:8080/messages/bob \
  -H 'Content-Type: application/json' -d '{"text":"across shards"}'
curl -s 'http://localhost:8080/messages/alice?limit=10'
curl -s -X POST http://localhost:8080/status/alice \
  -H 'Content-Type: application/json' -d '{"text":"maintenance at 22:00"}'
```

The first two `shards` calls show different shard names. The history request proves the
input layer merged messages written to different shards. The status response shows a
non-zero `chats` count on every shard that owns at least one user.

Persistence check for one shard:

```shell
curl -s http://localhost:8080/shards/alice       # for example chat-3 / storage-3
docker compose restart chat-3 storage-3
curl -s -X POST http://localhost:8080/sessions/alice
curl -s 'http://localhost:8080/messages/alice?limit=10'
```

### Tests

```shell
mvn test
docker compose --profile test run --rm tests
```

The separate test container runs the full reactor with no Docker-in-Docker dependency
because the storage tests use embedded H2.

| Test | What it proves |
| --- | --- |
| `ProtoRoundTripTest` | messages round-trip; service descriptors exist |
| `InMemoryChatServerTest`, `ChatMetricsTest` | domain behavior, concurrency, metrics |
| `JdbcChatStorageTest`, `PersistentChatServerTest` | H2, restart, crash recovery, disjoint shard identities |
| `StorageGrpcServiceTest` | storage contract over gRPC |
| `ChatGrpcServiceTest` | domain contract, `Deliver`, `SendStatus`, `LocalHistory` over gRPC |
| `FacadesTest`, `ChatHttpServerTest`, `GrpcChatGatewayTest` | input parsing, Netty HTTP/SSE, gRPC gateway |
| `ShardRouterTest` | stable FNV-1a assignments and balanced distribution |
| `ShardedChatGatewayTest` | three in-process shards: routing, cross-shard live delivery, offline queueing, merged history, aggregated status |
| `InMemoryShardStorageTest` | in-memory shards never generate the same message id |

### Concurrency and failure tests

Every concurrency layer has a stress test with
`Runtime.getRuntime().availableProcessors() + 4` workers, so the worker count is always
greater than the core count.

| Layer | Test | Contention / failure |
| --- | --- | --- |
| Core | `concurrentSendsBeyondCoreCountKeepEveryMessage` | more senders than cores; every message and id is retained |
| Core | `concurrentConnectBeyondCoreCountAllowsExactlyOneSession` | many simultaneous logins for one user; exactly one wins |
| Storage | `concurrentFirstConnectsBeyondCoreCountAreSafe` | many simultaneous first-time `connect` calls on one H2 row |
| Storage | `concurrentAppendsBeyondCoreCountKeepIdsUnique` | parallel H2 transactions, unique identity values |
| Chat gRPC | `concurrentSendsBeyondCoreCountKeepEveryMessage` | concurrent `Send` calls plus one live subscriber |
| API | `handlesMoreConcurrentClientsThanCores` | more Netty clients than cores, merged history verified |
| API | `concurrentDuplicateConnectsBeyondCoreCountCreateOneSession` | HTTP race on one user: one `201`, the rest `409` |
| Sharding | `handlesMoreConcurrentUsersThanCoresAcrossShards` | concurrent users across all three shards and cross-shard delivery |
| Sharding | `concurrentDuplicateConnectBeyondCoreCountCreatesOneSession` | race on the shard-selected user |
| Sharding | `failsWhenOneShardIsUnavailable` | one shard is stopped; general calls fail fast, single-shard reads still work |

Two real races were exposed and fixed by these tests:

- H2 `connect` used `UPDATE` followed by `INSERT`, so simultaneous first-time connects
  could violate the primary key. It now uses `MERGE INTO users ... KEY(name)`, which
  preserves the notification preference for existing users.
- gRPC server-stream observers are not thread-safe. Concurrent sends could call
  `onNext` from several threads, so the subscription listener now serializes stream
  writes.

The unavailable-shard test deliberately breaks the system: `POST /status/{user}` and
merged history raise `ChatInternalException` when any shard is down, while
`GET /users/{user}` for a user on a live shard still succeeds. That is the expected
fail-fast behaviour without replication.

## Performance

The load client and measured scaling data are in `perf/README.md`.

```shell
perf/load.sh 1 20
perf/load.sh 10 20
perf/load.sh 50 20
```
