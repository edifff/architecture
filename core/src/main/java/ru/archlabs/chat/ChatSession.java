package ru.archlabs.chat;

import ru.archlabs.chat.domain.ChatMessage;

import java.util.function.Consumer;

/**
 * A user's live connection. Each session belongs to exactly one user.
 */
public interface ChatSession extends AutoCloseable {
    String userName();

    ChatMessage send(String text);

    java.util.List<ChatMessage> history(int limit);

    void setNotificationsEnabled(boolean enabled);

    /**
     * Registers a notification listener and returns a handle that removes it.
     * Notifications received before a listener is attached are buffered.
     */
    AutoCloseable addListener(Consumer<ChatMessage> listener);

    @Override
    void close();
}
