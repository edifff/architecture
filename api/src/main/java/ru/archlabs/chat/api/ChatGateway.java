package ru.archlabs.chat.api;

import java.util.List;
import java.util.function.Consumer;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;

/**
 * Boundary between the HTTP input element and the chat implementation.
 * Lab 3 supplies an in-process implementation; lab 5 supplies a gRPC one.
 */
public interface ChatGateway extends AutoCloseable {
    ChatUser connect(String user);

    void disconnect(String user);

    Message send(String user, String text);

    List<Message> history(String user, int limit);

    void setNotificationsEnabled(String user, boolean enabled);

    ChatUser user(String user);

    AutoCloseable subscribe(String user, Consumer<Message> listener);

    ChatMetrics metrics();

    @Override
    void close();
}
