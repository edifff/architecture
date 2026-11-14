package ru.archlabs.chat.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.domain.Notification;

/**
 * Storage port used by the domain. Implementations may be in memory or embedded.
 */
public interface ChatStorage extends AutoCloseable {
    void initialize();

    void connect(String name, Instant at);

    void disconnect(String name, Instant at);

    void setNotificationsEnabled(String name, boolean enabled);

    Optional<ChatUser> findUser(String name);

    List<ChatUser> allUsers();

    Message appendMessage(String sender, String text, List<String> notificationRecipients, Instant at);

    List<Message> history(int limit);

    Notification enqueueNotification(String recipient, Message message, Instant at);

    List<Notification> pendingNotifications(String recipient);

    void markNotificationDelivered(long notificationId, Instant at);

    void resetOnlineUsers();

    @Override
    default void close() {
    }
}
