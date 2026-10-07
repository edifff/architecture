package ru.archlabs.chat.http;

import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.time.Instant;

/**
 * Описание HTTP-протокола чата: имена путей, типы событий и структуры запросов/ответов.
 *
 * <p>Клиент вызывает функции сервера обычными HTTP-запросами:
 *
 * <ul>
 *   <li>{@code POST /sessions/{username}} — подключиться к чату;
 *   <li>{@code DELETE /sessions/{username}} — отключиться;
 *   <li>{@code GET /users/{username}} — получить состояние пользователя;
 *   <li>{@code POST /messages/{username}} — отправить сообщение ({@code {"text": ...}});
 *   <li>{@code GET /messages/{username}} — получить историю (нужны выключенные уведомления);
 *   <li>{@code PUT /notifications/{username}} — включить/выключить уведомления
 *       ({@code {"enabled": true|false}});
 *   <li>{@code GET /events/{username}} — поток оповещений (Server-Sent Events).
 * </ul>
 *
 * <p>Оповещения доставляются как события SSE: каждому сообщению соответствует
 * событие {@code message} с JSON-телом {@link MessagePayload}.
 */
public final class ChatProtocol {
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";
    public static final String MESSAGE_EVENT = "message";
    public static final String DATA_PREFIX = "data:";

    private ChatProtocol() {
    }

    /** Тело ответа на успешное подключение. */
    public record SessionPayload(String username) {
    }

    /** Запрос на отправку сообщения. */
    public record SendMessageRequest(String text) {
    }

    /** Запрос на изменение настройки уведомлений. */
    public record NotificationsRequest(boolean enabled) {
    }

    /** Сообщение в сети. Время передаётся строкой ISO-8601. */
    public record MessagePayload(long id, String sender, String text, String sentAt) {
        public static MessagePayload from(Message message) {
            return new MessagePayload(
                    message.id(), message.sender(), message.text(), message.sentAt().toString());
        }

        public Message toMessage() {
            return new Message(id, sender, text, Instant.parse(sentAt));
        }
    }

    /** Состояние пользователя в сети. */
    public record UserPayload(
            String name, boolean online, String lastConnected, boolean notificationsEnabled) {
        public static UserPayload from(UserState state) {
            return new UserPayload(
                    state.name(),
                    state.online(),
                    state.lastConnected() == null ? null : state.lastConnected().toString(),
                    state.notificationsEnabled());
        }

        public UserState toUserState() {
            return new UserState(
                    name,
                    online,
                    lastConnected == null ? null : Instant.parse(lastConnected),
                    notificationsEnabled);
        }
    }

    /** Тело ответа при ошибке. */
    public record ErrorPayload(String error) {
    }
}
