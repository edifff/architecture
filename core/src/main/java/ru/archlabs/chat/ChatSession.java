package ru.archlabs.chat;

import java.util.List;
import java.util.function.Consumer;

import ru.archlabs.chat.domain.Message;

/**
 * A user's live connection. Each session belongs to exactly one user.
 */
public interface ChatSession extends AutoCloseable {
    String userName();

    Message send(String text);

    List<Message> history(int limit);

    void setNotificationsEnabled(boolean enabled);

    /**
     * Registers a notification listener and returns a handle that removes it.
     * Notifications received before a listener is attached are buffered.
     */
    AutoCloseable addListener(Consumer<Message> listener);

    @Override
    void close();
}
