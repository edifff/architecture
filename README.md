# Arch Labs: Chat System

This repository is a step-by-step implementation of the six architecture labs. The
chat option was selected for lab 1, and Netty was selected for the IP interface in lab 3.
Lab 6 shards the layered system into identical chat and storage containers, with the
input container acting as the deterministic router and scatter-gather aggregator.

## Code style

```shell
mvn checkstyle:check
```

Checkstyle runs during `validate`; `var` is forbidden. The project intentionally uses
no Javadoc comments, so Javadoc checks and repeated-string checks in tests are
suppressed in `suppressions.xml`.

All previous history is preserved. The commit before the rebuild is tagged
`pre-rebuild`; the rebuild itself starts with `chore: clear working tree for the lab
rebuild`.

## Lab 1: in-memory asynchronous chat

The domain is a small chat server with one live `ChatSession` per user. A user can send
a message, read history, enable or disable notifications, and attach a listener that
receives messages asynchronously. All state is in RAM.

The implementation is split by logical component category so each concern has its own
class:

| Category | Class |
| --- | --- |
| Message | `domain.Message` |
| Notification | `domain.Notification` |
| User presence/preferences | `domain.ChatUser` |
| Session | `ChatSession`, `service.ChatSessionImpl` |
| Session registry | `service.SessionRegistry` |
| User accounts | `service.UserAccountService` |
| Messages/history | `service.MessageService` |
| Notification outbox | `service.NotificationService` |
| Live fan-out | `service.MessageDeliveryService` |
| Server orchestration | `service.ChatServerImpl` |
| Metrics | `metrics.ChatMetrics` |
| Prometheus endpoint | `metrics.MetricsHttpServer` |
| RAM storage adapter | `storage.InMemoryChatStorage` |

The `ChatServerImpl` keeps session state behind one lock, while message listeners run
outside the lock. Concurrent tests prove that parallel sends never lose messages or
create duplicate ids.

### Run lab 1

```shell
mvn -q -pl core -am test
mvn -q -pl core exec:java -Dexec.mainClass=ru.archlabs.chat.demo.InMemoryChatDemo
```

The demo prints delivered messages and starts Prometheus metrics on
`http://localhost:9100/metrics`.

### Lab 1 tests

- `ChatServerContractTest` covers connection lifecycle, live delivery, history,
  validation, listener buffering, disabled notifications, offline buffering, and
  error cases.
- `InMemoryChatServerTest` runs that contract against RAM storage.
- `ChatMetricsTest` checks request, notification, and online-user metrics plus the
  Prometheus text endpoint.
- Concurrency tests cover duplicate simultaneous logins and 8 threads sending 320
  messages without loss or duplicate ids.
- The stress tests use `availableProcessors() + 4` workers. The worker count is always
  greater than the core count, so the scheduler must switch between blocked senders.

The contract test is reusable: lab 2 runs the same behavior against the embedded
database.

## Lab 2: embedded H2 storage

`storage-service` adds `JdbcChatStorage`, an H2 implementation of the same
`ChatStorage` port. Messages, users, preferences, and undelivered notifications are
committed to a file database. Every message and its pending notifications are written
in one transaction.

On startup the server calls `resetOnlineUsers()`. This is the crash-recovery boundary:
users from a previous process are marked offline, while messages and pending
notifications remain. Reconnecting users receive their buffered notifications.

### Run lab 2

```shell
mvn -q -pl storage-service -am test
mvn -q install -DskipTests
mvn -q -pl storage-service exec:java
```

The database is `./data/chat` by default. Override it with `ARCHLABS_DB_URL`:

```shell
ARCHLABS_DB_URL='jdbc:h2:file:/tmp/chat;DB_CLOSE_ON_EXIT=FALSE' \
  mvn -q -pl storage-service exec:java
```

Run the demo twice: the second run shows the message history from the first run plus a
new buffered notification. Metrics are exposed on port 9101.

### Lab 2 tests

- `JdbcChatStorageTest` proves committed messages and notifications survive reopening,
  and that `resetOnlineUsers()` is the crash recovery boundary.
- `PersistentChatServerTest` runs the full lab 1 contract against H2.
- Restart tests cover a normal shutdown and a simulated abnormal shutdown.
- The H2 concurrency test is reduced to four senders and ten messages each to keep
  disk-backed test time reasonable; the RAM contract keeps the larger 8 × 40 case.

## Lab 3: Netty IP interface

`api` is the input layer. It exposes a Netty HTTP server and converts JSON into calls on
`ChatGateway`. Live notifications are delivered as Server-Sent Events, so a client can
keep one HTTP connection open for the event stream.

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
| `POST` | `/status/{user}` | `{"text":"..."}` | `200` + aggregated status report (lab 6) |
| `GET` | `/shards/{user}` | — | `200` + deterministic shard assignment (lab 6) |

The event stream sends:

```text
event: message
data: {"id":1,"sender":"bob","text":"hello","createdAt":"..."}
```

Errors use `{"error":"..."}` and map validation to `400`, duplicate/closed sessions to
`409`, unknown users to `404`, and unexpected failures to `500`.

### Input element split by category

| Category | Facade | Netty handler |
| --- | --- | --- |
| Sessions | `SessionFacade` | `SessionsHandler` |
| Messages | `MessageFacade` | `MessagesHandler` |
| Notifications | `NotificationFacade` | `NotificationsHandler` |
| Users | `UserFacade` | `UsersHandler` |
| Event stream | `NotificationFacade` | `EventsHandler` |

`ChatFacades` groups the four facades. `ChatRequestRouter` chooses a route by method and
path, and `ResponseWriter` owns HTTP serialization. The HTTP layer depends on
`ChatGateway`, not on Netty internals or storage.

### Run lab 3

```shell
mvn -q package -DskipTests
ARCHLABS_STORAGE=memory ARCHLABS_HTTP_PORT=8080 java -jar app/target/app.jar
```

Use `ARCHLABS_STORAGE=h2` (the default) for persistence. The metrics endpoint is
`http://localhost:9100/metrics`.

```shell
curl -X POST http://localhost:8080/sessions/alice
curl -X POST http://localhost:8080/sessions/bob
curl -X POST http://localhost:8080/messages/bob \
  -H 'Content-Type: application/json' -d '{"text":"hello"}'
curl -N http://localhost:8080/events/alice
```

### Lab 3 tests

- `FacadesTest` checks parsing, validation, limits, and delegation without Netty.
- `ChatHttpServerTest` runs the real Netty server over the in-process domain and covers
  status codes, JSON bodies, history, notification preferences, disconnect, routing, and
  the SSE event stream.

## Lab 4: Docker containers

The multi-stage `Dockerfile` builds any Maven module selected by the `MODULE` build
argument. The runtime image uses Temurin JRE, runs as non-root UID 10001, installs a
`/metrics` healthcheck, and keeps H2 data in a named volume. Every module jar is shaded
with its own main class.

The monolith from labs 1–3 is still available under the `monolith` profile:

```shell
docker compose --profile monolith up -d --build app
```

That container listens on `8081` (HTTP/SSE) and `9110` (metrics) to avoid the layered
stack's ports. The H2 volume is `app-data`.

### Run lab 4 tests from the host and from a container

```shell
mvn test
docker compose --profile test run --rm tests
```

The separate test container runs the full reactor with no Docker-in-Docker dependency
because the storage tests use embedded H2. The Docker test run was verified to pass the
full suite (105 tests after lab 6).

### Persistence check

```shell
curl -X POST http://localhost:8081/sessions/bob
curl -X POST http://localhost:8081/messages/bob \
  -H 'Content-Type: application/json' -d '{"text":"before restart"}'
docker compose --profile monolith restart app
curl -X POST http://localhost:8081/sessions/alice
curl 'http://localhost:8081/messages/alice?limit=10'
```

## Lab 5: layered containers and protobuf

The lab 5 system is split into three containers with explicit contracts. Lab 6 keeps
these layers and shards the chat and storage layers horizontally:

```text
api (Netty HTTP/SSE + Facades) --gRPC chat.proto--> chat (domain)
chat (domain) --gRPC storage.proto--> storage (JDBC/H2) --JDBC--> H2 file
```

| Layer | Module / container | Responsibility | Contract |
| --- | --- | --- | --- |
| Input | `api` / `api` | Netty HTTP/SSE, Facade parsing, validation, protobuf conversion | `chat.proto` |
| Domain | `chat-service` / `chat` | sessions, delivery, notification fan-out | `chat.proto`, `storage.proto` |
| Storage | `storage-service` / `storage` | JDBC/H2, transactions, crash recovery | `storage.proto` |
| Contracts | `proto` | protobuf schemas and gRPC codegen | — |
| Core | `core` | domain records, policies, in-memory adapter, metrics | — |

### Contracts

- `proto/common.proto` — `ChatMessage`, `UserState`, `ChatNotification`, `Empty`.
- `proto/chat.proto` — `ChatService` with `Connect`, `Disconnect`, `Send`, `History`,
  `SetNotifications`, `GetUser`, and server-streaming `Subscribe`.
- `proto/storage.proto` — `StorageService` with presence, message, history, outbox, and
  acknowledgement operations.

`ChatGrpcService` maps domain failures to gRPC statuses. `GrpcChatStorage` maps them
back to domain exceptions for the chat layer. `GrpcChatGateway` does the same for the
api layer, so HTTP status mapping stays independent of protobuf details.

### Input element

The input element is still the `ChatFacades` group: one facade for sessions, messages,
notifications, and users. A facade parses JSON, validates it, and converts it into the
internal command represented by `ChatGateway`. In the layered deployment
`GrpcChatGateway` converts that internal call into a protobuf request.

### Run lab 5 (single-shard profile)

Lab 5 is kept as the `single` profile for comparison with the sharded stack:

```shell
docker compose --profile single up -d --build storage-single chat-single api-single
curl -X POST http://localhost:8085/sessions/alice
curl -X POST http://localhost:8085/sessions/bob
curl -X POST http://localhost:8085/messages/bob \
  -H 'Content-Type: application/json' -d '{"text":"through three layers"}'
curl -N http://localhost:8085/events/alice
```

Services: api `8085`, chat gRPC `9089`, storage gRPC `9190`, Prometheus `9090`.
Metrics: api `9105`, chat `9109`, storage `9114`.

### Tests per layer

| Layer | Test | What it proves |
| --- | --- | --- |
| Contracts | `ProtoRoundTripTest` | messages round-trip; service descriptors exist |
| Core | `InMemoryChatServerTest`, `ChatMetricsTest` | domain behavior, concurrency, metrics |
| Storage | `JdbcChatStorageTest`, `PersistentChatServerTest` | H2, restart, crash recovery |
| Storage gRPC | `StorageGrpcServiceTest` | storage contract over gRPC |
| Chat gRPC | `ChatGrpcServiceTest` | domain contract and live stream over gRPC |
| API | `FacadesTest`, `ChatHttpServerTest`, `GrpcChatGatewayTest` | input parsing, Netty HTTP/SSE, gRPC gateway |

## Lab 6: sharding by user

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
before it writes the HTTP response. That is the synchronous gather required by the lab;
the fan-out runs on virtual threads. A request either returns a complete aggregate or a
mapped error, so the caller never sees a half-built response.

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

### Run lab 6

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

### Lab 6 tests

| Test | What it proves |
| --- | --- |
| `ShardRouterTest` | stable FNV-1a assignments and balanced distribution |
| `ShardedChatGatewayTest` | three in-process shards: routing, cross-shard live delivery, offline queueing, merged history, aggregated status |
| `InMemoryShardStorageTest` | in-memory shards never generate the same message id |
| `JdbcChatStorageTest` | disjoint H2 identities, self-contained notifications, legacy schema migration |
| `ChatGrpcServiceTest` | `Deliver`, `SendStatus`, and `LocalHistory` contracts over gRPC |
| `ChatHttpServerTest` | `POST /status/{user}` and `GET /shards/{user}` over Netty |

### Concurrency and failure tests

Every concurrency layer now has a stress test with
`Runtime.getRuntime().availableProcessors() + 4` workers. On this machine that is 20
threads against 16 cores; the count is derived at runtime, so it is greater than the
core count on any host.

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

### Verified run

- `mvn test`: 105 tests, 0 failures.
- `docker compose up -d --build`: api + 3 chat + 3 storage + Prometheus all healthy.
- `GET /shards/{user}` assigned alice to `chat-3`, bob to `chat-1`, and carol to
  `chat-2`.
- bob's message was visible in alice's merged history, and alice's SSE stream received
  bob's live message from another shard.
- `POST /status/alice` returned three shard reports, four chats, and four deliveries.
- After restarting `storage-3` and `chat-3`, alice was offline, then reconnected with the
  same assignment and the full merged history.
- All seven Prometheus targets were `up`.

### Sharding trade-offs

- Fan-out cost is O(number of shards) per message and per status request.
- There is no replication inside a shard, so a stopped shard fails the requests whose
  users it owns; the lab shows the pattern, not high availability.
- Adding a shard changes `mod N` assignments. A production system would use consistent
  hashing or a shard directory and migrate the affected users.
- The input layer has no per-user state, so it can be replicated behind a load balancer
  without sticky sessions.

### Performance

The load client and measured scaling data are in `perf/README.md`.

```shell
perf/load.sh 1 20
perf/load.sh 10 20
perf/load.sh 50 20
```
