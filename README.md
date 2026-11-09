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
