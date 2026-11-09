package ru.archlabs.chat.storage;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotification;
import ru.archlabs.chat.domain.UserState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage port used by the domain. Implementations may be in memory or embedded.
 */
public interface ChatStorage extends AutoCloseable {
    void initialize();

    void connect(String name, Instant at);

    void disconnect(String name, Instant at);

    void setNotificationsEnabled(String name, boolean enabled);

    Optional<UserState> findUser(String name);

    List<UserState> allUsers();

    ChatMessage appendMessage(String sender, String text, List<String> notificationRecipients, Instant at);

    List<ChatMessage> history(int limit);

    ChatNotification enqueueNotification(String recipient, ChatMessage message, Instant at);

    List<ChatNotification> pendingNotifications(String recipient);

    void markNotificationDelivered(long notificationId, Instant at);

    void resetOnlineUsers();

    @Override
    default void close() {
    }
}
