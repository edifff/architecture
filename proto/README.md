# Модуль `proto` — контракты между слоями

Здесь лежат protobuf-схемы, через которые слои общаются исключительно по gRPC, и
кодоген, который собирает из них Java-классы и заготовки stub'ов.

| Файл | Пакет / Java-пакет | Содержимое |
|---|---|---|
| `common.proto` | `common` → `ru.archlabs.proto.common` | `Message`, `UserState`, `Empty` |
| `storage.proto` | `storage` → `ru.archlabs.proto.storage` | сервис `ChatStorage` (10 RPC) |
| `chat.proto` | `chat` → `ru.archlabs.proto.chat` | сервис `ChatService` (7 RPC) |

Ключевые решения:

- сгенерированный Java-исходник в репозиторий не коммитится — `protobuf-maven-plugin`
  пересобирает его на каждой сборке (`protoc` 4.36.2 и `protoc-gen-grpc-java` 1.85.1
  скачиваются под платформу сборки через `os-maven-plugin`);
- `grpc-netty-shaded`, а не `grpc-netty`: у фронтендного модуля свой
  `io.netty:netty-codec-http`, затенённый Netty внутри gRPC исключает конфликт версий;
- контракты проверяет `ProtoRoundTripTest` — round-trip каждого типа, литеральные
  номера полей и дескрипторы сервисов (Docker не нужен).

## Маппинг RPC на домен и HTTP

`ChatStorage` повторяет доменный интерфейс `ru.archlabs.chat.storage.ChatStorage`
один-в-один; `ChatService` повторяет клиентский HTTP-протокол:

| RPC `ChatService` | HTTP-операция |
|---|---|
| `Connect` | `POST /sessions/{u}` |
| `Disconnect` | `DELETE /sessions/{u}` |
| `Send` | `POST /messages/{u}` |
| `History` | `GET /messages/{u}` |
| `SetNotificationsEnabled` | `PUT /notifications/{u}` |
| `User` | `GET /users/{u}` |
| `Subscribe` (серверный поток) | `GET /events/{u}` (SSE) |

Unary-RPC возвращает ровно одно сообщение, поэтому списки обёрнуты:
`MessagesResponse{messages}` и `UserStatesResponse{users}`.

## Соглашение об ошибках (обязательно для подпроектов 2–4)

Доменное исключение маппится на gRPC-статус **по RPC-контексту**: один и тот же
`IllegalStateException` означает разные вещи в разных вызовах, поэтому и статус
разный. Описание статуса несёт текст исключения — фронтенд по нему воспроизводит
те же тела `{"error": "..."}`, что и сегодня.

| Условие | Контекст RPC | gRPC `Status` |
|---|---|---|
| «User is already online» | `Connect` | `ALREADY_EXISTS` |
| «Session is not active» | `Send`, `History`, `SetNotificationsEnabled`, `Subscribe` | `FAILED_PRECONDITION` |
| история запрошена до отключения уведомлений | `History` | `FAILED_PRECONDITION` |
| «Unknown user» | любой | `NOT_FOUND` |
| «Username must not be blank» | любой | `INVALID_ARGUMENT` |
| сбой хранилища (`StorageException`) | RPC хранилища | `INTERNAL` |

Обратное соответствие для слоя представления (подпроект 4), выведенное из текущей
HTTP-карты кодов: `ALREADY_EXISTS`/`FAILED_PRECONDITION` → 409, `INVALID_ARGUMENT`
→ 400, `NOT_FOUND` → 404, `INTERNAL` → 500.

## Время

`sent_at`, `at`, `last_connected` — `google.protobuf.Timestamp`. Колонки базы —
`TIMESTAMP WITH TIME ZONE` с микросекундной точностью, протокол — наносекунды,
потерь нет. Конвертация — `com.google.protobuf.util.Timestamps`
(зависимость `protobuf-java-util`):

```java
// Instant -> Timestamp
Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();

// Timestamp -> Instant
Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
```

Хелперы живут в каждом сервисе, пока не докажут, что нужны общими (YAGNI).
