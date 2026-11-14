# Arch Labs: Chat System

This repository is a step-by-step implementation of the five architecture labs. The
chat option was selected for lab 1, and Netty was selected for the IP interface in lab 3.

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
because the storage tests use embedded H2. The Docker test run was verified to pass all
66 tests.

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

The final system is split into three containers with explicit contracts:

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

### Run lab 5

```shell
docker compose up -d --build
docker compose ps
curl -X POST http://localhost:8080/sessions/alice
curl -X POST http://localhost:8080/sessions/bob
curl -X POST http://localhost:8080/messages/bob \
  -H 'Content-Type: application/json' -d '{"text":"through three layers"}'
curl -N http://localhost:8080/events/alice
```

Services: api `8080`, chat gRPC `9091`, storage gRPC `9092`, Prometheus `9090`.
Metrics: api `9100`, chat `9101`, storage `9102`.

### Tests per layer

| Layer | Test | What it proves |
| --- | --- | --- |
| Contracts | `ProtoRoundTripTest` | messages round-trip; service descriptors exist |
| Core | `InMemoryChatServerTest`, `ChatMetricsTest` | domain behavior, concurrency, metrics |
| Storage | `JdbcChatStorageTest`, `PersistentChatServerTest` | H2, restart, crash recovery |
| Storage gRPC | `StorageGrpcServiceTest` | storage contract over gRPC |
| Chat gRPC | `ChatGrpcServiceTest` | domain contract and live stream over gRPC |
| API | `FacadesTest`, `ChatHttpServerTest`, `GrpcChatGatewayTest` | input parsing, Netty HTTP/SSE, gRPC gateway |

### Performance

The load client and measured scaling data are in `perf/README.md`.

```shell
perf/load.sh 1 20
perf/load.sh 10 20
perf/load.sh 50 20
```
