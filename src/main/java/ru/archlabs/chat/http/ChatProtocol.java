package ru.archlabs.chat.http;

import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.time.Instant;


public final class ChatProtocol {
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";
    public static final String MESSAGE_EVENT = "message";
    public static final String DATA_PREFIX = "data:";

    private ChatProtocol() {
    }

    public record SessionPayload(String username) {
    }

    public record SendMessageRequest(String text) {
    }

    public record NotificationsRequest(boolean enabled) {
    }

    public record MessagePayload(long id, String sender, String text, String sentAt) {
        public static MessagePayload from(Message message) {
            return new MessagePayload(message.id(), message.sender(), message.text(), message.sentAt().toString());
        }

        public Message toMessage() {
            return new Message(id, sender, text, Instant.parse(sentAt));
        }
    }

    /** Состояние пользователя в сети. */
    public record UserPayload(String name, boolean online, String lastConnected, boolean notificationsEnabled) {
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

    public record ErrorPayload(String error) {
    }
}
