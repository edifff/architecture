# Лабораторная работа: слоистая архитектура чата

Чат перестроен из монолита в **слоистую архитектуру**: каждый слой — отдельный
контейнер, слои общаются только через protobuf по gRPC. Входной элемент — отдельный
модуль `api` с паттерном **Facade**: он разбирает пользовательское сообщение,
проверяет его и конвертирует в protobuf-запрос.

```
api (Netty HTTP + Facade) ──gRPC──▶ chat (домен) ──gRPC──▶ storage (хранение) ──JDBC──▶ db
```

## Слои

| Слой | Модуль | Контейнер | Ответственность | Контракт |
|---|---|---|---|---|
| Представление/вход | `api` | `api` (порт 8080) | Netty HTTP/SSE (точка входа для клиентов) + Facade: разбор, проверка, конвертация в protobuf; gRPC-клиент к chat | `chat.proto` |
| Домен | `chat-service` | `chat` | сессии, доставка, уведомления (`ChatServerImpl`) | `chat.proto`, `storage.proto` |
| Хранение | `storage-service` | `storage` | JDBC/PostgreSQL реализация `ChatStorage`, Flyway-миграции | `storage.proto` |
| Контракты | `proto` | — | protobuf-схемы и кодоген gRPC | — |
| Ядро | `core` | — | модели, интерфейсы, `ChatServerImpl`, метрики | — |

**Почему `api` — не frontend:** пользовательского интерфейса в работе нет, но
Netty-сервер остаётся **точкой входа** (initial access) для клиентов: он принимает
HTTP + JSON + SSE. Входной элемент — Facade — разбирает пользовательское сообщение,
проверяет его и конвертирует в protobuf, после чего вызывает chat-сервис по gRPC.

## Контракты (protobuf)

- `proto/src/main/proto/common.proto` — `Message`, `UserState`, `Empty`;
- `storage.proto` — сервис `storage.ChatStorage` (10 RPC, 1-в-1 с доменным
  интерфейсом `ChatStorage`);
- `chat.proto` — сервис `chat.ChatService` (7 RPC, включая серверный поток
  `Subscribe` для живых оповещений).

Сгенерированный код не коммитится: `protobuf-maven-plugin` собирает его на каждой
сборке (`protoc` 4.36.2, `protoc-gen-grpc-java` 1.85.1).

Соглашение об ошибках (доменное исключение → gRPC-статус по контексту RPC)
описано в `proto/README.md`.

## Входной элемент (Facade)

`api/src/main/java/ru/archlabs/chat/api/ChatFacade.java`:

1. `UserMessage.parse` — разбор строки `<OPERATION> <username> [аргумент]`;
2. `ChatFacade` — проверка (непустые имя/текст, корректный флаг) и конвертация
   в protobuf-запросы `chat.ChatService`;
3. вызов chat-сервиса по gRPC.

Пример:

```java
var message = UserMessage.parse("SEND ivan hello world"); // разбор
facade.send(message.username(), message.text());           // проверка + protobuf + gRPC
```

## Тесты по слоям (отдельно)

| Слой | Тест | Что проверяет |
|---|---|---|
| `proto` | `ProtoRoundTripTest` | round-trip сообщений, номера полей, дескрипторы сервисов |
| `core` | `InMemoryChatServerTest`, `ChatMetricsTest` | доменная логика без транспорта и БД |
| `storage-service` | `PostgresStorageTest`, `StorageGrpcServiceTest` | JDBC-хранилище и gRPC-контракт против реального PostgreSQL (Testcontainers) |
| `chat-service` | `ChatGrpcServiceTest` | gRPC-контракт домена поверх in-memory хранилища + поток `Subscribe` |
| `api` | `UserMessageTest`, `ChatFacadeTest`, `ChatHttpServerTest` | разбор/проверка/конвертация в protobuf; Netty HTTP поверх in-process gRPC |

```shell
mvn test        # весь реактор; Testcontainers требует Docker
```

## Запуск

```shell
docker compose up -d --build
```

Поднимаются `db`, `storage`, `chat`, `api` (вход, порт 8080), а также `prometheus`
(9090) и `grafana` (3000). `chat` и `storage` отдают метрики:
`chat:9101/metrics`, `storage:9102/metrics`.

## Замеры производительности

Методика и результаты — в `perf/README.md`:

```shell
perf/scale.sh chat 30 15s
perf/scale.sh storage 30 15s
```

k6 нативно вызывает gRPC, compose масштабирует нужный слой (`--scale`), а
Prometheus/Grafana показывают RPS, p95 и ошибки по слоям.

## Структура репозитория

```
proto/            контракты protobuf/gRPC
core/             доменное ядро
storage-service/  слой хранения (gRPC + JDBC)
chat-service/     доменный слой (gRPC)
api/              точка входа: Netty HTTP/SSE + Facade
perf/             k6-сценарии, Prometheus, Grafana, матрица масштабирования
Dockerfile        multi-target сборка (storage/chat)
compose.yaml      db + storage + chat + prometheus + grafana
```
