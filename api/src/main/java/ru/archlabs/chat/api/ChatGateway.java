package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.metrics.ChatMetrics;

import java.util.List;
import java.util.function.Consumer;

/**
 * Boundary between the HTTP input element and the chat implementation.
 * Lab 3 supplies an in-process implementation; lab 5 supplies a gRPC one.
 */
public interface ChatGateway extends AutoCloseable {
    UserState connect(String user);

    void disconnect(String user);

    ChatMessage send(String user, String text);

    List<ChatMessage> history(String user, int limit);

    void setNotificationsEnabled(String user, boolean enabled);

    UserState user(String user);

    AutoCloseable subscribe(String user, Consumer<ChatMessage> listener);

    ChatMetrics metrics();

    @Override
    void close();
}
