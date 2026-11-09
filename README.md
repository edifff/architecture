# Arch Labs: Chat System

This repository is a step-by-step implementation of the five architecture labs. The
chat option was selected for lab 1, and Netty was selected for the IP interface in lab 3.

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
| Message | `domain.ChatMessage` |
| Notification | `domain.ChatNotification` |
| User presence/preferences | `domain.UserState` |
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
